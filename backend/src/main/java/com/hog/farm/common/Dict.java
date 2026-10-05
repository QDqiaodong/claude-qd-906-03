package com.hog.farm.common;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 规模化养猪场词典与业务规则参数表：栋舍类型、品种、猪只/批次状态、饲料与疫苗名录、
 * 溯源类型与多态来源类型、各类数量上限。规则集中在此，便于出题时被要求调整或扩展。
 */
public final class Dict {

    private Dict() {
    }

    // ---------------- 栋舍类型（5 选 1） ----------------
    private static final Map<String, String> HOUSE_TYPE = Map.of(
            "GESTATION", "配怀",
            "FARROWING", "分娩",
            "NURSERY", "保育",
            "FINISHING", "育肥",
            "ISOLATION", "隔离");

    /** 各类型栋舍建议单栏容量（头），用于新建栏位时给默认值参考 */
    private static final Map<String, Integer> HOUSE_TYPE_PEN_CAPACITY = Map.of(
            "GESTATION", 6,
            "FARROWING", 1,
            "NURSERY", 20,
            "FINISHING", 30,
            "ISOLATION", 8);

    /** 各类型栋舍颜色（前端格子图用） */
    private static final Map<String, String> HOUSE_TYPE_COLOR = Map.of(
            "GESTATION", "#7e57c2",
            "FARROWING", "#ec407a",
            "NURSERY", "#29b6f6",
            "FINISHING", "#ff8f00",
            "ISOLATION", "#ef5350");

    // ---------------- 栋舍状态 ----------------
    // ACTIVE 在用 / MAINTAIN 检修（立即生效、原地照护）/ PENDING_DISABLE 待清空（先腾空再生效）/ DISABLED 停用
    private static final Map<String, String> HOUSE_STATUS = Map.of(
            "ACTIVE", "在用",
            "MAINTAIN", "检修",
            "PENDING_DISABLE", "待清空",
            "DISABLED", "停用");

    /** 待清空 / 停用色票（前端格子图用） */
    public static final String HOUSE_STATUS_PENDING_COLOR = "#ff8f00";
    public static final String HOUSE_STATUS_DISABLED_COLOR = "#9e9e9e";
    public static final String HOUSE_STATUS_MAINTAIN_COLOR = "#42a5f5";
    public static final String HOUSE_STATUS_ACTIVE_COLOR = "#66bb6a";

    // ---------------- 存栏处置异常（恢复在用前重新评估，遗留项不得静默清除） ----------------
    // OVER_CAPACITY 超容 / LOCATION_UNKNOWN 位置不明 / COUNT_MISMATCH 计数不符 / PEN_BUSY 栏位未完操作
    private static final Map<String, String> HOUSE_ANOMALY = Map.of(
            "OVER_CAPACITY", "超容",
            "LOCATION_UNKNOWN", "位置不明",
            "COUNT_MISMATCH", "计数不符",
            "PEN_BUSY", "栏位未完操作");

    // ---------------- 栏位状态 ----------------
    private static final Map<String, String> PEN_STATUS = Map.of(
            "NORMAL", "正常",
            "FULL", "满栏",
            "CLEANING", "清洗消毒",
            "REPAIR", "维修");

    // ---------------- 猪只品种（5 选 1） ----------------
    private static final Map<String, String> BREED = Map.of(
            "YORKSHIRE", "大白",
            "LANDRACE", "长白",
            "DUROC", "杜洛克",
            "PIETRAIN", "皮特兰",
            "BERKSHIRE", "巴克夏");

    /** 各品种出栏标准体重（kg） */
    private static final Map<String, BigDecimal> BREED_TARGET_WEIGHT = Map.of(
            "YORKSHIRE", BigDecimal.valueOf(115),
            "LANDRACE", BigDecimal.valueOf(112),
            "DUROC", BigDecimal.valueOf(125),
            "PIETRAIN", BigDecimal.valueOf(108),
            "BERKSHIRE", BigDecimal.valueOf(105));

    private static final Map<String, String> BREED_COLOR = Map.of(
            "YORKSHIRE", "#ffb74d",
            "LANDRACE", "#4fc3f7",
            "DUROC", "#e57373",
            "PIETRAIN", "#81c784",
            "BERKSHIRE", "#ba68c8");

    // ---------------- 猪只状态 ----------------
    private static final Map<String, String> PIG_STATUS = Map.of(
            "ON_FARM", "在场",
            "TRANSFERRED", "转栏",
            "SOLD", "出栏",
            "DEAD", "死亡",
            "CULLED", "淘汰");

