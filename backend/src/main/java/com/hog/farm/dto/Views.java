package com.hog.farm.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * 前端视图对象集合：所有列表接口统一返回这里的结构（带中文名称、颜色、派生比率），
 * 避免前端自己拼字典与算比率。
 */
public final class Views {

    private Views() {
    }

    /** 栋舍平面格子图：一个栋舍 + 它的若干栏位格子 */
    @Data
    public static class HouseVO {
        private Long id;
        private String houseCode;
        private String houseName;
        private String houseType;
        private String houseTypeName;
        private String houseTypeColor;
        private BigDecimal areaSqm;
        private String keeper;
        private String builtDate;
        private Integer penCount;
        private String status;
        private String statusName;
        /** 状态色票（在用 / 检修 / 待清空 / 停用） */
        private String statusColor;
        private String note;
        /** 栋舍总容量 */
        private Integer capacity = 0;
        /** 栏位计数合计（pig_count 之和，可能与实际存栏不符） */
        private Integer pigCount = 0;
        /** 实际在栋在栏的非终态猪只数（台账实测） */
        private Integer actualPigCount = 0;
        /** 栋舍存栏率 0~1（按实际存栏计） */
        private Double usageRate = 0D;
        /** 受限（检修 / 待清空 / 停用）：拒绝一切新增入口 */
        private Boolean restricted = Boolean.FALSE;
        /** 检修中：存栏保留、原地照护，只冻结新增 */
        private Boolean maintain = Boolean.FALSE;
        /** 待清空：停用已受理，等待迁出与收尾 */
        private Boolean pendingDisable = Boolean.FALSE;
        /** 停用栋舍不允许转入 */
        private Boolean disabled = Boolean.FALSE;
        /** 待清空/检修中的存栏处置进度：已清空栏位数 / 总栏位数 */
        private Integer clearedPenCount = 0;
        /** 待迁出猪只数（待清空时有意义，等于实际存栏） */
        private Integer pendingMoveCount = 0;
        /** 受限原因（人类可读，如「检修中：冻结新增，原地照护」「停用腾空：仍有 3 头待迁出」） */
        private String restrictReason;
        /** 待处理异常（超容 / 位置不明 / 计数不符 / 栏位未完操作），恢复在用前必须保留并处置 */
        private List<AnomalyVO> anomalies = new ArrayList<>();
        private List<PenVO> pens = new ArrayList<>();
    }

    /** 存栏处置异常：恢复在用前后都保留，直到台账实际修复才消失 */
    @Data
    public static class AnomalyVO {
        /** OVER_CAPACITY / LOCATION_UNKNOWN / COUNT_MISMATCH / PEN_BUSY */
        private String code;
        private String codeName;
        /** HOUSE / PEN / PIG 级别 */
        private String level;
        private Long penId;
        private String penCode;
        private Long pigId;
        private String earTag;
        private String message;
    }

    /** 格子图里的一个栏位格子 */
    @Data
    public static class PenVO {
        private Long id;
        private Long houseId;
        private String houseCode;
        private String houseName;
        private String penCode;
        private String penName;
        private Integer capacity;
        private Integer pigCount;
        /** 台账实测在栏非终态猪只数 */
        private Integer actualPigCount;
        private Integer remaining;
        private Double usageRate;
        private String status;
        private String statusName;
        /** 栏位是否有未完操作（清洗消毒 / 维修），停用生效前必须收尾 */
        private Boolean busy = Boolean.FALSE;
        /** 超容（实际存栏 > 容量） */
        private Boolean overCapacity = Boolean.FALSE;
        /** 计数不符（pig_count 与台账实测不一致） */
        private Boolean countMismatch = Boolean.FALSE;
        private String note;
    }

    /** 状态切换预检中的一头受影响猪只 */
    @Data
    public static class AffectedPigVO {
        private Long id;
        private String earTag;
        private String breedName;
        private String statusName;
        private Long penId;
        private String penCode;
        private BigDecimal weightKg;
    }

