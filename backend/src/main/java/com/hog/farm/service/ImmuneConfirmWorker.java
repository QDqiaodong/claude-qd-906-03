package com.hog.farm.service;

import com.hog.farm.common.BizException;
import com.hog.farm.common.Dict;
import com.hog.farm.domain.ImmunePlan;
import com.hog.farm.domain.ImmuneTask;
import com.hog.farm.domain.Pig;
import com.hog.farm.domain.VaccineRecord;
import com.hog.farm.dto.ImmuneConfirmRequest;
import com.hog.farm.dto.Views.ImmuneTaskResultVO;
import com.hog.farm.repository.ImmunePlanRepository;
import com.hog.farm.repository.ImmuneTaskRepository;
import com.hog.farm.repository.PigRepository;
import com.hog.farm.repository.VaccineRecordRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 单任务确认执行器：每个任务在独立事务（REQUIRES_NEW）中处置，
 * 局部失败只回滚当前任务，其余任务的完成结果与任务状态保持可核对。
 */
@Component
public class ImmuneConfirmWorker {

    private final ImmuneTaskRepository taskRepository;
    private final ImmunePlanRepository planRepository;
    private final PigRepository pigRepository;
    private final VaccineRecordRepository vaccineRepository;

    public ImmuneConfirmWorker(ImmuneTaskRepository taskRepository, ImmunePlanRepository planRepository,
                               PigRepository pigRepository, VaccineRecordRepository vaccineRepository) {
        this.taskRepository = taskRepository;
        this.planRepository = planRepository;
        this.pigRepository = pigRepository;
        this.vaccineRepository = vaccineRepository;
    }

