package com.hog.farm.service;

import com.hog.farm.common.BizException;
import com.hog.farm.common.Dict;
import com.hog.farm.domain.Batch;
import com.hog.farm.domain.House;
import com.hog.farm.domain.Pig;
import com.hog.farm.dto.BatchRequest;
import com.hog.farm.dto.Views.BatchVO;
import com.hog.farm.dto.Views.WithdrawalVO;
import com.hog.farm.repository.BatchRepository;
import com.hog.farm.repository.HouseRepository;
import com.hog.farm.repository.PigRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 出栏批次：批次号唯一、入栏头数 > 0、状态顺序推进（在建 → 在养 → 出栏中 → 已清栏）、
 * 累计出栏头数不得超过入栏头数。
 */
@Service
public class BatchService {

    private final BatchRepository batchRepository;
    private final HouseRepository houseRepository;
    private final PigRepository pigRepository;
    private final WithdrawalService withdrawalService;

    public BatchService(BatchRepository batchRepository, HouseRepository houseRepository,
                        PigRepository pigRepository, WithdrawalService withdrawalService) {
        this.batchRepository = batchRepository;
        this.houseRepository = houseRepository;
        this.pigRepository = pigRepository;
        this.withdrawalService = withdrawalService;
    }

    public List<BatchVO> list() {
        List<BatchVO> result = new ArrayList<>();
        for (Batch batch : batchRepository.findAllByOrderByBatchNoAsc()) {
            result.add(toVO(batch));
        }
        return result;
    }

    public Batch get(Long id) {
        return batchRepository.findById(id).orElseThrow(() -> new BizException("出栏批次不存在：" + id));
    }

    @Transactional
    public Batch create(BatchRequest request) {
        validate(request);
        if (!StringUtils.hasText(request.getBatchNo())) {
            throw new BizException("批次号不能为空");
        }
        if (batchRepository.existsByBatchNo(request.getBatchNo())) {
            throw new BizException("批次号已存在：" + request.getBatchNo());
        }
        Batch batch = new Batch();
        apply(batch, request);
        // 默认值放在 create 里
        if (batch.getStatus() == null) {
            batch.setStatus("BUILDING");
        }
        if (batch.getSoldCount() == null) {
            batch.setSoldCount(0);
        }
        if (batch.getDeadCount() == null) {
            batch.setDeadCount(0);
        }
        if (batch.getStartDate() == null) {
            batch.setStartDate(LocalDate.now());
        }
        return batchRepository.save(batch);
    }

    @Transactional
    public Batch update(Long id, BatchRequest request) {
        Batch batch = get(id);
        if (StringUtils.hasText(request.getBatchNo())
                && !request.getBatchNo().equals(batch.getBatchNo())
                && batchRepository.existsByBatchNo(request.getBatchNo())) {
            throw new BizException("批次号已存在：" + request.getBatchNo());
        }
        if (request.getIntakeCount() != null) {
            if (request.getIntakeCount() <= 0) {
                throw new BizException("入栏头数必须大于 0");
            }
            if (request.getIntakeCount() > Dict.MAX_BATCH_INTAKE) {
                throw new BizException("单批次入栏头数不得超过 " + Dict.MAX_BATCH_INTAKE + " 头");
            }
            int sold = nvl(batch.getSoldCount());
            int dead = nvl(batch.getDeadCount());
            if (request.getIntakeCount() < sold + dead) {
                throw new BizException("入栏头数不能小于已出栏 + 死淘头数（" + (sold + dead) + " 头）");
            }
        }
        if (request.getStatus() != null && !Dict.isValidBatchStatus(request.getStatus())) {
            throw new BizException("批次状态只能为 在建 / 在养 / 出栏中 / 已清栏");
        }
        if (request.getBreed() != null && !Dict.isValidBreed(request.getBreed())) {
            throw new BizException("品种只能是 大白 / 长白 / 杜洛克 / 皮特兰 / 巴克夏");
        }
        ifNotNull(request.getBatchNo(), batch::setBatchNo);
        ifNotNull(request.getBatchName(), batch::setBatchName);
        ifNotNull(request.getHouseId(), batch::setHouseId);
        ifNotNull(request.getBreed(), batch::setBreed);
        ifNotNull(request.getIntakeCount(), batch::setIntakeCount);
        ifNotNull(request.getStartDate(), batch::setStartDate);
        ifNotNull(request.getExpectDate(), batch::setExpectDate);
        ifNotNull(request.getStatus(), batch::setStatus);
        ifNotNull(request.getBuyer(), batch::setBuyer);
        ifNotNull(request.getAvgWeightKg(), batch::setAvgWeightKg);
        ifNotNull(request.getNote(), batch::setNote);
        return batchRepository.save(batch);
    }

