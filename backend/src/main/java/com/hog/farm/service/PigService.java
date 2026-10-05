package com.hog.farm.service;

import com.hog.farm.common.BizException;
import com.hog.farm.common.Dict;
import com.hog.farm.domain.Batch;
import com.hog.farm.domain.House;
import com.hog.farm.domain.Pen;
import com.hog.farm.domain.Pig;
import com.hog.farm.dto.PigRequest;
import com.hog.farm.dto.Views.PigVO;
import com.hog.farm.repository.BatchRepository;
import com.hog.farm.repository.HouseRepository;
import com.hog.farm.repository.PenRepository;
import com.hog.farm.repository.PigRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 猪只个体与耳标：耳标号唯一、品种 5 选 1、出生日期不晚于今天、
 * 状态流转（在场 → 转栏 → 出栏 / 死亡 / 淘汰，后三者终态不可再转栏）。
 */
@Service
public class PigService {

    /** 允许的状态流转：终态（出栏 / 死亡 / 淘汰）不能再变更 */
    private static final Map<String, List<String>> ALLOWED_FLOW = Map.of(
            "ON_FARM", List.of("TRANSFERRED", "SOLD", "DEAD", "CULLED"),
            "TRANSFERRED", List.of("SOLD", "DEAD", "CULLED"));

    private final PigRepository pigRepository;
    private final PenRepository penRepository;
    private final HouseRepository houseRepository;
    private final BatchRepository batchRepository;
    private final HouseService houseService;
    private final WithdrawalService withdrawalService;
    private final ImmuneService immuneService;

    public PigService(PigRepository pigRepository, PenRepository penRepository, HouseRepository houseRepository,
                      BatchRepository batchRepository, HouseService houseService,
                      WithdrawalService withdrawalService, ImmuneService immuneService) {
        this.pigRepository = pigRepository;
        this.penRepository = penRepository;
        this.houseRepository = houseRepository;
        this.batchRepository = batchRepository;
        this.houseService = houseService;
        this.withdrawalService = withdrawalService;
        this.immuneService = immuneService;
    }

    public List<PigVO> list(String status, String breed, Long penId, String keyword) {
        List<Pig> pigs = pigRepository.findAllByOrderByEarTagAsc();
        List<PigVO> result = new ArrayList<>();
        for (Pig pig : pigs) {
            if (status != null && !status.equals(pig.getStatus())) {
                continue;
            }
            if (breed != null && !breed.equals(pig.getBreed())) {
                continue;
            }
            if (penId != null && !penId.equals(pig.getPenId())) {
                continue;
            }
            if (StringUtils.hasText(keyword) && !pig.getEarTag().contains(keyword.trim())) {
                continue;
            }
            result.add(toVO(pig));
        }
        return result;
    }

    /** 点击栏位格子时列出该栏存栏 */
    public List<PigVO> listByPen(Long penId) {
        List<PigVO> result = new ArrayList<>();
        for (Pig pig : pigRepository.findByPenIdOrderByEarTagAsc(penId)) {
            result.add(toVO(pig));
        }
        return result;
    }

    public Pig get(Long id) {
        return pigRepository.findById(id).orElseThrow(() -> new BizException("猪只不存在：" + id));
    }

    @Transactional
    public Pig create(PigRequest request) {
        validate(request);
        if (!StringUtils.hasText(request.getEarTag())) {
            throw new BizException("耳标号不能为空");
        }
        if (pigRepository.existsByEarTag(request.getEarTag())) {
            throw new BizException("耳标号已存在：" + request.getEarTag());
        }
        Pig pig = new Pig();
        apply(pig, request);
        // 默认值放在 create 里，实体字段不带默认值
        if (pig.getStatus() == null) {
            pig.setStatus("ON_FARM");
        }
        if (pig.getStatusDate() == null) {
            pig.setStatusDate(LocalDate.now());
        }
        if (pig.getSourceType() == null) {
            pig.setSourceType("SELF");
        }
        if (pig.getGender() == null) {
            pig.setGender("FEMALE");
        }
        if (pig.getPenId() != null) {
            Pen pen = penRepository.findById(pig.getPenId()).orElseThrow(() -> new BizException("栏位不存在"));
            houseService.assertCanReceive(pen.getId(), 1);
            pig.setHouseId(pen.getHouseId());
        } else {
            // 只登记栋舍（无栏位）同样受栋舍状态约束：检修 / 待清空 / 停用一律拒绝新增
            houseService.assertHouseCanReceive(request.getHouseId());
            pig.setHouseId(request.getHouseId());
        }
        Pig saved = pigRepository.save(pig);
        if (saved.getPenId() != null) {
            houseService.changePenPigCount(saved.getPenId(), 1);
        }
        return saved;
    }