    /**
     * 确认单个任务：行锁串行化同一任务的并发确认；完成态直接幂等返回；
     * 确认时按猪只现状复核（终态 / 等效记录 → 可追溯终止）；漏种必须登记补种日期与原因。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ImmuneTaskResultVO confirmOne(Long taskId, ImmuneConfirmRequest request) {
        LocalDate today = LocalDate.now();
        ImmuneTask task = taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new BizException("接种任务不存在：" + taskId));
        ImmunePlan plan = planRepository.findById(task.getPlanId()).orElse(null);
        ImmuneTaskResultVO result = new ImmuneTaskResultVO();
        ImmuneService.fillResult(result, task, plan);

        // 幂等：每头猪、同一计划和剂次只形成一个完成结果
        if ("DONE".equals(task.getStatus()) || "MADE_UP".equals(task.getStatus())) {
            result.setResult("ALREADY");
            result.setResultName("已处理");
            result.setVaccineRecordId(task.getVaccineRecordId());
            result.setMessage("该剂次已完成（免疫记录 #" + task.getVaccineRecordId() + "），重复操作已拦截");
            return result;
        }
        if ("TERMINATED".equals(task.getStatus())) {
            result.setResult("FAILED");
            result.setResultName("失败");
            result.setMessage("任务已终止（" + Dict.immuneTerminateReasonName(task.getTerminateReason()) + "），不能确认接种");
            return result;
        }
        if (vaccineRepository.existsByTaskId(task.getId())) {
            result.setResult("ALREADY");
            result.setResultName("已处理");
            result.setMessage("该任务已关联免疫记录，重复操作已拦截");
            return result;
        }

        // 确认时按猪只现状复核：终态猪只 → 可追溯终止
        Pig pig = pigRepository.findById(task.getPigId())
                .orElseThrow(() -> new BizException("猪只不存在：" + task.getPigId()));
        if (Dict.isTerminalPigStatus(pig.getStatus())) {
            terminate(task, pig.getStatus());
            taskRepository.save(task);
            result.setResult("TERMINATED");
            result.setResultName("已终止");
            result.setMessage("猪只已" + Dict.pigStatusName(pig.getStatus()) + "，任务进入终止状态");
            return result;
        }
        // 确认时按猪只现状复核：已有等效接种记录 → 可追溯终止并关联
        VaccineRecord equivalent = findEquivalent(pig.getId(),
                plan == null ? "" : plan.getVaccineName(), task);
        if (equivalent != null) {
            equivalent.setTaskId(task.getId());
            equivalent.setPlanId(task.getPlanId());
            equivalent.setDoseNo(task.getDoseNo());
            vaccineRepository.save(equivalent);
            task.setVaccineRecordId(equivalent.getId());
            task.setStatus("TERMINATED");
            task.setTerminateReason("EQUIVALENT");
            task.setTerminatedAt(LocalDateTime.now());
            taskRepository.save(task);
            result.setResult("TERMINATED");
            result.setResultName("已终止");
            result.setVaccineRecordId(equivalent.getId());
            result.setMessage("已存在等效接种记录（" + equivalent.getVaccineDate() + "，免疫记录 #"
                    + equivalent.getId() + "），任务进入终止状态");
            return result;
        }

        // 漏种判定：状态已是漏种，或确认当天已超过宽限期
        boolean missed = "MISSED".equals(task.getStatus())
                || today.isAfter(task.getPlannedDate().plusDays(task.getGraceDays()));
        LocalDate vaccineDate;
        String remark;
        if (missed) {
            if (request.getMakeUpDate() == null || !StringUtils.hasText(request.getMakeUpReason())) {
                result.setResult("FAILED");
                result.setResultName("失败");
                result.setMessage("漏种任务必须登记补种日期与原因后才能完成");
                return result;
            }
            if (request.getMakeUpDate().isBefore(task.getPlannedDate())) {
                result.setResult("FAILED");
                result.setResultName("失败");
                result.setMessage("补种日期不能早于计划日期 " + task.getPlannedDate());
                return result;
            }
            vaccineDate = request.getMakeUpDate();
            remark = "漏种补种：" + request.getMakeUpReason().trim();
        } else {
            vaccineDate = request.getConfirmDate();
            remark = "计划接种 第 " + task.getDoseNo() + " 剂";
        }

        BigDecimal doseMl = request.getDoseMl() != null ? request.getDoseMl()
                : (plan == null ? null : plan.getDoseMl());
        if (doseMl == null || doseMl.compareTo(BigDecimal.ZERO) <= 0) {
            result.setResult("FAILED");
            result.setResultName("失败");
            result.setMessage("剂量必须大于 0");
            return result;
        }

        // 落免疫记录（vaccine_record.task_id 唯一约束兜底，重试不会产生重复记录）
        VaccineRecord record = new VaccineRecord();
        record.setPigId(pig.getId());
        record.setEarTag(pig.getEarTag());
        record.setVaccineName(plan == null ? "" : plan.getVaccineName());
        record.setDoseMl(doseMl);
        record.setVaccineDate(vaccineDate);
        if (plan != null && task.getDoseNo() < plan.getTotalDoses()) {
            record.setNextDate(pig.getBirthDate().plusDays(plan.getFirstDoseAgeDays()
                    + (long) task.getDoseNo() * plan.getDoseIntervalDays()));
        }
        record.setOperator(request.getOperator());
        record.setRemark(remark);
        record.setCreatedAt(LocalDateTime.now());
        record.setPlanId(task.getPlanId());
        record.setTaskId(task.getId());
        record.setDoseNo(task.getDoseNo());
        record.setRequestNo(request.getRequestNo());
        record = vaccineRepository.save(record);

        LocalDateTime now = LocalDateTime.now();
        task.setStatus(missed ? "MADE_UP" : "DONE");
        if (missed) {
            task.setMakeUpDate(request.getMakeUpDate());
            task.setMakeUpReason(request.getMakeUpReason().trim());
        }
        task.setCompletedDate(vaccineDate);
        task.setCompletedAt(now);
        task.setVaccineRecordId(record.getId());
        task.setRequestNo(request.getRequestNo());
        taskRepository.save(task);

        ImmuneService.fillResult(result, task, plan);
        result.setResult(missed ? "MADE_UP" : "DONE");
        result.setResultName(missed ? "已补种" : "已完成");
        result.setVaccineRecordId(record.getId());
        result.setMessage(missed
                ? "补种完成（免疫记录 #" + record.getId() + "），原计划日期与逾期 "
                + result.getOverdueDays() + " 天已保留"
                : "接种完成（免疫记录 #" + record.getId() + "）");
        return result;
    }

    /** 等效记录：同一猪只同一疫苗、未关联任务、接种日期不早于 计划日期 - 宽限天数 的最早一条 */
    private VaccineRecord findEquivalent(Long pigId, String vaccineName, ImmuneTask task) {
        if (!StringUtils.hasText(vaccineName)) {
            return null;
        }
        LocalDate earliest = task.getPlannedDate().minusDays(task.getGraceDays());
        List<VaccineRecord> records = vaccineRepository
                .findByPigIdAndVaccineNameOrderByVaccineDateAscIdAsc(pigId, vaccineName);
        for (VaccineRecord record : records) {
            if (record.getTaskId() == null && record.getVaccineDate() != null
                    && !record.getVaccineDate().isBefore(earliest)) {
                return record;
            }
        }
        return null;
    }

    private static void terminate(ImmuneTask task, String pigStatus) {
        task.setStatus("TERMINATED");
        task.setTerminatedAt(LocalDateTime.now());
        task.setTerminateReason(switch (pigStatus) {
            case "DEAD" -> "PIG_DEAD";
            case "SOLD" -> "PIG_SOLD";
            case "CULLED" -> "PIG_CULLED";
            default -> "PIG_DEAD";
        });
    }
}
