package com.hog.farm.service;

import com.hog.farm.common.BizException;
import com.hog.farm.common.Dict;
import com.hog.farm.domain.Batch;
import com.hog.farm.domain.Pen;
import com.hog.farm.domain.Pig;
import com.hog.farm.domain.ShipmentOrder;
import com.hog.farm.domain.TraceRecord;
import com.hog.farm.dto.ShipmentRequest;
import com.hog.farm.dto.TraceRequest;
import com.hog.farm.dto.Views.ShipmentPigVO;
import com.hog.farm.dto.Views.ShipmentReviewVO;
import com.hog.farm.dto.Views.WithdrawalVO;
import com.hog.farm.repository.BatchRepository;
import com.hog.farm.repository.PenRepository;
import com.hog.farm.repository.PigRepository;
import com.hog.farm.repository.ShipmentOrderRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 猪只级出栏放行。整批拦截和合格先出是显式策略；预检分组与最终提交共用同一套计算。
 */
@Service
public class ShipmentService {

    public static final String ALL_OR_NONE = "ALL_OR_NONE";
    public static final String PARTIAL = "PARTIAL";

    private final BatchRepository batchRepository;
    private final PigRepository pigRepository;
    private final PenRepository penRepository;
    private final ShipmentOrderRepository shipmentRepository;
    private final WithdrawalService withdrawalService;
    private final TraceService traceService;
    private final ImmuneService immuneService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ShipmentService(BatchRepository batchRepository, PigRepository pigRepository,
                           PenRepository penRepository, ShipmentOrderRepository shipmentRepository,
                           WithdrawalService withdrawalService, TraceService traceService,
                           ImmuneService immuneService) {
        this.batchRepository = batchRepository;
        this.pigRepository = pigRepository;
        this.penRepository = penRepository;
        this.shipmentRepository = shipmentRepository;
        this.withdrawalService = withdrawalService;
        this.traceService = traceService;
        this.immuneService = immuneService;
    }