    /**
     * 卡片流水线：卡片底部单按钮推进状态（在建 → 在养 → 出栏中 → 已清栏）。
     * 推进到「出栏中」要求平均体重达到出栏标准；推进到「已清栏」要求已经登记过出栏。
     */
    @Transactional
    public Batch advance(Long id) {
        Batch batch = get(id);
        String next = Dict.nextBatchStatus(batch.getStatus());
        if (next == null) {
            throw new BizException("批次 " + batch.getBatchNo() + " 已是终态（已清栏），不能再推进");
        }
        if ("SELLING".equals(next)) {
            BigDecimal weight = batch.getAvgWeightKg();
            if (weight == null) {
                throw new BizException("请先维护批次平均体重，达到出栏标准后才能进入出栏中");
            }
            if (weight.compareTo(Dict.MIN_SALE_WEIGHT_KG) < 0) {
                throw new BizException("平均体重 " + weight + "kg 未达到出栏标准 "
                        + Dict.MIN_SALE_WEIGHT_KG + "kg，不能进入出栏中");
            }
        }
        if ("CLEARED".equals(next) && nvl(batch.getSoldCount()) <= 0) {
            throw new BizException("尚未登记出栏头数，不能清栏");
        }
        batch.setStatus(next);
        return batchRepository.save(batch);
    }

    /** 登记出栏：累计出栏头数不得超过入栏头数 */
    @Transactional
    public Batch ship(Long id, Integer count) {
        if (count == null || count <= 0) {
            throw new BizException("本次出栏头数必须大于 0");
        }
        Batch batch = get(id);
        if (!"SELLING".equals(batch.getStatus())) {
            throw new BizException("只有「出栏中」的批次才能登记出栏，当前状态："
                    + Dict.batchStatusName(batch.getStatus()));
        }
        int sold = nvl(batch.getSoldCount());
        int intake = nvl(batch.getIntakeCount());
        if (sold + count > intake) {
            throw new BizException("累计出栏头数不得超过入栏头数：入栏 " + intake
                    + " 头，已出栏 " + sold + " 头，本次再出 " + count + " 头将超出");
        }
        long activePigs = pigRepository.findByBatchIdOrderByIdAsc(batch.getId()).stream()
                .filter(pig -> !Dict.isTerminalPigStatus(pig.getStatus()))
                .count();
        if (activePigs > 0) {
            throw new BizException("该批次台账中仍有 " + activePigs + " 头未终态猪只，请使用猪只级出栏放行，避免数量与状态不一致");
        }
        batch.setSoldCount(sold + count);
        return batchRepository.save(batch);
    }

    @Transactional
    public void remove(Long id) {
        Batch batch = get(id);
        if (nvl(batch.getSoldCount()) > 0) {
            throw new BizException("批次已登记出栏头数，不能删除");
        }
        batchRepository.delete(batch);
    }

    private void validate(BatchRequest request) {
        if (!StringUtils.hasText(request.getBatchNo())) {
            throw new BizException("批次号不能为空");
        }
        if (request.getIntakeCount() == null || request.getIntakeCount() <= 0) {
            throw new BizException("入栏头数必须大于 0");
        }
        if (request.getIntakeCount() > Dict.MAX_BATCH_INTAKE) {
            throw new BizException("单批次入栏头数不得超过 " + Dict.MAX_BATCH_INTAKE + " 头");
        }
        if (!Dict.isValidBreed(request.getBreed())) {
            throw new BizException("品种只能是 大白 / 长白 / 杜洛克 / 皮特兰 / 巴克夏");
        }
        if (request.getStatus() != null && !Dict.isValidBatchStatus(request.getStatus())) {
            throw new BizException("批次状态只能为 在建 / 在养 / 出栏中 / 已清栏");
        }
        if (request.getHouseId() != null && !houseRepository.existsById(request.getHouseId())) {
            throw new BizException("栋舍不存在：" + request.getHouseId());
        }
    }

