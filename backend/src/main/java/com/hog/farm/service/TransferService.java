package com.hog.farm.service;

import com.hog.farm.common.BizException;
import com.hog.farm.common.Dict;
import com.hog.farm.domain.FeedBatch;
import com.hog.farm.domain.Feeding;
import com.hog.farm.domain.House;
import com.hog.farm.domain.Pen;
import com.hog.farm.domain.Pig;
import com.hog.farm.domain.Transfer;
import com.hog.farm.dto.FeedBatchRequest;
import com.hog.farm.dto.FeedingRequest;
import com.hog.farm.dto.TransferRequest;
import com.hog.farm.dto.Views.FeedingVO;
import com.hog.farm.dto.Views.TransferVO;
import com.hog.farm.repository.FeedBatchRepository;
import com.hog.farm.repository.FeedingRepository;
import com.hog.farm.repository.HouseRepository;
import com.hog.farm.repository.PenRepository;
import com.hog.farm.repository.PigRepository;
import com.hog.farm.repository.TransferRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 转栏与饲喂：转入栏位剩余容量校验、同一猪只同日重复转栏拦截、单次饲喂量 > 0 且有上限。
 */
@Service
public class TransferService {

    private final TransferRepository transferRepository;
    private final FeedingRepository feedingRepository;
    private final FeedBatchRepository feedBatchRepository;
    private final PenRepository penRepository;
    private final HouseRepository houseRepository;
    private final PigRepository pigRepository;
    private final HouseService houseService;

    public TransferService(TransferRepository transferRepository, FeedingRepository feedingRepository,
                           FeedBatchRepository feedBatchRepository, PenRepository penRepository,
                           HouseRepository houseRepository, PigRepository pigRepository, HouseService houseService) {
        this.transferRepository = transferRepository;
        this.feedingRepository = feedingRepository;
        this.feedBatchRepository = feedBatchRepository;
        this.penRepository = penRepository;
        this.houseRepository = houseRepository;
        this.pigRepository = pigRepository;
        this.houseService = houseService;
    }

    // ---------------- 转栏 ----------------

    public List<TransferVO> listTransfers() {
        Map<Long, String> penCodes = penCodes();
        List<TransferVO> result = new ArrayList<>();
        for (Transfer transfer : transferRepository.findAllByOrderByTransferDateDescIdDesc()) {
            result.add(toTransferVO(transfer, penCodes));
        }
        return result;
    }

    /** 左右两栏搬移：从源栏位挑猪 → 目标栏位；支持一次搬多头 */
    @Transactional
    public List<TransferVO> createTransfer(TransferRequest request) {
        if (request == null || request.getToPenId() == null) {
            throw new BizException("请选择目标栏位");
        }
        List<Long> pigIds = resolvePigIds(request);
        if (pigIds.isEmpty()) {
            throw new BizException("请选择要转栏的猪只");
        }
        if (request.getFromPenId() != null && request.getFromPenId().equals(request.getToPenId())) {
            throw new BizException("源栏位与目标栏位不能相同");
        }
        Pen target = penRepository.findById(request.getToPenId())
                .orElseThrow(() -> new BizException("目标栏位不存在：" + request.getToPenId()));
        LocalDate date = request.getTransferDate() == null ? LocalDate.now() : request.getTransferDate();
        if (request.getReason() != null && !Dict.isValidTransferReason(request.getReason())) {
            throw new BizException("转栏原因非法：" + request.getReason());
        }
        // 目标栏位剩余容量 + 栋舍是否停用
        houseService.assertCanReceive(target.getId(), pigIds.size());

        List<Pig> pigs = new ArrayList<>();
        for (Long pigId : pigIds) {
            Pig pig = pigRepository.findById(pigId).orElseThrow(() -> new BizException("猪只不存在：" + pigId));
            if (Dict.isTerminalPigStatus(pig.getStatus())) {
                throw new BizException("猪只 " + pig.getEarTag() + " 已"
                        + Dict.pigStatusName(pig.getStatus()) + "，不允许再转栏");
            }
            if (transferRepository.existsByPigIdAndTransferDate(pigId, date)) {
                throw new BizException("猪只 " + pig.getEarTag() + " 在 " + date + " 已转栏一次，同日不允许重复转栏");
            }
            if (request.getFromPenId() != null && pig.getPenId() != null
                    && !request.getFromPenId().equals(pig.getPenId())) {
                throw new BizException("猪只 " + pig.getEarTag() + " 当前不在源栏位，请刷新后再操作");
            }
            pigs.add(pig);
        }

        // 先记下每头猪原来的栏位，后面按原栏位扣减存栏
        Map<Long, Integer> fromPenCounts = new LinkedHashMap<>();
        for (Pig pig : pigs) {
            if (pig.getPenId() != null) {
                fromPenCounts.merge(pig.getPenId(), 1, Integer::sum);
            }
        }

        List<Transfer> saved = new ArrayList<>();
        for (Pig pig : pigs) {
            Long fromPenId = pig.getPenId();
            Transfer transfer = new Transfer();
            transfer.setPigId(pig.getId());
            transfer.setEarTag(pig.getEarTag());
            transfer.setFromPenId(fromPenId);
            transfer.setToPenId(target.getId());
            transfer.setTransferDate(date);
            transfer.setReason(request.getReason());
            transfer.setOperator(request.getOperator());
            transfer.setRemark(request.getRemark());
            transfer.setCreatedAt(LocalDateTime.now());
            saved.add(transferRepository.save(transfer));

            pig.setPenId(target.getId());
            pig.setHouseId(target.getHouseId());
            if ("ON_FARM".equals(pig.getStatus())) {
                pig.setStatus("TRANSFERRED");
            }
            pig.setStatusDate(date);
            pigRepository.save(pig);
        }
        houseService.changePenPigCount(target.getId(), pigs.size());
        fromPenCounts.forEach((penId, count) -> houseService.changePenPigCount(penId, -count));

        Map<Long, String> penCodes = penCodes();
        List<TransferVO> result = new ArrayList<>();
        for (Transfer transfer : saved) {
            result.add(toTransferVO(transfer, penCodes));
        }
        return result;
    }