    /** 不加锁预览：供操作员提交前核对放行、拦截、状态异常三组。 */
    @Transactional(readOnly = true)
    public ShipmentReviewVO preview(Long batchId, ShipmentRequest request) {
        Batch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new BizException("出栏批次不存在：" + batchId));
        return buildReview(batch, normalize(request), pigRepository.findByBatchIdOrderByIdAsc(batchId), null);
    }

    @Transactional
    public ShipmentReviewVO confirm(Long batchId, ShipmentRequest request) {
        ShipmentRequest normalized = normalize(request);
        if (!StringUtils.hasText(normalized.getRequestNo())) {
            normalized.setRequestNo(newRequestNo());
        }
        ShipmentOrder replay = shipmentRepository.findByRequestNo(normalized.getRequestNo()).orElse(null);
        if (replay != null) {
            return replayVO(replay);
        }

        Batch batch = batchRepository.lockById(batchId)
                .orElseThrow(() -> new BizException("出栏批次不存在：" + batchId));
        if (!"SELLING".equals(batch.getStatus())) {
            throw new BizException("只有「出栏中」的批次才能提交放行，当前状态："
                    + Dict.batchStatusName(batch.getStatus()));
        }

        // 固定顺序锁定本批全部猪只；用药登记也要先锁批次再锁猪只，因此确认期间补入会等待本事务结束后重算。
        List<Pig> allPigs = pigRepository.findByBatchIdOrderByIdAsc(batchId);
        Map<Long, Pig> lockedMap = new LinkedHashMap<>();
        if (!allPigs.isEmpty()) {
            for (Pig pig : pigRepository.lockByIds(allPigs.stream().map(Pig::getId).toList())) {
                lockedMap.put(pig.getId(), pig);
            }
        }
        ShipmentReviewVO review = buildReview(batch, normalized, new ArrayList<>(lockedMap.values()), null);
        if (!Boolean.TRUE.equals(review.getCanSubmit())) {
            throw new BizException(review.getMessage());
        }
        List<Pig> releasedPigs = review.getReleasedPigs().stream()
                .map(item -> lockedMap.get(item.getId())).toList();
        if (releasedPigs.isEmpty()) {
            throw new BizException("本次没有可放行猪只");
        }

        int count = releasedPigs.size();
        int sold = nvl(batch.getSoldCount()) + count;
        if (sold > nvl(batch.getIntakeCount())) {
            throw new BizException("累计出栏头数不得超过入栏头数");
        }

        LocalDateTime now = LocalDateTime.now();
        Map<Long, Integer> penDeltas = new LinkedHashMap<>();
        for (Pig pig : releasedPigs) {
            if (pig.getPenId() != null) {
                penDeltas.merge(pig.getPenId(), -1, Integer::sum);
            }
            pig.setStatus("SOLD");
            pig.setStatusDate(normalized.getShipDate());
            pig.setPenId(null);
            pig.setHouseId(null);
            pigRepository.save(pig);
            // 已出栏猪只的未完成免疫任务进入可追溯的终止状态
            immuneService.terminateForPig(pig.getId(), "SOLD");
        }
        for (Map.Entry<Long, Integer> entry : penDeltas.entrySet()) {
            penRepository.updatePigCount(entry.getKey(), entry.getValue());
        }

        batch.setSoldCount(sold);
        if (sold >= nvl(batch.getIntakeCount())) {
            batch.setStatus("CLEARED");
        }
        batchRepository.save(batch);

        TraceRequest traceRequest = new TraceRequest();
        traceRequest.setTraceType("SALE");
        traceRequest.setEventTitle(batch.getBatchNo() + " 出栏放行 " + count + " 头");
        traceRequest.setEventDesc(buildTraceDescription(normalized, review));
        traceRequest.setOperator(normalized.getOperator());
        traceRequest.setEventTime(now);
        TraceRequest.TargetRef batchTarget = new TraceRequest.TargetRef();
        batchTarget.setTargetType("BATCH");
        batchTarget.setTargetId(batch.getId());
        traceRequest.getTargets().add(batchTarget);
        for (Pig pig : releasedPigs) {
            TraceRequest.TargetRef pigTarget = new TraceRequest.TargetRef();
            pigTarget.setTargetType("PIG");
            pigTarget.setTargetId(pig.getId());
            traceRequest.getTargets().add(pigTarget);
        }
        TraceRecord trace = traceService.createTrace(traceRequest);

        ShipmentOrder order = new ShipmentOrder();
        order.setRequestNo(normalized.getRequestNo());
        order.setBatchId(batch.getId());
        order.setReleaseMode(normalized.getReleaseMode());
        order.setShipDate(normalized.getShipDate());
        order.setReleasedCount(count);
        order.setReleasedPigIds(writeIds(releasedPigs));
        order.setOperator(normalized.getOperator());
        order.setBuyer(normalized.getBuyer());
        order.setTraceNo(trace.getTraceNo());
        order.setCreatedAt(now);
        shipmentRepository.save(order);

        review.setSelected(Boolean.TRUE);
        review.setReleasedCount(count);
        review.setBatchSoldCount(batch.getSoldCount());
        review.setBatchIntakeCount(batch.getIntakeCount());
        review.setTraceNo(trace.getTraceNo());
        review.setRequestNo(order.getRequestNo());
        review.setMessage("已放行 " + count + " 头；批次累计出栏 " + batch.getSoldCount() + " 头");
        return review;
    }

    private ShipmentReviewVO buildReview(Batch batch, ShipmentRequest request, List<Pig> allPigs,
                                         ShipmentOrder replayOrder) {
        LocalDate shipDate = request.getShipDate() == null ? LocalDate.now() : request.getShipDate();
        Map<Long, Pen> pens = new LinkedHashMap<>();
        for (Pen pen : penRepository.findAllByOrderByPenCodeAsc()) {
            pens.put(pen.getId(), pen);
        }
        Map<Long, WithdrawalVO> withdrawalMap = withdrawalService.mapForPigs(allPigs);

        List<Pig> activePigs = new ArrayList<>();
        List<ShipmentPigVO> skipped = new ArrayList<>();
        for (Pig pig : WithdrawalService.stableEligibleOrder(allPigs)) {
            if (Dict.isTerminalPigStatus(pig.getStatus())) {
                skipped.add(pigVO(pig, withdrawalMap.get(pig.getId()), pens, "状态为" + Dict.pigStatusName(pig.getStatus()) + "，已跳过", false));
            } else if ("ON_FARM".equals(pig.getStatus()) || "TRANSFERRED".equals(pig.getStatus())) {
                activePigs.add(pig);
            } else {
                skipped.add(pigVO(pig, withdrawalMap.get(pig.getId()), pens, "未知在群状态，已跳过", false));
            }
        }

        List<ShipmentPigVO> eligible = new ArrayList<>();
        List<ShipmentPigVO> blocked = new ArrayList<>();
        for (Pig pig : activePigs) {
            WithdrawalVO withdrawal = withdrawalMap.get(pig.getId());
            boolean restricted = withdrawal != null && Boolean.TRUE.equals(withdrawal.getRestricted());
            ShipmentPigVO item = pigVO(pig, withdrawal, pens,
                    restricted ? "休药期至 " + withdrawal.getDeadline() + "，" + withdrawal.getEarliestShipDate() + " 起方可出栏" : "休药期合格",
                    false);
            if (restricted) {
                blocked.add(item);
            } else {
                eligible.add(item);
            }
        }

        ShipmentReviewVO vo = new ShipmentReviewVO();
        vo.setBatchId(batch.getId());
        vo.setBatchNo(batch.getBatchNo());
        vo.setReleaseMode(request.getReleaseMode());
        vo.setReleaseModeName(ALL_OR_NONE.equals(request.getReleaseMode()) ? "整批拦截更易核对" : "合格猪只先出栏");
        vo.setShipDate(shipDate.toString());
        vo.setEligibleCount(eligible.size());
        vo.setBlockedCount(blocked.size());
        vo.setSkippedCount(skipped.size());
        vo.setBatchSoldCount(batch.getSoldCount());
        vo.setBatchIntakeCount(batch.getIntakeCount());
        vo.getEligiblePigs().addAll(eligible);
        vo.getBlockedPigs().addAll(blocked);
        vo.getSkippedPigs().addAll(skipped);

        List<ShipmentPigVO> chosen = choose(request, eligible, blocked, skipped, vo);
        vo.getReleasedPigs().addAll(chosen);
        vo.setReleasedCount(chosen.size());
        vo.setCanSubmit(Boolean.FALSE);

        if (!"SELLING".equals(batch.getStatus())) {
            vo.setMessage("批次当前为「" + Dict.batchStatusName(batch.getStatus()) + "」，不能出栏");
        } else if (shipDate.isAfter(LocalDate.now())) {
            vo.setMessage("出栏日期不能晚于今天");
        } else if (ALL_OR_NONE.equals(request.getReleaseMode())) {
            if (!blocked.isEmpty()) {
                vo.setMessage("已选择「整批拦截」：当前仍有 " + blocked.size() + " 头处于休药期，整批暂不放行");
            } else if (eligible.isEmpty()) {
                vo.setMessage("整批暂无可放行猪只");
            } else if (!skipped.isEmpty() && explicitPigIds(request).isEmpty()) {
                vo.setMessage("整批可放行 " + eligible.size() + " 头；另有 " + skipped.size()
                        + " 头状态异常将跳过并保留原状态");
                vo.setCanSubmit(Boolean.TRUE);
            } else {
                vo.setMessage("整批 " + eligible.size() + " 头均通过休药期校验，可以放行");
                vo.setCanSubmit(Boolean.TRUE);
            }
        } else {
            if (chosen.isEmpty()) {
                vo.setMessage(blocked.isEmpty() ? "当前没有可放行猪只"
                        : "本次未选中合格猪只；仍有 " + blocked.size() + " 头被休药期拦下");
            } else {
                vo.setMessage("将放行 " + chosen.size() + " 头合格猪只，拦下 " + blocked.size()
                        + " 头，跳过 " + skipped.size() + " 头状态异常猪只");
                vo.setCanSubmit(Boolean.TRUE);
            }
        }

        if (nvl(batch.getSoldCount()) + chosen.size() > nvl(batch.getIntakeCount())) {
            vo.setCanSubmit(Boolean.FALSE);
            vo.setMessage("放行后累计出栏 " + (nvl(batch.getSoldCount()) + chosen.size())
                    + " 头，将超过入栏 " + nvl(batch.getIntakeCount()) + " 头");
        }
        if (replayOrder != null) {
            vo.setRequestNo(replayOrder.getRequestNo());
            vo.setTraceNo(replayOrder.getTraceNo());
        }
        return vo;
    }

    private List<ShipmentPigVO> choose(ShipmentRequest request, List<ShipmentPigVO> eligible,
                                       List<ShipmentPigVO> blocked, List<ShipmentPigVO> skipped,
                                       ShipmentReviewVO vo) {
        List<Long> explicitIds = explicitPigIds(request);
        if (!explicitIds.isEmpty()) {
            Map<Long, ShipmentPigVO> eligibleMap = eligible.stream()
                    .collect(Collectors.toMap(ShipmentPigVO::getId, item -> item));
            Map<Long, ShipmentPigVO> blockedMap = blocked.stream()
                    .collect(Collectors.toMap(ShipmentPigVO::getId, item -> item));
            Map<Long, ShipmentPigVO> skippedMap = skipped.stream()
                    .collect(Collectors.toMap(ShipmentPigVO::getId, item -> item));
            List<ShipmentPigVO> chosen = new ArrayList<>();
            for (Long id : explicitIds.stream().sorted().toList()) {
                ShipmentPigVO item = eligibleMap.get(id);
                if (item != null) {
                    chosen.add(item);
                } else if (blockedMap.containsKey(id)) {
                    vo.setMessage("选中的 " + blockedMap.get(id).getEarTag() + " 仍在休药期，不能放行");
                    return List.of();
                } else if (skippedMap.containsKey(id)) {
                    vo.setMessage("选中的 " + skippedMap.get(id).getEarTag() + " 状态异常，不能出栏");
                    return List.of();
                } else {
                    vo.setMessage("选中的猪只 #" + id + " 不属于本批或不存在");
                    return List.of();
                }
            }
            if (ALL_OR_NONE.equals(request.getReleaseMode()) && chosen.size() != eligible.size()) {
                vo.setMessage("整批拦截策略要求本批合格猪只全部放行；当前合格 "
                        + eligible.size() + " 头，仅选择 " + chosen.size() + " 头");
                return List.of();
            }
            vo.setRequestedCount(chosen.size());
            vo.setSelectionRule("按操作员勾选的 " + chosen.size() + " 头猪只放行，不做自动补选");
            return chosen;
        }

        int requested = request.getCount() == null ? eligible.size() : request.getCount();
        vo.setRequestedCount(requested);
        if (requested < 0) {
            vo.setMessage("出栏头数不能为负数");
            return List.of();
        }
        if (PARTIAL.equals(request.getReleaseMode()) && requested == 0) {
            vo.setSelectionRule("头数为 0：仅预检，不自动选择猪只");
            return List.of();
        }
        if (requested > eligible.size()) {
            vo.setMessage("合格且可出栏猪只有 " + eligible.size() + " 头，不足以按 " + requested + " 头自动选择");
            return List.of();
        }

        List<ShipmentPigVO> chosen = new ArrayList<>();
        int limit = ALL_OR_NONE.equals(request.getReleaseMode()) ? eligible.size() : requested;
        for (ShipmentPigVO item : eligible) {
            if (chosen.size() >= limit) {
                break;
            }
            item.setAutoSelected(Boolean.TRUE);
            chosen.add(item);
        }
        vo.setSelectionRule("未指定具体猪只：按猪只 id（等同于台账固定顺序）升序，从前向后选 "
                + chosen.size() + " 头合格猪，结果不受并发刷新顺序影响");
        return chosen;
    }

    private ShipmentPigVO pigVO(Pig pig, WithdrawalVO withdrawal, Map<Long, Pen> pens, String reason,
                                boolean autoSelected) {
        ShipmentPigVO vo = new ShipmentPigVO();
        vo.setId(pig.getId());
        vo.setEarTag(pig.getEarTag());
        vo.setStatus(pig.getStatus());
        vo.setStatusName(Dict.pigStatusName(pig.getStatus()));
        Pen pen = pig.getPenId() == null ? null : pens.get(pig.getPenId());
        vo.setPenCode(pen == null ? "" : pen.getPenCode());
        if (withdrawal != null) {
            vo.setDeadline(withdrawal.getDeadline());
            vo.setEarliestShipDate(withdrawal.getEarliestShipDate());
        }
        vo.setReason(reason);
        vo.setAutoSelected(autoSelected);
        return vo;
    }

    private ShipmentRequest normalize(ShipmentRequest request) {
        ShipmentRequest normalized = request == null ? new ShipmentRequest() : request;
        if (!ALL_OR_NONE.equals(normalized.getReleaseMode()) && !PARTIAL.equals(normalized.getReleaseMode())) {
            throw new BizException("请明确选择放行策略：整批拦截或合格先出");
        }
        if (normalized.getShipDate() == null) {
            normalized.setShipDate(LocalDate.now());
        }
        if (normalized.getShipDate().isAfter(LocalDate.now())) {
            throw new BizException("出栏日期不能晚于今天");
        }
        if (!StringUtils.hasText(normalized.getOperator())) {
            normalized.setOperator("场长");
        }
        if (normalized.getPigIds() != null) {
            normalized.setPigIds(normalized.getPigIds().stream().distinct().sorted().toList());
        }
        return normalized;
    }

    private ShipmentReviewVO replayVO(ShipmentOrder order) {
        Batch batch = batchRepository.findById(order.getBatchId())
                .orElseThrow(() -> new BizException("出栏批次不存在：" + order.getBatchId()));
        ShipmentRequest request = new ShipmentRequest();
        request.setReleaseMode(order.getReleaseMode());
        request.setShipDate(order.getShipDate());
        request.setOperator(order.getOperator());
        request.setBuyer(order.getBuyer());
        request.setRequestNo(order.getRequestNo());
        List<Long> ids = readIds(order.getReleasedPigIds());
        List<Pig> allPigs = pigRepository.findByBatchIdOrderByIdAsc(batch.getId());
        List<Pig> extra = pigRepository.findAllById(ids).stream()
                .filter(pig -> !allPigs.stream().anyMatch(item -> item.getId().equals(pig.getId())))
                .toList();
        List<Pig> combined = new ArrayList<>(allPigs);
        combined.addAll(extra);
        ShipmentReviewVO vo = buildReview(batch, request, combined, order);
        vo.setSelected(Boolean.TRUE);
        vo.setReleasedCount(order.getReleasedCount());
        vo.setBatchSoldCount(batch.getSoldCount());
        vo.setBatchIntakeCount(batch.getIntakeCount());
        vo.setTraceNo(order.getTraceNo());
        vo.setMessage("该出栏请求已提交，本次为幂等重试，未重复扣减");
        // 已售猪现在是终态，普通预检会归入异常；重放时从历史 id 单独还原到“已放行”。
        int replayReleased = 0;
        vo.getSkippedPigs().removeIf(item -> ids.contains(item.getId()));
        vo.getReleasedPigs().clear();
        Map<Long, Pig> pigMap = WithdrawalService.byId(combined);
        for (Long id : ids) {
            Pig pig = pigMap.get(id);
            if (pig != null) {
                ShipmentPigVO item = new ShipmentPigVO();
                item.setId(pig.getId());
                item.setEarTag(pig.getEarTag());
                item.setStatus(pig.getStatus());
                item.setStatusName(Dict.pigStatusName(pig.getStatus()));
                item.setReason("首次请求已放行");
                vo.getReleasedPigs().add(item);
                replayReleased++;
            }
        }
        vo.setReleasedCount(order.getReleasedCount());
        vo.setEligibleCount(0);
        vo.setBlockedCount(0);
        vo.setSkippedCount(vo.getSkippedPigs().size());
        if (replayReleased != order.getReleasedCount()) {
            throw new BizException("出栏单历史猪只清单不完整，拒绝返回不一致的重放结果");
        }
        return vo;
    }

    private String buildTraceDescription(ShipmentRequest request, ShipmentReviewVO review) {
        String mode = ALL_OR_NONE.equals(request.getReleaseMode()) ? "整批拦截策略" : "合格先出策略";
        return mode + "，出栏日 " + review.getShipDate() + "，实际放行 " + review.getReleasedCount()
                + " 头；休药期拦下 " + review.getBlockedCount() + " 头，状态异常跳过 "
                + review.getSkippedCount() + " 头";
    }

    private String writeIds(List<Pig> pigs) {
        try {
            return objectMapper.writeValueAsString(pigs.stream().map(Pig::getId).toList());
        } catch (JsonProcessingException e) {
            throw new BizException("出栏猪只清单序列化失败");
        }
    }

    private List<Long> readIds(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, new com.fasterxml.jackson.core.type.TypeReference<List<Long>>() {});
        } catch (JsonProcessingException e) {
            throw new BizException("出栏猪只清单无法解析");
        }
    }

    private List<Long> explicitPigIds(ShipmentRequest request) {
        return request.getPigIds() == null ? List.of() : request.getPigIds().stream().distinct().sorted().toList();
    }

    private String newRequestNo() {
        return "SHIP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24).toUpperCase();
    }

    private static int nvl(Integer value) {
        return value == null ? 0 : value;
    }
}