    /** 状态切换预检：操作员确认前必须看到受影响栏位、猪只清单和阻塞原因 */
    @Data
    public static class HouseStatusReviewVO {
        private Long houseId;
        private String houseCode;
        private String houseName;
        private String fromStatus;
        private String fromStatusName;
        private String targetStatus;
        private String targetStatusName;
        /** 处置模式：MAINTAIN_IN_PLACE 检修立即生效、原地照护；EMPTY_FIRST 先腾空再生效 */
        private String dispositionMode;
        private String dispositionModeName;
        private String ruleText;
        private Integer affectedPenCount = 0;
        private Integer affectedPigCount = 0;
        private Integer busyPenCount = 0;
        private Integer anomalyCount = 0;
        /** 待清空处置进度 */
        private Integer clearedPenCount = 0;
        private Integer totalPenCount = 0;
        /** 是否可按目标状态生效（停用生效要求全空且无未完操作） */
        private Boolean canApply = Boolean.FALSE;
        /** 阻塞原因（不可生效时逐条给出） */
        private List<String> blockers = new ArrayList<>();
        /** 警告（不阻塞，但操作员须知悉；如恢复在用时仍有维修栏位 / 遗留异常） */
        private List<String> warnings = new ArrayList<>();
        private List<PenVO> affectedPens = new ArrayList<>();
        private List<AffectedPigVO> affectedPigs = new ArrayList<>();
        private List<AnomalyVO> anomalies = new ArrayList<>();
    }

    /** 栋舍状态切换日志行 */
    @Data
    public static class HouseStatusLogVO {
        private Long id;
        private Long houseId;
        private String houseCode;
        private String fromStatus;
        private String fromStatusName;
        private String toStatus;
        private String toStatusName;
        private Integer pigCount;
        private String reason;
        private String operator;
        private String createdAt;
    }

    /** 猪只台账行 */
    @Data
    public static class PigVO {
        private Long id;
        private String earTag;
        private String breed;
        private String breedName;
        private String breedColor;
        private String gender;
        private String genderName;
        private String birthDate;
        private Long ageInDays;
        private String sourceType;
        private String sourceName;
        private Long penId;
        private String penCode;
        private Long houseId;
        private String houseCode;
        private Long batchId;
        private String batchNo;
        private String status;
        private String statusName;
        private BigDecimal weightKg;
        private String statusDate;
        private String note;
        private WithdrawalVO withdrawal = new WithdrawalVO();
    }

    /** 出栏批次卡片 */
    @Data
    public static class BatchVO {
        private Long id;
        private String batchNo;
        private String batchName;
        private Long houseId;
        private String houseCode;
        private String breed;
        private String breedName;
        private Integer intakeCount;
        private Integer soldCount;
        private Integer deadCount;
        private Integer remainCount;
        private Double soldRate;
        private String startDate;
        private String expectDate;
        private String status;
        private String statusName;
        private String statusColor;
        /** 下一个可推进状态（终态为 null） */
        private String nextStatus;
        private String nextStatusName;
        private String buyer;
        private BigDecimal avgWeightKg;
        private String note;
        /** 台账中实际仍未终态的猪只数 */
        private Integer activePigCount = 0;
        /** 当前在群且受休药期限制的猪只数 */
        private Integer restrictedPigCount = 0;
        /** 当前在群猪只中最晚的休药截止日；为空表示无限制 */
        private String latestWithdrawalDeadline;
        /** 该日期之后才允许出栏 */
        private String earliestShipDate;
    }

    /** 转栏记录行 */
    @Data
    public static class TransferVO {
        private Long id;
        private Long pigId;
        private String earTag;
        private Long fromPenId;
        private String fromPenCode;
        private Long toPenId;
        private String toPenCode;
        private String transferDate;
        private String reason;
        private String reasonName;
        private String operator;
        private String remark;
        private String createdAt;
    }

    /** 饲喂记录行 */
    @Data
    public static class FeedingVO {
        private Long id;
        private Long feedBatchId;
        private String feedNo;
        private Long penId;
        private String penCode;
        private String feedName;
        private String feedType;
        private String feedTypeName;
        private BigDecimal amountKg;
        private Integer pigCount;
        private BigDecimal perHeadKg;
        private String feedTime;
        private String operator;
        private String remark;
    }

    /** 免疫记录行 */
    @Data
    public static class VaccineVO {
        private Long id;
        private Long pigId;
        private String earTag;
        private String vaccineName;
        private BigDecimal doseMl;
        private String vaccineDate;
        private String nextDate;
        private String operator;
        private String remark;
        private Boolean overdue;
        /** 来源免疫计划 / 剂次（手工登记为 null） */
        private Long planId;
        private String planCode;
        private Integer doseNo;
    }