    // ---------------- 饲喂 ----------------

    public List<FeedingVO> listFeedings() {
        Map<Long, String> penCodes = penCodes();
        List<FeedingVO> result = new ArrayList<>();
        for (Feeding feeding : feedingRepository.findAllByOrderByFeedTimeDesc()) {
            result.add(toFeedingVO(feeding, penCodes));
        }
        return result;
    }

    @Transactional
    public FeedingVO createFeeding(FeedingRequest request) {
        if (request == null || !StringUtils.hasText(request.getFeedName())) {
            throw new BizException("饲料名称不能为空");
        }
        if (request.getAmountKg() == null || request.getAmountKg().doubleValue() <= 0) {
            throw new BizException("单次饲喂量必须大于 0");
        }
        if (request.getAmountKg().compareTo(Dict.MAX_FEED_AMOUNT_KG) > 0) {
            throw new BizException("单次饲喂量不得超过 " + Dict.MAX_FEED_AMOUNT_KG + "kg，本次为 "
                    + request.getAmountKg() + "kg");
        }
        if (request.getPenId() == null) {
            throw new BizException("请选择被饲喂的栏位");
        }
        Pen pen = penRepository.findById(request.getPenId())
                .orElseThrow(() -> new BizException("栏位不存在：" + request.getPenId()));
        if (request.getFeedType() != null && !Dict.isValidFeedType(request.getFeedType())) {
            throw new BizException("饲料类型非法：" + request.getFeedType());
        }
        FeedBatch feedBatch = null;
        if (request.getFeedBatchId() != null) {
            feedBatch = feedBatchRepository.findById(request.getFeedBatchId())
                    .orElseThrow(() -> new BizException("饲料批次不存在：" + request.getFeedBatchId()));
            if ("SCRAPPED".equals(feedBatch.getStatus())) {
                throw new BizException("饲料批次 " + feedBatch.getFeedNo() + " 已报废，不能投喂");
            }
            if (feedBatch.getExpireDate() != null && feedBatch.getExpireDate().isBefore(LocalDate.now())) {
                throw new BizException("饲料批次 " + feedBatch.getFeedNo() + " 已于 "
                        + feedBatch.getExpireDate() + " 过期，不能投喂");
            }
        }
        Feeding feeding = new Feeding();
        feeding.setFeedBatchId(feedBatch == null ? null : feedBatch.getId());
        feeding.setPenId(pen.getId());
        feeding.setFeedName(request.getFeedName());
        feeding.setFeedType(request.getFeedType());
        feeding.setAmountKg(request.getAmountKg());
        feeding.setPigCount(request.getPigCount() == null ? nvl(pen.getPigCount()) : request.getPigCount());
        feeding.setFeedTime(request.getFeedTime() == null ? LocalDateTime.now() : request.getFeedTime());
        feeding.setOperator(request.getOperator());
        feeding.setRemark(request.getRemark());
        return toFeedingVO(feedingRepository.save(feeding), penCodes());
    }

    // ---------------- 饲料批次 ----------------

    public List<FeedBatch> listFeedBatches() {
        return feedBatchRepository.findAllByOrderByFeedNoAsc();
    }

