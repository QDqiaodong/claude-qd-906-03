package com.hog.farm.service;

import com.hog.farm.common.BizException;
import com.hog.farm.common.Dict;
import com.hog.farm.domain.House;
import com.hog.farm.domain.ImmunePlan;
import com.hog.farm.domain.ImmuneTask;
import com.hog.farm.domain.Pen;
import com.hog.farm.domain.Pig;
import com.hog.farm.domain.VaccineRecord;
import com.hog.farm.dto.ImmuneConfirmRequest;
import com.hog.farm.dto.ImmunePlanRequest;
import com.hog.farm.dto.Views.DoseSlotVO;
import com.hog.farm.dto.Views.ImmuneBoardVO;
import com.hog.farm.dto.Views.ImmuneConfirmResultVO;
import com.hog.farm.dto.Views.ImmuneDecisionVO;
import com.hog.farm.dto.Views.ImmuneGenerateVO;
import com.hog.farm.dto.Views.ImmunePlanVO;
import com.hog.farm.dto.Views.ImmuneTaskGroupVO;
import com.hog.farm.dto.Views.ImmuneTaskResultVO;
import com.hog.farm.dto.Views.ImmuneTaskVO;
import com.hog.farm.repository.HouseRepository;
import com.hog.farm.repository.ImmunePlanRepository;
import com.hog.farm.repository.ImmuneTaskRepository;
import com.hog.farm.repository.PenRepository;
import com.hog.farm.repository.PigRepository;
import com.hog.farm.repository.VaccineRecordRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 免疫计划与接种任务。
 *
 * <p>执行口径：<b>生成时名单快照</b>。生成任务时按当时在场猪只与现状把任务固化为快照行，
 * 之后转栏、日龄增长不影响已生成任务（任务挂在猪只上，转栏不丢）；
 * 猪只死亡 / 出栏 / 淘汰或出现等效接种记录时，任务进入可追溯的终止状态（保留原因与时间）；
 * 确认接种时逐头校验猪只现状，每头猪、同一计划和剂次只形成一个完成结果，
 * 局部失败互不影响，同一确认批次号重试不产生重复免疫记录。</p>
 */
@Service
public class ImmuneService {

    /** 看板分组顺序：待接种 / 临期 / 漏种 / 已补种 / 已完成 / 已终止 */
    private static final List<String[]> GROUPS = List.of(
            new String[]{"DUE", "待接种"},
            new String[]{"DUE_SOON", "临期"},
            new String[]{"MISSED", "漏种"},
            new String[]{"MADE_UP", "已补种"},
            new String[]{"DONE", "已完成"},
            new String[]{"TERMINATED", "已终止"});

    /** 执行口径说明（看板顶部展示） */
    public static final String POLICY_NOTE =
            "执行口径：生成时名单快照。任务在生成时按当时在场猪只与现状固化；转栏不丢任务（任务跟随猪只）；"
                    + "猪只死亡 / 出栏 / 淘汰或出现等效接种记录时，任务进入可追溯的终止状态；"
                    + "确认接种时逐头校验猪只现状，同一猪只、同一计划和剂次只形成一个完成结果，重复提交不产生重复免疫记录。";

    private final ImmunePlanRepository planRepository;
    private final ImmuneTaskRepository taskRepository;
    private final VaccineRecordRepository vaccineRepository;
    private final PigRepository pigRepository;
    private final PenRepository penRepository;
    private final HouseRepository houseRepository;
    private final ImmuneConfirmWorker confirmWorker;

    public ImmuneService(ImmunePlanRepository planRepository, ImmuneTaskRepository taskRepository,
                         VaccineRecordRepository vaccineRepository, PigRepository pigRepository,
                         PenRepository penRepository, HouseRepository houseRepository,
                         ImmuneConfirmWorker confirmWorker) {
        this.planRepository = planRepository;
        this.taskRepository = taskRepository;
        this.vaccineRepository = vaccineRepository;
        this.pigRepository = pigRepository;
        this.penRepository = penRepository;
        this.houseRepository = houseRepository;
        this.confirmWorker = confirmWorker;
    }

    // ---------------- 计划规则 ----------------