    /** 已出栏 / 死亡 / 淘汰：终态，不允许再转栏 */
    private static final Set<String> PIG_TERMINAL_STATUS = Set.of("SOLD", "DEAD", "CULLED");

    /** 允许变更到的状态顺序（在场 → 转栏 → 出栏/死亡/淘汰） */
    public static final List<String> PIG_FLOW = List.of("ON_FARM", "TRANSFERRED", "SOLD", "DEAD", "CULLED");

    // ---------------- 猪只性别 ----------------
    private static final Map<String, String> GENDER = Map.of(
            "MALE", "公",
            "FEMALE", "母");

    // ---------------- 猪只来源 ----------------
    private static final Map<String, String> PIG_SOURCE = Map.of(
            "SELF", "自繁",
            "PURCHASE", "外购");

    // ---------------- 出栏批次状态（4 态顺序推进） ----------------
    private static final Map<String, String> BATCH_STATUS = Map.of(
            "BUILDING", "在建",
            "RAISING", "在养",
            "SELLING", "出栏中",
            "CLEARED", "已清栏");

    /** 批次状态推进顺序：在建 → 在养 → 出栏中 → 已清栏 */
    public static final List<String> BATCH_FLOW = List.of("BUILDING", "RAISING", "SELLING", "CLEARED");

    private static final Map<String, String> BATCH_STATUS_COLOR = Map.of(
            "BUILDING", "#90a4ae",
            "RAISING", "#29b6f6",
            "SELLING", "#ff8f00",
            "CLEARED", "#66bb6a");

    // ---------------- 转栏原因 ----------------
    private static final Map<String, String> TRANSFER_REASON = Map.of(
            "GROWTH", "日龄转栏",
            "WEAN", "断奶转栏",
            "ISOLATE", "隔离观察",
            "DISEASE", "病弱调栏",
            "DENSITY", "密度调整",
            "SELL", "出栏前集中");

    // ---------------- 饲料类型 ----------------
    private static final Map<String, String> FEED_TYPE = Map.of(
            "CREEP", "教槽料",
            "NURSERY", "保育料",
            "FINISHER", "育肥料",
            "SOW", "母猪料",
            "BOAR", "公猪料");

    /** 饲料批次状态 */
    private static final Map<String, String> FEED_STATUS = Map.of(
            "NORMAL", "在库",
            "USED_UP", "用完",
            "SCRAPPED", "报废");

    // ---------------- 免疫计划：适用生长阶段 ----------------
    // 按日龄划分；计划可指定某一阶段或 ALL（全阶段）
    private static final Map<String, String> GROWTH_STAGE = Map.of(
            "SUCKLING", "哺乳仔猪",
            "NURSERY", "保育猪",
            "GROWER", "育成猪",
            "FINISHING", "育肥猪",
            "BREEDING", "种用猪");

    /** 计划适用阶段 = 五个生长阶段 + ALL 全阶段 */
    private static final Map<String, String> PLAN_STAGE = Map.of(
            "ALL", "全阶段",
            "SUCKLING", "哺乳仔猪",
            "NURSERY", "保育猪",
            "GROWER", "育成猪",
            "FINISHING", "育肥猪",
            "BREEDING", "种用猪");

    // ---------------- 免疫任务状态 ----------------
    // PENDING 待接种 / MISSED 漏种（超过宽限期）/ DONE 已完成 / MADE_UP 已补种 / TERMINATED 已终止
    private static final Map<String, String> IMMUNE_TASK_STATUS = Map.of(
            "PENDING", "待接种",
            "MISSED", "漏种",
            "DONE", "已完成",
            "MADE_UP", "已补种",
            "TERMINATED", "已终止");

    /** 任务终止原因（可追溯）：终态猪只 / 已有等效接种记录 / 计划停用 */
    private static final Map<String, String> IMMUNE_TERMINATE_REASON = Map.of(
            "PIG_DEAD", "猪只死亡",
            "PIG_SOLD", "猪只已出栏",
            "PIG_CULLED", "猪只已淘汰",
            "EQUIVALENT", "已有等效接种记录",
            "PLAN_DISABLED", "计划已停用");

    /** 临期窗口（天）：计划日期距今不超过该天数的待接种任务进入「临期」分组 */
    public static final int IMMUNE_DUE_SOON_DAYS = 3;