    /** 免疫计划规则行 */
    @Data
    public static class ImmunePlanVO {
        private Long id;
        private String planCode;
        private String vaccineName;
        private String stage;
        private String stageName;
        private Integer firstDoseAgeDays;
        private Integer doseIntervalDays;
        private Integer totalDoses;
        private Integer graceDays;
        private BigDecimal doseMl;
        private String status;
        private String statusName;
        private String note;
        /** 未完成（待接种 + 漏种）任务数 */
        private Integer openTaskCount = 0;
        /** 漏种任务数 */
        private Integer missedTaskCount = 0;
    }

    /** 接种任务行：计划日期、当前剂次、归属栏位、形成依据都直接展示 */
    @Data
    public static class ImmuneTaskVO {
        private Long id;
        private Long planId;
        private String planCode;
        private String vaccineName;
        private Long pigId;
        private String earTag;
        /** 当前剂次与总剂次 */
        private Integer doseNo;
        private Integer totalDoses;
        private String plannedDate;
        private Integer graceDays;
        private String status;
        private String statusName;
        /** 看板分组：DUE 待接种 / DUE_SOON 临期 / MISSED 漏种 / MADE_UP 已补种 / DONE 已完成 / TERMINATED 已终止 */
        private String groupKey;
        private String groupName;
        /** 归属栏位（当前台账栏位；猪只转栏后跟随新栏位） */
        private Long penId;
        private String penCode;
        private String houseCode;
        /** 形成依据：日期推算链 */
        private String basis;
        /** 逾期天数 = 今天（或完成日）- 计划日期，<= 0 时为 0 */
        private Long overdueDays = 0L;
        private String terminateReason;
        private String terminateReasonName;
        private String terminatedAt;
        private String makeUpDate;
        private String makeUpReason;
        private String completedDate;
        private Long vaccineRecordId;
        private String requestNo;
    }

    /** 看板分组 */
    @Data
    public static class ImmuneTaskGroupVO {
        private String key;
        private String name;
        private List<ImmuneTaskVO> tasks = new ArrayList<>();
    }

    /** 免疫计划看板：分组任务 + 执行口径说明 */
    @Data
    public static class ImmuneBoardVO {
        private List<ImmuneTaskGroupVO> groups = new ArrayList<>();
        private Integer total = 0;
        /** 执行口径（生成时名单快照），前端直接展示 */
        private String policyNote;
    }

    /** 生成预检 / 生成结果中的一头猪的处置决定 */
    @Data
    public static class ImmuneDecisionVO {
        private Long pigId;
        private String earTag;
        /** INCLUDED 纳入 / DEFERRED 暂缓 / NOT_APPLICABLE 不再适用 */
        private String decision;
        private String decisionName;
        /** 人类可读原因（为什么纳入 / 暂缓 / 不再适用） */
        private String reason;
        /** 纳入时涉及的计划剂次 */
        private List<DoseSlotVO> doses = new ArrayList<>();
    }

    /** 一个计划剂次的日期槽 */
    @Data
    public static class DoseSlotVO {
        private Integer doseNo;
        private String plannedDate;
        /** 任务是否已存在（重复生成时跳过，不产生重复任务） */
        private Boolean existed = Boolean.FALSE;
    }

    /** 生成预检 / 生成结果 */
    @Data
    public static class ImmuneGenerateVO {
        private Long planId;
        private String planCode;
        /** 是否已真正落库（false = 仅预检） */
        private Boolean generated = Boolean.FALSE;
        private Integer includedCount = 0;
        private Integer deferredCount = 0;
        private Integer notApplicableCount = 0;
        private Integer createdTaskCount = 0;
        private List<ImmuneDecisionVO> items = new ArrayList<>();
    }

    /** 批量确认中单个任务的处置结果（局部失败时其余结果仍可核对） */
    @Data
    public static class ImmuneTaskResultVO {
        private Long taskId;
        private Long pigId;
        private String earTag;
        private String planCode;
        private Integer doseNo;
        /** DONE / MADE_UP / ALREADY / TERMINATED / FAILED */
        private String result;
        private String resultName;
        private String message;
        private Long vaccineRecordId;
        private String plannedDate;
        private Long overdueDays;
    }