    @Transactional(readOnly = true)
    public List<ImmunePlanVO> listPlans() {
        List<ImmuneTask> openTasks = taskRepository.findByStatusInOrderByPlannedDateAscIdAsc(
                List.of("PENDING", "MISSED"));
        Map<Long, Long> openCount = openTasks.stream()
                .collect(Collectors.groupingBy(ImmuneTask::getPlanId, Collectors.counting()));
        Map<Long, Long> missedCount = openTasks.stream().filter(t -> "MISSED".equals(t.getStatus()))
                .collect(Collectors.groupingBy(ImmuneTask::getPlanId, Collectors.counting()));
        List<ImmunePlanVO> result = new ArrayList<>();
        for (ImmunePlan plan : planRepository.findAllByOrderByPlanCodeAsc()) {
            ImmunePlanVO vo = new ImmunePlanVO();
            vo.setId(plan.getId());
            vo.setPlanCode(plan.getPlanCode());
            vo.setVaccineName(plan.getVaccineName());
            vo.setStage(plan.getStage());
            vo.setStageName(Dict.planStageName(plan.getStage()));
            vo.setFirstDoseAgeDays(plan.getFirstDoseAgeDays());
            vo.setDoseIntervalDays(plan.getDoseIntervalDays());
            vo.setTotalDoses(plan.getTotalDoses());
            vo.setGraceDays(plan.getGraceDays());
            vo.setDoseMl(plan.getDoseMl());
            vo.setStatus(plan.getStatus());
            vo.setStatusName("ACTIVE".equals(plan.getStatus()) ? "启用" : "停用");
            vo.setNote(plan.getNote());
            vo.setOpenTaskCount(openCount.getOrDefault(plan.getId(), 0L).intValue());
            vo.setMissedTaskCount(missedCount.getOrDefault(plan.getId(), 0L).intValue());
            result.add(vo);
        }
        return result;
    }

    @Transactional
    public ImmunePlan createPlan(ImmunePlanRequest request) {
        if (request == null || !StringUtils.hasText(request.getVaccineName())) {
            throw new BizException("疫苗名称不能为空");
        }
        if (!Dict.isValidPlanStage(request.getStage())) {
            throw new BizException("适用生长阶段只能是 全阶段 / 哺乳仔猪 / 保育猪 / 育成猪 / 育肥猪 / 种用猪");
        }
        if (request.getFirstDoseAgeDays() == null || request.getFirstDoseAgeDays() < 0) {
            throw new BizException("首剂日龄不能小于 0 天");
        }
        if (request.getDoseIntervalDays() == null || request.getDoseIntervalDays() < 1) {
            throw new BizException("剂次间隔至少 1 天");
        }
        if (request.getTotalDoses() == null || request.getTotalDoses() < 1) {
            throw new BizException("总剂次至少 1 剂");
        }
        if (request.getGraceDays() == null || request.getGraceDays() < 0) {
            throw new BizException("宽限天数不能小于 0 天");
        }
        if (request.getDoseMl() == null || request.getDoseMl().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("默认剂量必须大于 0");
        }
        if (request.getDoseMl().compareTo(Dict.MAX_DOSE_ML) > 0) {
            throw new BizException("默认剂量不得超过 " + Dict.MAX_DOSE_ML + "ml");
        }
        String planCode = StringUtils.hasText(request.getPlanCode())
                ? request.getPlanCode().trim() : nextPlanCode();
        if (planRepository.existsByPlanCode(planCode)) {
            throw new BizException("计划编号已存在：" + planCode);
        }
        ImmunePlan plan = new ImmunePlan();
        plan.setPlanCode(planCode);
        plan.setVaccineName(request.getVaccineName().trim());
        plan.setStage(request.getStage());
        plan.setFirstDoseAgeDays(request.getFirstDoseAgeDays());
        plan.setDoseIntervalDays(request.getDoseIntervalDays());
        plan.setTotalDoses(request.getTotalDoses());
        plan.setGraceDays(request.getGraceDays());
        plan.setDoseMl(request.getDoseMl());
        plan.setStatus("ACTIVE");
        plan.setNote(request.getNote());
        return planRepository.save(plan);
    }

