package com.hog.farm.dto;

import java.math.BigDecimal;
import lombok.Data;

/** 免疫计划规则请求 */
@Data
public class ImmunePlanRequest {

    /** 计划编号，留空自动生成 */
    private String planCode;

    /** 疫苗名称，必填 */
    private String vaccineName;

    /** 适用生长阶段：ALL / SUCKLING / NURSERY / GROWER / FINISHING / BREEDING */
    private String stage;

    /** 首剂日龄（天），>= 0 */
    private Integer firstDoseAgeDays;

    /** 剂次间隔（天），>= 1 */
    private Integer doseIntervalDays;

    /** 总剂次，>= 1 */
    private Integer totalDoses;

    /** 宽限天数，>= 0 */
    private Integer graceDays;

    /** 默认剂量（ml/头份），> 0 */
    private BigDecimal doseMl;

    private String note;
}