    /** 批量确认结果 */
    @Data
    public static class ImmuneConfirmResultVO {
        private String requestNo;
        /** 是否为同一 requestNo 的幂等回放（未产生新记录） */
        private Boolean replay = Boolean.FALSE;
        private Integer totalCount = 0;
        private Integer doneCount = 0;
        private Integer madeUpCount = 0;
        private Integer terminatedCount = 0;
        private Integer failedCount = 0;
        private Integer alreadyCount = 0;
        private List<ImmuneTaskResultVO> results = new ArrayList<>();
    }

    /** 休药期状态 */
    @Data
    public static class WithdrawalVO {
        private Boolean restricted = Boolean.FALSE;
        private Integer activeMedicationCount = 0;
        private String latestMedicationDate;
        private String latestMedicineName;
        /** 最晚休药截止日 = max(用药日期 + 休药天数) */
        private String deadline;
        /** 第一个允许出栏日期 = 截止日次日 */
        private String earliestShipDate;
        private Long remainingDays = 0L;
    }

    /** 用药记录行 */
    @Data
    public static class MedicationVO {
        private Long id;
        private String requestNo;
        private Long pigId;
        private String earTag;
        private String medicineName;
        private String dose;
        private String medicationDate;
        private Integer withdrawalDays;
        private String deadline;
        private String earliestShipDate;
        private String operator;
        private String remark;
        private String createdAt;
        private Long supersedesId;
        private Boolean revoked;
        private String revokedAt;
        private String revokeReason;
        private String traceNo;
        private Boolean active;
        private Boolean restrictedNow;
    }

    /** 出栏预检中的一头猪 */
    @Data
    public static class ShipmentPigVO {
        private Long id;
        private String earTag;
        private String status;
        private String statusName;
        private String penCode;
        private String deadline;
        private String earliestShipDate;
        private String reason;
        private Boolean autoSelected = Boolean.FALSE;
    }

    /** 出栏预检 / 已提交结果 */
    @Data
    public static class ShipmentReviewVO {
        private Long batchId;
        private String batchNo;
        private String releaseMode;
        private String releaseModeName;
        private String shipDate;
        private Integer requestedCount = 0;
        private Integer eligibleCount = 0;
        private Integer blockedCount = 0;
        private Integer skippedCount = 0;
        private Integer releasedCount = 0;
        private Integer batchSoldCount;
        private Integer batchIntakeCount;
        private Boolean selected = Boolean.FALSE;
        private Boolean canSubmit = Boolean.FALSE;
        private String selectionRule;
        private String requestNo;
        private String traceNo;
        private String message;
        private List<ShipmentPigVO> eligiblePigs = new ArrayList<>();
        private List<ShipmentPigVO> blockedPigs = new ArrayList<>();
        private List<ShipmentPigVO> skippedPigs = new ArrayList<>();
        private List<ShipmentPigVO> releasedPigs = new ArrayList<>();
    }

    /** @ManyToAny 的一个多态来源标签 */
    @Data
    public static class TargetVO {
        /** PIG / BATCH / HOUSE / FEED_BATCH */
        private String targetType;
        private String targetTypeName;
        private Long targetId;
        /** 来源对象编号（耳标号 / 批次号 / 栋舍编号 / 饲料批次号） */
        private String targetCode;
        private String targetName;
    }

    /** 溯源时间线节点 */
    @Data
    public static class TraceVO {
        private Long id;
        private String traceNo;
        private String traceType;
        private String traceTypeName;
        private String traceTypeColor;
        private String eventTitle;
        private String eventDesc;
        private String operator;
        private String eventTime;
        /** 时间线分组用的日期 yyyy-MM-dd */
        private String day;
        private List<TargetVO> targets = new ArrayList<>();
    }

    /** 顶部概览数字 */
    @Data
    public static class OverviewVO {
        private Long houseCount;
        private Long penCount;
        private Long pigCount;
        private Long onFarmCount;
        private Long batchCount;
        private Long feedBatchCount;
        private Long transferCount;
        private Long feedingCount;
        private Long vaccineCount;
        private Long traceCount;
        private Integer penCapacity;
        private Double penUsageRate;
    }
}