    /** 停用 / 启用计划；停用时把未完成任务的终止原因留痕（可追溯），不再生成新任务 */
    @Transactional
    public ImmunePlan changePlanStatus(Long id, String status) {
        if (!"ACTIVE".equals(status) && !"DISABLED".equals(status)) {
            throw new BizException("计划状态只能是 ACTIVE / DISABLED");
        }
        ImmunePlan plan = getPlan(id);
        plan.setStatus(status);
        if ("DISABLED".equals(status)) {
            LocalDateTime now = LocalDateTime.now();
            for (ImmuneTask task : taskRepository.findByPlanIdOrderByPlannedDateAscIdAsc(plan.getId())) {
                if ("PENDING".equals(task.getStatus()) || "MISSED".equals(task.getStatus())) {
                    task.setStatus("TERMINATED");
                    task.setTerminateReason("PLAN_DISABLED");
                    task.setTerminatedAt(now);
                }
            }
        }
        return planRepository.save(plan);
    }

    // ---------------- 生成任务（预检 / 落库共用一套判定，保证页面解释与实际生成一致） ----------------

    /** 生成预检：只解释每头猪被纳入 / 暂缓 / 不再适用的原因，不落库 */
    @Transactional(readOnly = true)
    public ImmuneGenerateVO preview(Long planId) {
        return evaluate(getPlan(planId), false);
    }

    /** 生成任务：为在场猪生成到期接种任务（快照），已存在的剂次跳过不重复 */
    @Transactional
    public ImmuneGenerateVO generate(Long planId) {
        ImmunePlan plan = getPlan(planId);
        if (!"ACTIVE".equals(plan.getStatus())) {
            throw new BizException("计划 " + plan.getPlanCode() + " 已停用，不能生成任务");
        }
        sweep();
        return evaluate(plan, true);
    }