    /** 生成窗口（天）：只生成计划日期在今天 + 该天数以内的任务，更远的剂次暂缓 */
    public static final int IMMUNE_GENERATE_HORIZON_DAYS = 30;

    // ---------------- 疫苗名录 ----------------
    public static final List<String> VACCINES = List.of(
            "猪瘟活疫苗", "口蹄疫 O 型灭活疫苗", "伪狂犬病活疫苗",
            "圆环病毒灭活疫苗", "高致病性蓝耳病活疫苗", "细小病毒灭活疫苗",
            "猪传染性胃肠炎二联苗", "支原体灭活疫苗");

    // ---------------- 溯源事件类型 ----------------
    private static final Map<String, String> TRACE_TYPE = Map.of(
            "IMMUNE", "免疫",
            "MEDICATION", "用药",
            "TRANSFER", "转栏",
            "FEEDING", "饲喂",
            "SALE", "出栏",
            "DEATH", "死亡",
            "CHECK", "检疫");

    private static final Map<String, String> TRACE_TYPE_COLOR = Map.of(
            "IMMUNE", "#66bb6a",
            "TRANSFER", "#29b6f6",
            "FEEDING", "#ff8f00",
            "SALE", "#7e57c2",
            "DEATH", "#ef5350",
            "CHECK", "#26c6da");

    // ---------------- @ManyToAny 多态来源类型 ----------------
    private static final Map<String, String> TARGET_TYPE = Map.of(
            "PIG", "猪只",
            "BATCH", "出栏批次",
            "HOUSE", "栋舍",
            "FEED_BATCH", "饲料批次");

    // ---------------- 业务参数上限 ----------------
    /** 单个栏位容量上限（头） */
    public static final int MAX_PEN_CAPACITY = 200;

    /** 单个批次入栏头数上限（头） */
    public static final int MAX_BATCH_INTAKE = 5000;

    /** 单次饲喂投料上限（kg） */
    public static final BigDecimal MAX_FEED_AMOUNT_KG = BigDecimal.valueOf(500);

    /** 单次免疫剂量上限（ml/头份） */
    public static final BigDecimal MAX_DOSE_ML = BigDecimal.valueOf(20);

    /** 出栏标准体重下限（kg） */
    public static final BigDecimal MIN_SALE_WEIGHT_KG = BigDecimal.valueOf(90);

    private static String name(Map<String, String> source, String key) {
        if (key == null) {
            return "";
        }
        return source.getOrDefault(key, key);
    }

    // ---------------- 取值方法 ----------------

    public static boolean isValidHouseType(String type) {
        return type != null && HOUSE_TYPE.containsKey(type);
    }

    public static String houseTypeName(String type) {
        return name(HOUSE_TYPE, type);
    }

    public static String houseTypeColor(String type) {
        return type == null ? "#90a4ae" : HOUSE_TYPE_COLOR.getOrDefault(type, "#90a4ae");
    }

    public static int houseTypePenCapacity(String type) {
        return HOUSE_TYPE_PEN_CAPACITY.getOrDefault(type, 10);
    }

    public static boolean isValidHouseStatus(String status) {
        return status != null && HOUSE_STATUS.containsKey(status);
    }

    public static String houseStatusName(String status) {
        return name(HOUSE_STATUS, status);
    }

    public static String houseStatusColor(String status) {
        if (status == null) {
            return "#90a4ae";
        }
        return switch (status) {
            case "ACTIVE" -> HOUSE_STATUS_ACTIVE_COLOR;
            case "MAINTAIN" -> HOUSE_STATUS_MAINTAIN_COLOR;
            case "PENDING_DISABLE" -> HOUSE_STATUS_PENDING_COLOR;
            case "DISABLED" -> HOUSE_STATUS_DISABLED_COLOR;
            default -> "#90a4ae";
        };
    }

    public static boolean isValidHouseAnomaly(String code) {
        return code != null && HOUSE_ANOMALY.containsKey(code);
    }

    public static String houseAnomalyName(String code) {
        return name(HOUSE_ANOMALY, code);
    }

    public static boolean isValidPenStatus(String status) {
        return status != null && PEN_STATUS.containsKey(status);
    }

    public static String penStatusName(String status) {
        return name(PEN_STATUS, status);
    }

    public static boolean isValidBreed(String breed) {
        return breed != null && BREED.containsKey(breed);
    }

    public static String breedName(String breed) {
        return name(BREED, breed);
    }

