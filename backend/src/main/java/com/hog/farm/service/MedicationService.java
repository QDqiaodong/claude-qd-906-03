package com.hog.farm.service;

import com.hog.farm.common.BizException;
import com.hog.farm.domain.Pig;
import com.hog.farm.domain.TraceRecord;
import com.hog.farm.domain.MedicationRecord;
import com.hog.farm.dto.MedicationRequest;
import com.hog.farm.dto.MedicationRevokeRequest;
import com.hog.farm.dto.MedicationUpdateRequest;
import com.hog.farm.dto.TraceRequest;
import com.hog.farm.dto.Views.MedicationVO;
import com.hog.farm.repository.BatchRepository;
import com.hog.farm.repository.MedicationRecordRepository;
import com.hog.farm.repository.PigRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 用药登记、补登、纠错与撤销。用药记录、猪只批次和溯源事件在同一事务内落库。
 */
@Service
public class MedicationService {

    private static final int MAX_WITHDRAWAL_DAYS = 365;

    private final MedicationRecordRepository medicationRepository;
    private final PigRepository pigRepository;
    private final BatchRepository batchRepository;
    private final TraceService traceService;
    private final WithdrawalService withdrawalService;

    public MedicationService(MedicationRecordRepository medicationRepository, PigRepository pigRepository,
                             BatchRepository batchRepository, TraceService traceService,
                             WithdrawalService withdrawalService) {
        this.medicationRepository = medicationRepository;
        this.pigRepository = pigRepository;
        this.batchRepository = batchRepository;
        this.traceService = traceService;
        this.withdrawalService = withdrawalService;
    }

    @Transactional(readOnly = true)
    public List<MedicationVO> list(Long pigId) {
        List<MedicationRecord> records = pigId == null
                ? medicationRepository.findAllByOrderByMedicationDateDescIdDesc()
                : medicationRepository.findByPigIdOrderByMedicationDateDescIdDesc(pigId);
        return records.stream().map(withdrawalService::toMedicationVO).toList();
    }

    @Transactional(readOnly = true)
    public MedicationVO get(Long id) {
        return withdrawalService.toMedicationVO(getRecord(id));
    }

    /**
     * 批量登记。先按批次加锁，再按猪只 id 固定顺序加行锁，与出栏提交保持相同锁序。
     */
    @Transactional
    public List<MedicationVO> create(MedicationRequest request) {
        validate(request);
        String requestNo = normalizeRequestNo(request.getRequestNo());
        if (medicationRepository.existsByRequestNo(requestNo)) {
            return medicationRepository.findAll().stream()
                    .filter(record -> requestNo.equals(record.getRequestNo()))
                    .sorted(Comparator.comparing(MedicationRecord::getPigId))
                    .map(withdrawalService::toMedicationVO)
                    .toList();
        }

        List<Pig> pigs = resolveAndLockPigs(request);
        List<MedicationRecord> records = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (Pig pig : pigs) {
            MedicationRecord record = new MedicationRecord();
            record.setRequestNo(requestNo);
            record.setPigId(pig.getId());
            record.setEarTag(pig.getEarTag());
            record.setMedicineName(request.getMedicineName().trim());
            record.setDose(trim(request.getDose()));
            record.setMedicationDate(request.getMedicationDate());
            record.setWithdrawalDays(request.getWithdrawalDays());
            record.setOperator(trim(request.getOperator()));
            record.setRemark(trim(request.getRemark()));
            record.setCreatedAt(now);
            record.setRevoked(Boolean.FALSE);
            records.add(medicationRepository.save(record));
        }

        LocalDate latestDeadline = records.stream().map(WithdrawalService::deadline)
                .max(LocalDate::compareTo).orElse(request.getMedicationDate());
        TraceRecord trace = createTrace("MEDICATION",
                "批量用药登记：" + request.getMedicineName().trim(),
                "对 " + records.size() + " 头猪登记用药，休药期 " + request.getWithdrawalDays()
                        + " 天；最晚休药截止日 " + latestDeadline + "，次日起方可出栏",
                request.getOperator(), pigs, pigs.stream().map(Pig::getBatchId)
                        .filter(java.util.Objects::nonNull).distinct().sorted().toList());
        for (MedicationRecord record : records) {
            record.setTraceNo(trace.getTraceNo());
        }
        medicationRepository.saveAll(records);
        return records.stream().map(withdrawalService::toMedicationVO).toList();
    }