    private ImmuneGenerateVO evaluate(ImmunePlan plan, boolean persist) {
        LocalDate today = LocalDate.now();
        LocalDate horizon = today.plusDays(Dict.IMMUNE_GENERATE_HORIZON_DAYS);
        ImmuneGenerateVO vo = new ImmuneGenerateVO();
        vo.setPlanId(plan.getId());
        vo.setPlanCode(plan.getPlanCode());
        vo.setGenerated(Boolean.valueOf(persist));
        LocalDateTime now = LocalDateTime.now();
        for (Pig pig : pigRepository.findAllByOrderByEarTagAsc()) {
            ImmuneDecisionVO decision = new ImmuneDecisionVO();
            decision.setPigId(pig.getId());
            decision.setEarTag(pig.getEarTag());
            vo.getItems().add(decision);
            // 不再适用：终态猪只
            if (Dict.isTerminalPigStatus(pig.getStatus())) {
                mark(vo, decision, "NOT_APPLICABLE",
                        "猪只已" + Dict.pigStatusName(pig.getStatus()) + "（终态），不再纳入免疫计划");
                continue;
            }
            // 不再适用：生长阶段不符
            String stage = Dict.growthStageOf(pig.getAgeInDays());
            if (!"ALL".equals(plan.getStage()) && !stage.equals(plan.getStage())) {
                mark(vo, decision, "NOT_APPLICABLE",
                        "当前为" + Dict.growthStageName(stage) + "（" + pig.getAgeInDays() + " 日龄），计划适用"
                                + Dict.planStageName(plan.getStage()));
                continue;
            }
            // 不再适用：已有等效接种记录覆盖全部剂次
            int existing = vaccineRepository
                    .findByPigIdAndVaccineNameOrderByVaccineDateAscIdAsc(pig.getId(), plan.getVaccineName())
                    .size();
            int startDose = existing + 1;
            if (startDose > plan.getTotalDoses()) {
                mark(vo, decision, "NOT_APPLICABLE",
                        "已有 " + existing + " 条该疫苗接种记录，" + plan.getTotalDoses() + " 个剂次已覆盖");
                continue;
            }
            // 计算应生成的剂次槽（生成窗口内）
            LocalDate firstDoseDate = pig.getBirthDate().plusDays(plan.getFirstDoseAgeDays());
            List<DoseSlotVO> slots = new ArrayList<>();
            for (int dose = startDose; dose <= plan.getTotalDoses(); dose++) {
                LocalDate planned = firstDoseDate.plusDays((long) (dose - 1) * plan.getDoseIntervalDays());
                if (!planned.isAfter(horizon)) {
                    DoseSlotVO slot = new DoseSlotVO();
                    slot.setDoseNo(dose);
                    slot.setPlannedDate(planned.toString());
                    slot.setExisted(Boolean.valueOf(
                            taskRepository.existsByPlanIdAndPigIdAndDoseNo(plan.getId(), pig.getId(), dose)));
                    slots.add(slot);
                }
            }
            if (slots.isEmpty()) {
                // 暂缓：最早计划日超出生成窗口
                LocalDate earliest = firstDoseDate.plusDays((long) (startDose - 1) * plan.getDoseIntervalDays());
                mark(vo, decision, "DEFERRED",
                        "第 " + startDose + " 剂计划日 " + earliest + " 超出生成窗口（今天 + "
                                + Dict.IMMUNE_GENERATE_HORIZON_DAYS + " 天），到期前重新生成即可纳入");
                continue;
            }
            // 纳入
            decision.setDecision("INCLUDED");
            decision.setDecisionName("纳入");
            decision.setDoses(slots);
            vo.setIncludedCount(vo.getIncludedCount() + 1);
            long existedCount = slots.stream().filter(DoseSlotVO::getExisted).count();
            StringBuilder reason = new StringBuilder()
                    .append(Dict.growthStageName(stage)).append("，").append(pig.getAgeInDays())
                    .append(" 日龄，纳入第 ");
            decision.setReason(reason.append(slots.stream().map(s -> String.valueOf(s.getDoseNo()))
                            .collect(Collectors.joining("、")))
                    .append(" 剂")
                    .append(existedCount > 0 ? "（其中 " + existedCount + " 剂任务已存在，跳过不重复生成）" : "")
                    .toString());
            if (persist) {
                for (DoseSlotVO slot : slots) {
                    if (Boolean.TRUE.equals(slot.getExisted())) {
                        continue;
                    }
                    ImmuneTask task = new ImmuneTask();
                    task.setPlanId(plan.getId());
                    task.setPigId(pig.getId());
                    task.setEarTag(pig.getEarTag());
                    task.setDoseNo(slot.getDoseNo());
                    task.setPlannedDate(LocalDate.parse(slot.getPlannedDate()));
                    task.setGraceDays(plan.getGraceDays());
                    task.setPenId(pig.getPenId());
                    task.setBasis(basisText(pig, plan, firstDoseDate, slot.getDoseNo()));
                    task.setStatus("PENDING");
                    task.setCreatedAt(now);
                    taskRepository.save(task);
                    vo.setCreatedTaskCount(vo.getCreatedTaskCount() + 1);
                }
            }
        }
        return vo;
    }

    private static void mark(ImmuneGenerateVO vo, ImmuneDecisionVO decision, String code, String reason) {
        decision.setDecision(code);
        decision.setDecisionName("INCLUDED".equals(code) ? "纳入" : "DEFERRED".equals(code) ? "暂缓" : "不再适用");
        decision.setReason(reason);
        if ("DEFERRED".equals(code)) {
            vo.setDeferredCount(vo.getDeferredCount() + 1);
        } else if ("NOT_APPLICABLE".equals(code)) {
            vo.setNotApplicableCount(vo.getNotApplicableCount() + 1);
        }
    }

    /** 形成依据：人类可读的日期推算链 */
    private static String basisText(Pig pig, ImmunePlan plan, LocalDate firstDoseDate, int doseNo) {
        if (doseNo == 1) {
            return "出生 " + pig.getBirthDate() + " + 首剂日龄 " + plan.getFirstDoseAgeDays()
                    + " 天 → 第 1 剂计划 " + firstDoseDate;
        }
        LocalDate planned = firstDoseDate.plusDays((long) (doseNo - 1) * plan.getDoseIntervalDays());
        return "第 1 剂计划 " + firstDoseDate + " + 剂次间隔 " + plan.getDoseIntervalDays()
                + " 天 × " + (doseNo - 1) + " → 第 " + doseNo + " 剂计划 " + planned;
    }

    // ---------------- 状态清扫（读路径上惰性推进，保证转栏 / 死亡 / 手工登记后看板口径一致） ----------------