    @Transactional
    public Pig update(Long id, PigRequest request) {
        Pig pig = get(id);
        if (StringUtils.hasText(request.getEarTag())
                && !request.getEarTag().equals(pig.getEarTag())
                && pigRepository.existsByEarTag(request.getEarTag())) {
            throw new BizException("耳标号已存在：" + request.getEarTag());
        }
        if (request.getBreed() != null && !Dict.isValidBreed(request.getBreed())) {
            throw new BizException("品种只能是 大白 / 长白 / 杜洛克 / 皮特兰 / 巴克夏");
        }
        if (request.getBirthDate() != null && request.getBirthDate().isAfter(LocalDate.now())) {
            throw new BizException("出生日期不能晚于今天");
        }
        if (request.getStatus() != null && !Dict.isValidPigStatus(request.getStatus())) {
            throw new BizException("猪只状态非法：" + request.getStatus());
        }
        if (request.getGender() != null && !Dict.isValidGender(request.getGender())) {
            throw new BizException("性别只能是 公 / 母");
        }
        if (request.getSourceType() != null && !Dict.isValidPigSource(request.getSourceType())) {
            throw new BizException("来源只能是 自繁 / 外购");
        }
        Long oldPenId = pig.getPenId();
        ifNotNull(request.getEarTag(), pig::setEarTag);
        ifNotNull(request.getBreed(), pig::setBreed);
        ifNotNull(request.getGender(), pig::setGender);
        ifNotNull(request.getBirthDate(), pig::setBirthDate);
        ifNotNull(request.getSourceType(), pig::setSourceType);
        ifNotNull(request.getWeightKg(), pig::setWeightKg);
        ifNotNull(request.getStatus(), pig::setStatus);
        ifNotNull(request.getNote(), pig::setNote);
        if (request.getBatchId() != null) {
            pig.setBatchId(request.getBatchId());
        }
        if (request.getPenId() != null && !request.getPenId().equals(oldPenId)) {
            Pen pen = penRepository.findById(request.getPenId())
                    .orElseThrow(() -> new BizException("栏位不存在：" + request.getPenId()));
            houseService.assertCanReceive(pen.getId(), 1);
            pig.setPenId(pen.getId());
            pig.setHouseId(pen.getHouseId());
        }
        Pig saved = pigRepository.save(pig);
        if (saved.getPenId() != null && !saved.getPenId().equals(oldPenId)) {
            houseService.changePenPigCount(saved.getPenId(), 1);
        }
        if (oldPenId != null && !oldPenId.equals(saved.getPenId())) {
            houseService.changePenPigCount(oldPenId, -1);
        }
        return saved;
    }

    /** 单头状态变更：终态（出栏 / 死亡 / 淘汰）不允许再变更，也不允许跳级回退 */
    @Transactional
    public Pig changeStatus(Long id, String status) {
        if (!Dict.isValidPigStatus(status)) {
            throw new BizException("猪只状态非法：" + status);
        }
        Pig pig = get(id);
        assertCanFlow(pig, status);
        pig.setStatus(status);
        pig.setStatusDate(LocalDate.now());
        Pig saved = pigRepository.save(pig);
        // 出栏 / 死亡 / 淘汰后从栏位释放
        if (Dict.isTerminalPigStatus(status) && saved.getPenId() != null) {
            houseService.changePenPigCount(saved.getPenId(), -1);
            saved.setPenId(null);
            saved = pigRepository.save(saved);
        }
        // 终态猪只的未完成免疫任务进入可追溯的终止状态（看板清扫为兜底）
        if (Dict.isTerminalPigStatus(status)) {
            immuneService.terminateForPig(saved.getId(), status);
        }
        return saved;
    }

    /** 侧滑批量操作：一次把勾选的猪只推进到同一状态 */
    @Transactional
    public int batchChangeStatus(List<Long> ids, String status) {
        if (ids == null || ids.isEmpty()) {
            throw new BizException("请先勾选猪只");
        }
        if (!Dict.isValidPigStatus(status)) {
            throw new BizException("猪只状态非法：" + status);
        }
        List<Pig> pigs = new ArrayList<>();
        for (Long id : ids) {
            pigs.add(get(id));
        }
        // 先全部校验，避免中途失败留下一半被改
        for (Pig pig : pigs) {
            assertCanFlow(pig, status);
        }
        for (Pig pig : pigs) {
            changeStatus(pig.getId(), status);
        }
        return pigs.size();
    }

    @Transactional
    public void remove(Long id) {
        Pig pig = get(id);
        if (pig.getPenId() != null) {
            houseService.changePenPigCount(pig.getPenId(), -1);
        }
        pigRepository.delete(pig);
    }

    private void assertCanFlow(Pig pig, String target) {
        if (Dict.isTerminalPigStatus(pig.getStatus())) {
            throw new BizException("猪只 " + pig.getEarTag() + " 已"
                    + Dict.pigStatusName(pig.getStatus()) + "，不允许再变更状态或转栏");
        }
        List<String> allowed = ALLOWED_FLOW.getOrDefault(pig.getStatus(), List.of());
        if (!allowed.contains(target)) {
            throw new BizException("猪只 " + pig.getEarTag() + " 当前状态为"
                    + Dict.pigStatusName(pig.getStatus()) + "，不能直接变更为" + Dict.pigStatusName(target));
        }
        if ("SOLD".equals(target)) {
            com.hog.farm.dto.Views.WithdrawalVO withdrawal = withdrawalService.forPig(pig.getId());
            if (Boolean.TRUE.equals(withdrawal.getRestricted())) {
                throw new BizException("猪只 " + pig.getEarTag() + " 仍在休药期内，"
                        + withdrawal.getEarliestShipDate() + " 起方可出栏");
            }
        }
    }

