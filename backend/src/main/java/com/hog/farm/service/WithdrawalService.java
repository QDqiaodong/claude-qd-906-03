package com.hog.farm.service;

import com.hog.farm.domain.MedicationRecord;
import com.hog.farm.domain.Pig;
import com.hog.farm.dto.Views.MedicationVO;
import com.hog.farm.dto.Views.WithdrawalVO;
import com.hog.farm.repository.MedicationRecordRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 休药期计算：只承认未撤销的用药记录；当前限制和最晚可出栏日期均从记录动态得到，
 * 因此补登、纠错或撤销后无需额外冗余字段即可同步重算。
 */
@Service
public class WithdrawalService {

    private final MedicationRecordRepository medicationRepository;

    public WithdrawalService(MedicationRecordRepository medicationRepository) {
        this.medicationRepository = medicationRepository;
    }

    @Transactional(readOnly = true)
    public Map<Long, WithdrawalVO> mapForPigs(List<Pig> pigs) {
        List<Long> ids = pigs.stream().map(Pig::getId).toList();
        Map<Long, WithdrawalVO> result = new HashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        List<MedicationRecord> records = medicationRepository.findByPigIdInAndRevokedFalseOrderByMedicationDateDescIdDesc(ids);
        Map<Long, List<MedicationRecord>> byPig = new HashMap<>();
        for (MedicationRecord record : records) {
            byPig.computeIfAbsent(record.getPigId(), key -> new ArrayList<>()).add(record);
        }
        for (Pig pig : pigs) {
            result.put(pig.getId(), build(byPig.getOrDefault(pig.getId(), List.of()), LocalDate.now()));
        }
        return result;
    }

    public WithdrawalVO forPig(Long pigId) {
        return build(medicationRepository.findByPigIdAndRevokedFalseOrderByMedicationDateDescIdDesc(pigId),
                LocalDate.now());
    }

    public WithdrawalVO build(List<MedicationRecord> records, LocalDate shipDate) {
        WithdrawalVO vo = new WithdrawalVO();
        vo.setActiveMedicationCount(records.size());
        MedicationRecord latest = null;
        LocalDate latestDeadline = null;
        for (MedicationRecord record : records) {
            LocalDate deadline = deadline(record);
            if (latestDeadline == null || deadline.isAfter(latestDeadline)) {
                latestDeadline = deadline;
                latest = record;
            }
        }
        if (latest != null) {
            vo.setLatestMedicationDate(latest.getMedicationDate().toString());
            vo.setLatestMedicineName(latest.getMedicineName());
            vo.setDeadline(latestDeadline.toString());
            vo.setEarliestShipDate(latestDeadline.plusDays(1).toString());
            vo.setRestricted(!shipDate.isAfter(latestDeadline));
            vo.setRemainingDays(Math.max(0, ChronoUnit.DAYS.between(shipDate, latestDeadline) + 1));
        }
        return vo;
    }

    public MedicationVO toMedicationVO(MedicationRecord record) {
        MedicationVO vo = new MedicationVO();
        vo.setId(record.getId());
        vo.setRequestNo(record.getRequestNo());
        vo.setPigId(record.getPigId());
        vo.setEarTag(record.getEarTag());
        vo.setMedicineName(record.getMedicineName());
        vo.setDose(record.getDose());
        vo.setMedicationDate(record.getMedicationDate() == null ? null : record.getMedicationDate().toString());
        vo.setWithdrawalDays(record.getWithdrawalDays());
        LocalDate deadline = deadline(record);
        vo.setDeadline(deadline.toString());
        vo.setEarliestShipDate(deadline.plusDays(1).toString());
        vo.setOperator(record.getOperator());
        vo.setRemark(record.getRemark());
        vo.setCreatedAt(formatTime(record.getCreatedAt()));
        vo.setSupersedesId(record.getSupersedesId());
        vo.setRevoked(Boolean.TRUE.equals(record.getRevoked()));
        vo.setRevokedAt(formatTime(record.getRevokedAt()));
        vo.setRevokeReason(record.getRevokeReason());
        vo.setTraceNo(record.getTraceNo());
        vo.setActive(!Boolean.TRUE.equals(record.getRevoked()));
        vo.setRestrictedNow(Boolean.valueOf(!Boolean.TRUE.equals(record.getRevoked())
                && !LocalDate.now().isAfter(deadline)));
        return vo;
    }

    public static LocalDate deadline(MedicationRecord record) {
        return record.getMedicationDate().plusDays(record.getWithdrawalDays() == null ? 0 : record.getWithdrawalDays());
    }

    public static List<Pig> stableEligibleOrder(List<Pig> pigs) {
        List<Pig> sorted = new ArrayList<>(pigs);
        sorted.sort(Comparator.comparing(Pig::getId));
        return sorted;
    }

    public static Map<Long, Pig> byId(List<Pig> pigs) {
        Map<Long, Pig> result = new LinkedHashMap<>();
        for (Pig pig : stableEligibleOrder(pigs)) {
            result.put(pig.getId(), pig);
        }
        return result;
    }

    private static String formatTime(LocalDateTime time) {
        return time == null ? null : time.toString();
    }
}