    /**
     * 清扫未完成任务：终态猪只 → 终止；出现等效接种记录 → 终止并关联记录；
     * 超过宽限期 → 漏种。在看板 / 任务列表 / 生成 / 确认前都会执行。
     */
    @Transactional
    public void sweep() {
        List<ImmuneTask> open = taskRepository.findByStatusInOrderByPlannedDateAscIdAsc(
                List.of("PENDING", "MISSED"));
        if (open.isEmpty()) {
            return;
        }
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        Map<Long, Pig> pigs = pigRepository.findAllByOrderByEarTagAsc().stream()
                .collect(Collectors.toMap(Pig::getId, Function.identity()));
        Map<Long, ImmunePlan> plans = planRepository.findAll().stream()
                .collect(Collectors.toMap(ImmunePlan::getId, Function.identity()));
        // 1) 终态猪只 → 终止（可追溯原因）
        for (ImmuneTask task : open) {
            Pig pig = pigs.get(task.getPigId());
            if (pig != null && Dict.isTerminalPigStatus(pig.getStatus())) {
                terminate(task, terminateReasonOf(pig.getStatus()), now);
            }
        }
        // 2) 等效接种记录 → 终止并双向关联（同一猪只同一疫苗的未关联记录，按日期升序贪心匹配）
        Map<String, List<ImmuneTask>> byPigPlan = open.stream()
                .filter(t -> !"TERMINATED".equals(t.getStatus()))
                .collect(Collectors.groupingBy(t -> t.getPigId() + ":" + t.getPlanId(),
                        LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<String, List<ImmuneTask>> entry : byPigPlan.entrySet()) {
            List<ImmuneTask> tasks = entry.getValue();
            ImmunePlan plan = plans.get(tasks.get(0).getPlanId());
            if (plan == null) {
                continue;
            }
            List<VaccineRecord> records = new ArrayList<>(vaccineRepository
                    .findByPigIdAndVaccineNameOrderByVaccineDateAscIdAsc(
                            tasks.get(0).getPigId(), plan.getVaccineName())
                    .stream().filter(r -> r.getTaskId() == null).toList());
            if (records.isEmpty()) {
                continue;
            }
            tasks.sort((a, b) -> a.getDoseNo().compareTo(b.getDoseNo()));
            for (ImmuneTask task : tasks) {
                LocalDate earliest = task.getPlannedDate().minusDays(task.getGraceDays());
                VaccineRecord hit = null;
                for (VaccineRecord record : records) {
                    if (record.getVaccineDate() != null && !record.getVaccineDate().isBefore(earliest)) {
                        hit = record;
                        break;
                    }
                }
                if (hit != null) {
                    records.remove(hit);
                    hit.setTaskId(task.getId());
                    hit.setPlanId(plan.getId());
                    hit.setDoseNo(task.getDoseNo());
                    vaccineRepository.save(hit);
                    task.setVaccineRecordId(hit.getId());
                    terminate(task, "EQUIVALENT", now);
                }
            }
        }
        // 3) 超过宽限期 → 漏种
        for (ImmuneTask task : open) {
            if ("PENDING".equals(task.getStatus())
                    && today.isAfter(task.getPlannedDate().plusDays(task.getGraceDays()))) {
                task.setStatus("MISSED");
            }
        }
    }

    /** 猪只进入终态时由 PigService 调用：未完成任务立即进入可追溯的终止状态 */
    @Transactional
    public void terminateForPig(Long pigId, String pigStatus) {
        if (!Dict.isTerminalPigStatus(pigStatus)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (ImmuneTask task : taskRepository.findByPigIdOrderByPlannedDateAscIdAsc(pigId)) {
            if ("PENDING".equals(task.getStatus()) || "MISSED".equals(task.getStatus())) {
                terminate(task, terminateReasonOf(pigStatus), now);
            }
        }
    }

    private static void terminate(ImmuneTask task, String reason, LocalDateTime now) {
        task.setStatus("TERMINATED");
        task.setTerminateReason(reason);
        task.setTerminatedAt(now);
    }

    private static String terminateReasonOf(String pigStatus) {
        return switch (pigStatus) {
            case "DEAD" -> "PIG_DEAD";
            case "SOLD" -> "PIG_SOLD";
            case "CULLED" -> "PIG_CULLED";
            default -> "PIG_DEAD";
        };
    }

    // ---------------- 看板与任务列表 ----------------

    @Transactional
    public ImmuneBoardVO board() {
        sweep();
        ImmuneBoardVO vo = new ImmuneBoardVO();
        vo.setPolicyNote(POLICY_NOTE);
        Map<String, ImmuneTaskGroupVO> groups = new LinkedHashMap<>();
        for (String[] group : GROUPS) {
            ImmuneTaskGroupVO g = new ImmuneTaskGroupVO();
            g.setKey(group[0]);
            g.setName(group[1]);
            groups.put(group[0], g);
            vo.getGroups().add(g);
        }
        TaskContext ctx = taskContext();
        List<ImmuneTask> tasks = taskRepository.findAll();
        tasks.sort((a, b) -> {
            int c = a.getPlannedDate().compareTo(b.getPlannedDate());
            return c != 0 ? c : a.getId().compareTo(b.getId());
        });
        for (ImmuneTask task : tasks) {
            ImmuneTaskVO taskVO = toTaskVO(task, ctx);
            groups.get(taskVO.getGroupKey()).getTasks().add(taskVO);
            vo.setTotal(vo.getTotal() + 1);
        }
        return vo;
    }

    /** 猪只详情追溯：该猪只的全部接种任务（含计划与形成依据）；pigId 为空时返回全部 */
    @Transactional
    public List<ImmuneTaskVO> listTasks(Long pigId) {
        sweep();
        TaskContext ctx = taskContext();
        List<ImmuneTask> tasks = pigId == null
                ? taskRepository.findAll()
                : taskRepository.findByPigIdOrderByPlannedDateAscIdAsc(pigId);
        List<ImmuneTaskVO> result = new ArrayList<>();
        for (ImmuneTask task : tasks) {
            result.add(toTaskVO(task, ctx));
        }
        return result;
    }

    // ---------------- 批量确认接种 ----------------

    /**
     * 一次确认可处理多头猪；每个任务在独立事务中处置，局部失败不影响其余结果。
     * 同一 requestNo 重复提交 → 幂等回放，不产生重复免疫记录。
     */
    public ImmuneConfirmResultVO confirm(ImmuneConfirmRequest request) {
        if (request == null || request.getTaskIds() == null || request.getTaskIds().isEmpty()) {
            throw new BizException("请先勾选要确认接种的任务");
        }
        LocalDate today = LocalDate.now();
        LocalDate confirmDate = request.getConfirmDate() == null ? today : request.getConfirmDate();
        if (confirmDate.isAfter(today)) {
            throw new BizException("接种日期不能晚于今天：" + confirmDate);
        }
        if (request.getMakeUpDate() != null && request.getMakeUpDate().isAfter(today)) {
            throw new BizException("补种日期不能晚于今天：" + request.getMakeUpDate());
        }
        if (request.getDoseMl() != null
                && (request.getDoseMl().compareTo(BigDecimal.ZERO) <= 0
                || request.getDoseMl().compareTo(Dict.MAX_DOSE_ML) > 0)) {
            throw new BizException("剂量必须在 0 ~ " + Dict.MAX_DOSE_ML + "ml 之间");
        }
        String requestNo = StringUtils.hasText(request.getRequestNo())
                ? request.getRequestNo().trim()
                : "IMM-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24).toUpperCase();
        request.setRequestNo(requestNo);
        request.setConfirmDate(confirmDate);

        List<Long> taskIds = request.getTaskIds().stream().distinct().sorted().toList();
        // 幂等回放：该批次已处理过，按任务现状还原结果，不产生新记录
        if (vaccineRepository.existsByRequestNo(requestNo)) {
            return replay(requestNo, taskIds);
        }
        ImmuneConfirmResultVO vo = new ImmuneConfirmResultVO();
        vo.setRequestNo(requestNo);
        for (Long taskId : taskIds) {
            ImmuneTaskResultVO result;
            try {
                result = confirmWorker.confirmOne(taskId, request);
            } catch (Exception e) {
                result = new ImmuneTaskResultVO();
                result.setTaskId(taskId);
                result.setResult("FAILED");
                result.setResultName("失败");
                result.setMessage(e instanceof BizException ? e.getMessage() : "系统异常：" + e.getMessage());
            }
            accumulate(vo, result);
        }
        vo.setTotalCount(vo.getResults().size());
        return vo;
    }

    /** 同一 requestNo 重试：按任务现状还原每头猪的结果，可核对且不会重复落记录 */
    private ImmuneConfirmResultVO replay(String requestNo, List<Long> taskIds) {
        Map<Long, VaccineRecord> recordByTask = vaccineRepository.findByRequestNoOrderByIdAsc(requestNo)
                .stream().filter(r -> r.getTaskId() != null)
                .collect(Collectors.toMap(VaccineRecord::getTaskId, Function.identity()));
        Map<Long, ImmuneTask> tasks = taskRepository.findAllById(taskIds).stream()
                .collect(Collectors.toMap(ImmuneTask::getId, Function.identity()));
        Map<Long, ImmunePlan> plans = planRepository.findAll().stream()
                .collect(Collectors.toMap(ImmunePlan::getId, Function.identity()));
        ImmuneConfirmResultVO vo = new ImmuneConfirmResultVO();
        vo.setRequestNo(requestNo);
        vo.setReplay(Boolean.TRUE);
        for (Long taskId : taskIds) {
            ImmuneTaskResultVO result = new ImmuneTaskResultVO();
            result.setTaskId(taskId);
            ImmuneTask task = tasks.get(taskId);
            VaccineRecord record = recordByTask.get(taskId);
            if (task != null && record != null) {
                fillResult(result, task, plans.get(task.getPlanId()));
                result.setResult("ALREADY");
                result.setResultName("已处理");
                result.setVaccineRecordId(record.getId());
                result.setMessage("该确认批次已处理（免疫记录 #" + record.getId() + "），重复提交未产生新记录");
            } else if (task != null) {
                fillResult(result, task, plans.get(task.getPlanId()));
                result.setResult("FAILED");
                result.setResultName("失败");
                result.setMessage("该确认批次已处理，此任务未产生免疫记录（当前状态："
                        + Dict.immuneTaskStatusName(task.getStatus()) + "）");
            } else {
                result.setResult("FAILED");
                result.setResultName("失败");
                result.setMessage("任务不存在：" + taskId);
            }
            accumulate(vo, result);
        }
        vo.setTotalCount(vo.getResults().size());
        return vo;
    }

    static void accumulate(ImmuneConfirmResultVO vo, ImmuneTaskResultVO result) {
        vo.getResults().add(result);
        switch (result.getResult() == null ? "FAILED" : result.getResult()) {
            case "DONE" -> vo.setDoneCount(vo.getDoneCount() + 1);
            case "MADE_UP" -> vo.setMadeUpCount(vo.getMadeUpCount() + 1);
            case "TERMINATED" -> vo.setTerminatedCount(vo.getTerminatedCount() + 1);
            case "ALREADY" -> vo.setAlreadyCount(vo.getAlreadyCount() + 1);
            default -> vo.setFailedCount(vo.getFailedCount() + 1);
        }
    }

    static void fillResult(ImmuneTaskResultVO result, ImmuneTask task, ImmunePlan plan) {
        result.setPigId(task.getPigId());
        result.setEarTag(task.getEarTag());
        result.setPlanCode(plan == null ? "" : plan.getPlanCode());
        result.setDoseNo(task.getDoseNo());
        result.setPlannedDate(task.getPlannedDate() == null ? null : task.getPlannedDate().toString());
        LocalDate completedDate = task.getCompletedDate() != null ? task.getCompletedDate() : LocalDate.now();
        result.setOverdueDays(Math.max(0L, ChronoUnit.DAYS.between(task.getPlannedDate(), completedDate)));
    }

    // ---------------- VO 组装 ----------------

    /** 任务展示上下文：当前猪只位置（转栏后跟随新栏位）与计划信息 */
    private TaskContext taskContext() {
        TaskContext ctx = new TaskContext();
        ctx.pigs = pigRepository.findAll().stream()
                .collect(Collectors.toMap(Pig::getId, Function.identity()));
        ctx.plans = planRepository.findAll().stream()
                .collect(Collectors.toMap(ImmunePlan::getId, Function.identity()));
        ctx.pens = penRepository.findAll().stream()
                .collect(Collectors.toMap(Pen::getId, Function.identity()));
        ctx.houses = houseRepository.findAll().stream()
                .collect(Collectors.toMap(House::getId, Function.identity()));
        return ctx;
    }

    private ImmuneTaskVO toTaskVO(ImmuneTask task, TaskContext ctx) {
        LocalDate today = LocalDate.now();
        ImmuneTaskVO vo = new ImmuneTaskVO();
        vo.setId(task.getId());
        vo.setPlanId(task.getPlanId());
        ImmunePlan plan = ctx.plans.get(task.getPlanId());
        vo.setPlanCode(plan == null ? "" : plan.getPlanCode());
        vo.setVaccineName(plan == null ? "" : plan.getVaccineName());
        vo.setTotalDoses(plan == null ? null : plan.getTotalDoses());
        vo.setPigId(task.getPigId());
        vo.setEarTag(task.getEarTag());
        vo.setDoseNo(task.getDoseNo());
        vo.setPlannedDate(task.getPlannedDate() == null ? null : task.getPlannedDate().toString());
        vo.setGraceDays(task.getGraceDays());
        vo.setStatus(task.getStatus());
        vo.setStatusName(Dict.immuneTaskStatusName(task.getStatus()));
        // 看板分组：待接种 / 临期（计划日期距今 ≤ 3 天，含宽限期内逾期）/ 漏种 / 已补种 / 已完成 / 已终止
        String groupKey = switch (task.getStatus()) {
            case "PENDING" -> task.getPlannedDate().isAfter(today.plusDays(Dict.IMMUNE_DUE_SOON_DAYS))
                    ? "DUE" : "DUE_SOON";
            case "MISSED" -> "MISSED";
            case "MADE_UP" -> "MADE_UP";
            case "DONE" -> "DONE";
            default -> "TERMINATED";
        };
        vo.setGroupKey(groupKey);
        vo.setGroupName(GROUPS.stream().filter(g -> g[0].equals(groupKey)).findFirst().orElseThrow()[1]);
        // 归属栏位：以猪只台账当前栏位为准（转栏不丢任务）；猪只已无栏位时回退到生成时快照
        Pig pig = ctx.pigs.get(task.getPigId());
        Long penId = pig != null && pig.getPenId() != null ? pig.getPenId() : task.getPenId();
        vo.setPenId(penId);
        Pen pen = penId == null ? null : ctx.pens.get(penId);
        vo.setPenCode(pen == null ? "无栏位" : pen.getPenCode());
        House house = pen == null ? null : ctx.houses.get(pen.getHouseId());
        vo.setHouseCode(house == null ? "" : house.getHouseCode());
        vo.setBasis(task.getBasis());
        LocalDate overdueBase = task.getCompletedDate() != null ? task.getCompletedDate() : today;
        vo.setOverdueDays(Math.max(0L, ChronoUnit.DAYS.between(task.getPlannedDate(), overdueBase)));
        vo.setTerminateReason(task.getTerminateReason());
        vo.setTerminateReasonName(Dict.immuneTerminateReasonName(task.getTerminateReason()));
        vo.setTerminatedAt(task.getTerminatedAt() == null ? null : task.getTerminatedAt().toString());
        vo.setMakeUpDate(task.getMakeUpDate() == null ? null : task.getMakeUpDate().toString());
        vo.setMakeUpReason(task.getMakeUpReason());
        vo.setCompletedDate(task.getCompletedDate() == null ? null : task.getCompletedDate().toString());
        vo.setVaccineRecordId(task.getVaccineRecordId());
        vo.setRequestNo(task.getRequestNo());
        return vo;
    }

    private ImmunePlan getPlan(Long id) {
        return planRepository.findById(id).orElseThrow(() -> new BizException("免疫计划不存在：" + id));
    }

    private String nextPlanCode() {
        String code;
        do {
            code = "IP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        } while (planRepository.existsByPlanCode(code));
        return code;
    }

    /** 任务 VO 组装用的预取上下文 */
    private static final class TaskContext {
        private Map<Long, Pig> pigs;
        private Map<Long, ImmunePlan> plans;
        private Map<Long, Pen> pens;
        private Map<Long, House> houses;
    }
}