    private void validate(PigRequest request) {
        if (!StringUtils.hasText(request.getEarTag())) {
            throw new BizException("耳标号不能为空");
        }
        if (!Dict.isValidBreed(request.getBreed())) {
            throw new BizException("品种只能是 大白 / 长白 / 杜洛克 / 皮特兰 / 巴克夏");
        }
        if (request.getBirthDate() == null) {
            throw new BizException("出生日期不能为空");
        }
        if (request.getBirthDate().isAfter(LocalDate.now())) {
            throw new BizException("出生日期不能晚于今天：" + request.getBirthDate());
        }
        if (request.getStatus() != null && !Dict.isValidPigStatus(request.getStatus())) {
            throw new BizException("猪只状态非法：" + request.getStatus());
        }
        if (request.getGender() != null && !Dict.isValidGender(request.getGender())) {
            throw new BizException("性别只能是 公 / 母");
        }
        if (request.getSourceType() != null && !Dict.isValidPigSource(request.getSourceType())) {
            throw new BizException("来源只能是 自繁 / 外购");
        }
        if (request.getWeightKg() != null && request.getWeightKg().doubleValue() <= 0) {
            throw new BizException("体重必须大于 0");
        }
    }

    private void apply(Pig pig, PigRequest request) {
        pig.setEarTag(request.getEarTag());
        pig.setBreed(request.getBreed());
        pig.setGender(request.getGender());
        pig.setBirthDate(request.getBirthDate());
        pig.setSourceType(request.getSourceType());
        pig.setPenId(request.getPenId());
        pig.setHouseId(request.getHouseId());
        pig.setBatchId(request.getBatchId());
        pig.setStatus(request.getStatus());
        pig.setWeightKg(request.getWeightKg());
        pig.setStatusDate(request.getStatusDate());
        pig.setNote(request.getNote());
    }

    public PigVO toVO(Pig pig) {
        PigVO vo = new PigVO();
        vo.setId(pig.getId());
        vo.setEarTag(pig.getEarTag());
        vo.setBreed(pig.getBreed());
        vo.setBreedName(Dict.breedName(pig.getBreed()));
        vo.setBreedColor(Dict.breedColor(pig.getBreed()));
        vo.setGender(pig.getGender());
        vo.setGenderName(Dict.genderName(pig.getGender()));
        vo.setBirthDate(pig.getBirthDate() == null ? null : pig.getBirthDate().toString());
        vo.setAgeInDays(pig.getAgeInDays());
        vo.setSourceType(pig.getSourceType());
        vo.setSourceName(Dict.pigSourceName(pig.getSourceType()));
        vo.setPenId(pig.getPenId());
        Pen pen = pig.getPenId() == null ? null : penRepository.findById(pig.getPenId()).orElse(null);
        vo.setPenCode(pen == null ? "" : pen.getPenCode());
        vo.setHouseId(pig.getHouseId());
        House house = pig.getHouseId() == null ? null : houseRepository.findById(pig.getHouseId()).orElse(null);
        vo.setHouseCode(house == null ? "" : house.getHouseCode());
        vo.setBatchId(pig.getBatchId());
        Batch batch = pig.getBatchId() == null ? null : batchRepository.findById(pig.getBatchId()).orElse(null);
        vo.setBatchNo(batch == null ? "" : batch.getBatchNo());
        vo.setStatus(pig.getStatus());
        vo.setStatusName(Dict.pigStatusName(pig.getStatus()));
        vo.setWeightKg(pig.getWeightKg());
        vo.setStatusDate(pig.getStatusDate() == null ? null : pig.getStatusDate().toString());
        vo.setNote(pig.getNote());
        vo.setWithdrawal(withdrawalService.forPig(pig.getId()));
        return vo;
    }

    /** 供前端下拉用的可选猪只（耳标号 + 栏位） */
    public List<PigVO> options() {
        Map<Long, String> penCodes = new LinkedHashMap<>();
        for (Pen pen : penRepository.findAllByOrderByPenCodeAsc()) {
            penCodes.put(pen.getId(), pen.getPenCode());
        }
        List<PigVO> result = new ArrayList<>();
        for (Pig pig : pigRepository.findAllByOrderByEarTagAsc()) {
            PigVO vo = new PigVO();
            vo.setId(pig.getId());
            vo.setEarTag(pig.getEarTag());
            vo.setStatus(pig.getStatus());
            vo.setStatusName(Dict.pigStatusName(pig.getStatus()));
            vo.setPenId(pig.getPenId());
            vo.setPenCode(penCodes.getOrDefault(pig.getPenId(), ""));
            result.add(vo);
        }
        return result;
    }

    private static <T> void ifNotNull(T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }
}