    /** 纠错：旧记录软撤销并被一条新记录替代，审计链保留。 */
    @Transactional
    public MedicationVO update(Long id, MedicationUpdateRequest request) {
        MedicationRecord old = getRecord(id);
        if (Boolean.TRUE.equals(old.getRevoked())) {
            throw new BizException("记录已撤销，不能继续纠错");
        }
        String requestNo = normalizeRequestNo(request == null ? null : request.getRequestNo());
        MedicationRecord existing = medicationRepository.findByRequestNo(requestNo).orElse(null);
        if (existing != null) {
            return withdrawalService.toMedicationVO(existing);
        }

        validateUpdate(request);
        LocalDateTime now = LocalDateTime.now();
        MedicationRecord replacement = new MedicationRecord();
        replacement.setRequestNo(requestNo);
        replacement.setPigId(old.getPigId());
        replacement.setEarTag(old.getEarTag());
        replacement.setMedicineName(StringUtils.hasText(request.getMedicineName())
                ? request.getMedicineName().trim() : old.getMedicineName());
        replacement.setDose(request.getDose() != null ? trim(request.getDose()) : old.getDose());
        replacement.setMedicationDate(request.getMedicationDate() == null ? old.getMedicationDate() : request.getMedicationDate());
        replacement.setWithdrawalDays(request.getWithdrawalDays() == null ? old.getWithdrawalDays() : request.getWithdrawalDays());
        replacement.setOperator(request.getOperator() != null ? trim(request.getOperator()) : old.getOperator());
        replacement.setRemark(request.getRemark() != null ? trim(request.getRemark()) : old.getRemark());
        replacement.setCreatedAt(now);
        replacement.setRevoked(Boolean.FALSE);
        replacement.setSupersedesId(old.getId());
        replacement = medicationRepository.save(replacement);

        old.setRevoked(Boolean.TRUE);
        old.setRevokedAt(now);
        old.setRevokeReason("纠错：由记录 #" + replacement.getId() + " 替代");
        medicationRepository.save(old);

        Pig pig = pigRepository.findById(old.getPigId()).orElse(null);
        TraceRecord trace = createTrace("MEDICATION", "用药记录纠错：" + replacement.getMedicineName(),
                "猪只 " + old.getEarTag() + " 的用药记录 #" + old.getId() + " 已由 #" + replacement.getId()
                        + " 替代，休药截止日调整为 " + WithdrawalService.deadline(replacement),
                replacement.getOperator(), pig == null ? List.of() : List.of(pig),
                pig == null || pig.getBatchId() == null ? null : List.of(pig.getBatchId()));
        replacement.setTraceNo(trace.getTraceNo());
        return withdrawalService.toMedicationVO(medicationRepository.save(replacement));
    }

    @Transactional
    public MedicationVO revoke(Long id, MedicationRevokeRequest request) {
        MedicationRecord record = getRecord(id);
        if (Boolean.TRUE.equals(record.getRevoked())) {
            return withdrawalService.toMedicationVO(record);
        }
        record.setRevoked(Boolean.TRUE);
        record.setRevokedAt(LocalDateTime.now());
        record.setRevokeReason(request == null ? null : trim(request.getReason()));
        medicationRepository.save(record);

        Pig pig = pigRepository.findById(record.getPigId()).orElse(null);
        createTrace("MEDICATION", "撤销用药记录：" + record.getMedicineName(),
                "猪只 " + record.getEarTag() + " 的用药记录 #" + record.getId() + " 已撤销，休药限制立即重算",
                request == null ? null : request.getOperator(), pig == null ? List.of() : List.of(pig),
                pig == null || pig.getBatchId() == null ? null : List.of(pig.getBatchId()));
        return withdrawalService.toMedicationVO(record);
    }