    public static String breedColor(String breed) {
        return breed == null ? "#90a4ae" : BREED_COLOR.getOrDefault(breed, "#90a4ae");
    }

    public static BigDecimal breedTargetWeight(String breed) {
        return BREED_TARGET_WEIGHT.getOrDefault(breed, BigDecimal.valueOf(110));
    }

    public static boolean isValidPigStatus(String status) {
        return status != null && PIG_STATUS.containsKey(status);
    }

    public static String pigStatusName(String status) {
        return name(PIG_STATUS, status);
    }

    /** 终态猪只：已出栏 / 死亡 / 淘汰，不允许再转栏或再次变更状态 */
    public static boolean isTerminalPigStatus(String status) {
        return status != null && PIG_TERMINAL_STATUS.contains(status);
    }

    public static boolean isValidGender(String gender) {
        return gender != null && GENDER.containsKey(gender);
    }

    public static String genderName(String gender) {
        return name(GENDER, gender);
    }

    public static boolean isValidPigSource(String source) {
        return source != null && PIG_SOURCE.containsKey(source);
    }

    public static String pigSourceName(String source) {
        return name(PIG_SOURCE, source);
    }

    public static boolean isValidBatchStatus(String status) {
        return status != null && BATCH_STATUS.containsKey(status);
    }

    public static String batchStatusName(String status) {
        return name(BATCH_STATUS, status);
    }

    public static String batchStatusColor(String status) {
        return status == null ? "#90a4ae" : BATCH_STATUS_COLOR.getOrDefault(status, "#90a4ae");
    }

    /** 批次状态的下一站；已是终态返回 null */
    public static String nextBatchStatus(String status) {
        int index = BATCH_FLOW.indexOf(status);
        if (index < 0 || index >= BATCH_FLOW.size() - 1) {
            return null;
        }
        return BATCH_FLOW.get(index + 1);
    }

    public static boolean isValidTransferReason(String reason) {
        return reason != null && TRANSFER_REASON.containsKey(reason);
    }

    public static String transferReasonName(String reason) {
        return name(TRANSFER_REASON, reason);
    }

    public static boolean isValidFeedType(String type) {
        return type != null && FEED_TYPE.containsKey(type);
    }

    public static String feedTypeName(String type) {
        return name(FEED_TYPE, type);
    }

    public static boolean isValidFeedStatus(String status) {
        return status != null && FEED_STATUS.containsKey(status);
    }

    public static String feedStatusName(String status) {
        return name(FEED_STATUS, status);
    }

    /** 按日龄划分的生长阶段：0-28 哺乳 / 29-70 保育 / 71-120 育成 / 121-200 育肥 / >200 种用 */
    public static String growthStageOf(long ageInDays) {
        if (ageInDays <= 28) {
            return "SUCKLING";
        }
        if (ageInDays <= 70) {
            return "NURSERY";
        }
        if (ageInDays <= 120) {
            return "GROWER";
        }
        if (ageInDays <= 200) {
            return "FINISHING";
        }
        return "BREEDING";
    }

    public static String growthStageName(String stage) {
        return name(GROWTH_STAGE, stage);
    }

    public static boolean isValidPlanStage(String stage) {
        return stage != null && PLAN_STAGE.containsKey(stage);
    }

    public static String planStageName(String stage) {
        return name(PLAN_STAGE, stage);
    }

    public static boolean isValidImmuneTaskStatus(String status) {
        return status != null && IMMUNE_TASK_STATUS.containsKey(status);
    }

    public static String immuneTaskStatusName(String status) {
        return name(IMMUNE_TASK_STATUS, status);
    }

    public static boolean isValidImmuneTerminateReason(String reason) {
        return reason != null && IMMUNE_TERMINATE_REASON.containsKey(reason);
    }

    public static String immuneTerminateReasonName(String reason) {
        return name(IMMUNE_TERMINATE_REASON, reason);
    }

    public static boolean isValidTraceType(String type) {
        return type != null && TRACE_TYPE.containsKey(type);
    }    public static String traceTypeName(String type) {
        return name(TRACE_TYPE, type);
    }

    public static String traceTypeColor(String type) {
        return type == null ? "#90a4ae" : TRACE_TYPE_COLOR.getOrDefault(type, "#90a4ae");
    }

    /** @ManyToAny 允许的多态来源类型 */
    public static boolean isValidTargetType(String type) {
        return type != null && TARGET_TYPE.containsKey(type);
    }

    public static String targetTypeName(String type) {
        return name(TARGET_TYPE, type);
    }
}