    private void apply(Batch batch, BatchRequest request) {
        batch.setBatchNo(request.getBatchNo());
        batch.setBatchName(request.getBatchName());
        batch.setHouseId(request.getHouseId());
        batch.setBreed(request.getBreed());
        batch.setIntakeCount(request.getIntakeCount());
        batch.setStartDate(request.getStartDate());
        batch.setExpectDate(request.getExpectDate());
        batch.setStatus(request.getStatus());
        batch.setBuyer(request.getBuyer());
        batch.setAvgWeightKg(request.getAvgWeightKg());
        batch.setNote(request.getNote());
    }

    public BatchVO toVO(Batch batch) {
        BatchVO vo = new BatchVO();
        vo.setId(batch.getId());
        vo.setBatchNo(batch.getBatchNo());
        vo.setBatchName(batch.getBatchName());
        vo.setHouseId(batch.getHouseId());
        House house = batch.getHouseId() == null ? null : houseRepository.findById(batch.getHouseId()).orElse(null);
        vo.setHouseCode(house == null ? "" : house.getHouseCode());
        vo.setBreed(batch.getBreed());
        vo.setBreedName(Dict.breedName(batch.getBreed()));
        vo.setIntakeCount(nvl(batch.getIntakeCount()));
        vo.setSoldCount(nvl(batch.getSoldCount()));
        vo.setDeadCount(nvl(batch.getDeadCount()));
        vo.setRemainCount(batch.getRemainCount());
        vo.setSoldRate(batch.getSoldRate());
        vo.setStartDate(batch.getStartDate() == null ? null : batch.getStartDate().toString());
        vo.setExpectDate(batch.getExpectDate() == null ? null : batch.getExpectDate().toString());
        vo.setStatus(batch.getStatus());
        vo.setStatusName(Dict.batchStatusName(batch.getStatus()));
        vo.setStatusColor(Dict.batchStatusColor(batch.getStatus()));
        String next = Dict.nextBatchStatus(batch.getStatus());
        vo.setNextStatus(next);
        vo.setNextStatusName(next == null ? "" : Dict.batchStatusName(next));
        vo.setBuyer(batch.getBuyer());
        vo.setAvgWeightKg(batch.getAvgWeightKg());
        vo.setNote(batch.getNote());
        List<Pig> batchPigs = pigRepository.findByBatchIdOrderByIdAsc(batch.getId());
        List<Pig> activePigs = batchPigs.stream()
                .filter(pig -> !Dict.isTerminalPigStatus(pig.getStatus()))
                .toList();
        vo.setActivePigCount(activePigs.size());
        Map<Long, WithdrawalVO> withdrawals = withdrawalService.mapForPigs(activePigs);
        int restricted = 0;
        String latestDeadline = null;
        for (WithdrawalVO withdrawal : withdrawals.values()) {
            if (Boolean.TRUE.equals(withdrawal.getRestricted())) {
                restricted++;
                if (withdrawal.getDeadline() != null
                        && (latestDeadline == null || withdrawal.getDeadline().compareTo(latestDeadline) > 0)) {
                    latestDeadline = withdrawal.getDeadline();
                }
            }
        }
        vo.setRestrictedPigCount(restricted);
        vo.setLatestWithdrawalDeadline(latestDeadline);
        vo.setEarliestShipDate(latestDeadline == null ? null
                : LocalDate.parse(latestDeadline).plusDays(1).toString());
        return vo;
    }

    private static int nvl(Integer value) {
        return value == null ? 0 : value;
    }

    private static <T> void ifNotNull(T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }
}