    private List<Pig> resolveAndLockPigs(MedicationRequest request) {
        Set<Long> ids = new LinkedHashSet<>();
        if (request.getBatchId() != null) {
            // 先锁批次，再锁猪只；与出栏事务保持相同顺序，避免并发补药与出栏互相等待。
            batchRepository.lockById(request.getBatchId())
                    .orElseThrow(() -> new BizException("出栏批次不存在：" + request.getBatchId()));
        }
        if (request.getPigIds() != null) {
            ids.addAll(request.getPigIds());
        }
        if (ids.isEmpty() && request.getBatchId() != null) {
            pigRepository.findByBatchIdOrderByIdAsc(request.getBatchId()).stream()
                    .filter(pig -> !com.hog.farm.common.Dict.isTerminalPigStatus(pig.getStatus()))
                    .map(Pig::getId).forEach(ids::add);
        }
        if (ids.isEmpty()) {
            throw new BizException("请选择需要登记用药的猪只");
        }
        List<Long> sortedIds = ids.stream().sorted().toList();
        List<Pig> pigs = pigRepository.lockByIds(sortedIds);
        if (pigs.size() != sortedIds.size()) {
            throw new BizException("存在已删除或无效的猪只，请刷新后重试");
        }
        for (Pig pig : pigs) {
            if (com.hog.farm.common.Dict.isTerminalPigStatus(pig.getStatus())) {
                throw new BizException("猪只 " + pig.getEarTag() + " 已"
                        + com.hog.farm.common.Dict.pigStatusName(pig.getStatus()) + "，不能再补记用药");
            }
            if (request.getBatchId() != null && !request.getBatchId().equals(pig.getBatchId())) {
                throw new BizException("猪只 " + pig.getEarTag() + " 不属于所选出栏批次");
            }
        }
        return pigs;
    }

    private void validate(MedicationRequest request) {
        if (request == null) {
            throw new BizException("用药登记内容不能为空");
        }
        if (!StringUtils.hasText(request.getMedicineName())) {
            throw new BizException("药品名称不能为空");
        }
        if (request.getMedicationDate() == null) {
            throw new BizException("用药日期不能为空");
        }
        if (request.getMedicationDate().isAfter(LocalDate.now())) {
            throw new BizException("用药日期不能晚于今天");
        }
        if (request.getWithdrawalDays() == null || request.getWithdrawalDays() < 0) {
            throw new BizException("休药天数必须为不小于 0 的整数");
        }
        if (request.getWithdrawalDays() > MAX_WITHDRAWAL_DAYS) {
            throw new BizException("休药天数不得超过 " + MAX_WITHDRAWAL_DAYS + " 天");
        }
        if (!StringUtils.hasText(request.getOperator())) {
            throw new BizException("经办人不能为空");
        }
        if ((request.getPigIds() == null || request.getPigIds().isEmpty()) && request.getBatchId() == null) {
            throw new BizException("请选择猪只或出栏批次");
        }
    }

    private void validateUpdate(MedicationUpdateRequest request) {
        if (request == null) {
            throw new BizException("纠错内容不能为空");
        }
        if (request.getMedicationDate() != null && request.getMedicationDate().isAfter(LocalDate.now())) {
            throw new BizException("用药日期不能晚于今天");
        }
        if (request.getWithdrawalDays() != null
                && (request.getWithdrawalDays() < 0 || request.getWithdrawalDays() > MAX_WITHDRAWAL_DAYS)) {
            throw new BizException("休药天数必须在 0 到 " + MAX_WITHDRAWAL_DAYS + " 天之间");
        }
    }

    private MedicationRecord getRecord(Long id) {
        return medicationRepository.findById(id).orElseThrow(() -> new BizException("用药记录不存在：" + id));
    }

    private TraceRecord createTrace(String type, String title, String desc, String operator,
                                    List<Pig> pigs, List<Long> batchIds) {
        TraceRequest traceRequest = new TraceRequest();
        traceRequest.setTraceType(type);
        traceRequest.setEventTitle(title);
        traceRequest.setEventDesc(desc);
        traceRequest.setOperator(operator);
        for (Pig pig : pigs) {
            TraceRequest.TargetRef ref = new TraceRequest.TargetRef();
            ref.setTargetType("PIG");
            ref.setTargetId(pig.getId());
            traceRequest.getTargets().add(ref);
        }
        if (batchIds != null) {
            for (Long batchId : batchIds) {
                TraceRequest.TargetRef ref = new TraceRequest.TargetRef();
                ref.setTargetType("BATCH");
                ref.setTargetId(batchId);
                traceRequest.getTargets().add(ref);
            }
        }
        if (traceRequest.getTargets().isEmpty() && pigs.isEmpty()) {
            // 极端历史数据缺猪时，仍保证审计事件可写。
            throw new BizException("用药记录缺少有效猪只来源，不能生成溯源事件");
        }
        return traceService.createTrace(traceRequest);
    }

    private String normalizeRequestNo(String value) {
        if (StringUtils.hasText(value)) {
            return value.trim();
        }
        return "MED-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24).toUpperCase();
    }

    private static String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