    @Transactional
    public FeedBatch createFeedBatch(FeedBatchRequest request) {
        if (request == null || !StringUtils.hasText(request.getFeedNo())) {
            throw new BizException("饲料批次号不能为空");
        }
        if (!StringUtils.hasText(request.getFeedName())) {
            throw new BizException("饲料名称不能为空");
        }
        if (!Dict.isValidFeedType(request.getFeedType())) {
            throw new BizException("饲料类型只能是 教槽料 / 保育料 / 育肥料 / 母猪料 / 公猪料");
        }
        if (feedBatchRepository.existsByFeedNo(request.getFeedNo())) {
            throw new BizException("饲料批次号已存在：" + request.getFeedNo());
        }
        if (request.getQuantityKg() != null && request.getQuantityKg().doubleValue() <= 0) {
            throw new BizException("进货数量必须大于 0");
        }
        if (request.getStatus() != null && !Dict.isValidFeedStatus(request.getStatus())) {
            throw new BizException("饲料批次状态非法：" + request.getStatus());
        }
        FeedBatch feedBatch = new FeedBatch();
        feedBatch.setFeedNo(request.getFeedNo());
        feedBatch.setFeedName(request.getFeedName());
        feedBatch.setFeedType(request.getFeedType());
        feedBatch.setSupplier(request.getSupplier());
        feedBatch.setQuantityKg(request.getQuantityKg());
        feedBatch.setProduceDate(request.getProduceDate());
        feedBatch.setExpireDate(request.getExpireDate());
        feedBatch.setStatus(request.getStatus() == null ? "NORMAL" : request.getStatus());
        feedBatch.setNote(request.getNote());
        return feedBatchRepository.save(feedBatch);
    }

    /** 左侧选源栏位时列出该栏猪只 */
    public List<Long> pigsInPen(Long penId) {
        List<Long> ids = new ArrayList<>();
        for (Pig pig : pigRepository.findByPenIdOrderByEarTagAsc(penId)) {
            ids.add(pig.getId());
        }
        return ids;
    }

    private List<Long> resolvePigIds(TransferRequest request) {
        List<Long> ids = new ArrayList<>();
        if (request.getPigIds() != null && !request.getPigIds().isEmpty()) {
            ids.addAll(request.getPigIds());
        } else if (request.getPigId() != null) {
            ids.add(request.getPigId());
        }
        return ids;
    }

    private Map<Long, String> penCodes() {
        Map<Long, String> codes = new LinkedHashMap<>();
        for (Pen pen : penRepository.findAllByOrderByPenCodeAsc()) {
            codes.put(pen.getId(), pen.getPenCode());
        }
        return codes;
    }

    private TransferVO toTransferVO(Transfer transfer, Map<Long, String> penCodes) {
        TransferVO vo = new TransferVO();
        vo.setId(transfer.getId());
        vo.setPigId(transfer.getPigId());
        vo.setEarTag(transfer.getEarTag());
        vo.setFromPenId(transfer.getFromPenId());
        vo.setFromPenCode(transfer.getFromPenId() == null ? "" : penCodes.getOrDefault(transfer.getFromPenId(), ""));
        vo.setToPenId(transfer.getToPenId());
        vo.setToPenCode(penCodes.getOrDefault(transfer.getToPenId(), ""));
        vo.setTransferDate(transfer.getTransferDate() == null ? null : transfer.getTransferDate().toString());
        vo.setReason(transfer.getReason());
        vo.setReasonName(Dict.transferReasonName(transfer.getReason()));
        vo.setOperator(transfer.getOperator());
        vo.setRemark(transfer.getRemark());
        vo.setCreatedAt(transfer.getCreatedAt() == null ? null : transfer.getCreatedAt().toString());
        return vo;
    }

    private FeedingVO toFeedingVO(Feeding feeding, Map<Long, String> penCodes) {
        FeedingVO vo = new FeedingVO();
        vo.setId(feeding.getId());
        vo.setFeedBatchId(feeding.getFeedBatchId());
        FeedBatch feedBatch = feeding.getFeedBatchId() == null ? null
                : feedBatchRepository.findById(feeding.getFeedBatchId()).orElse(null);
        vo.setFeedNo(feedBatch == null ? "" : feedBatch.getFeedNo());
        vo.setPenId(feeding.getPenId());
        vo.setPenCode(penCodes.getOrDefault(feeding.getPenId(), ""));
        vo.setFeedName(feeding.getFeedName());
        vo.setFeedType(feeding.getFeedType());
        vo.setFeedTypeName(Dict.feedTypeName(feeding.getFeedType()));
        vo.setAmountKg(feeding.getAmountKg());
        vo.setPigCount(nvl(feeding.getPigCount()));
        vo.setPerHeadKg(feeding.getPerHeadKg());
        vo.setFeedTime(feeding.getFeedTime() == null ? null : feeding.getFeedTime().toString());
        vo.setOperator(feeding.getOperator());
        vo.setRemark(feeding.getRemark());
        return vo;
    }

    private static int nvl(Integer value) {
        return value == null ? 0 : value;
    }

    /** 栋舍下拉（转栏视图顶部用） */
    public List<House> houses() {
        return houseRepository.findAllByOrderByHouseCodeAsc();
    }
}
