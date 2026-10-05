package com.hog.farm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/**
 * 免疫计划规则：按疫苗 + 适用生长阶段 + 首剂日龄 + 剂次间隔 + 宽限天数定义，
 * 据此为在场猪生成到期接种任务（immune_task）。
 */
@Entity
@Table(name = "immune_plan")
@Getter
@Setter
public class ImmunePlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 计划编号：唯一 */
    @Column(name = "plan_code", nullable = false, length = 32, unique = true)
    private String planCode;

    /** 疫苗名称 */
    @Column(name = "vaccine_name", nullable = false, length = 64)
    private String vaccineName;

    /** 适用生长阶段：SUCKLING / NURSERY / GROWER / FINISHING / BREEDING / ALL */
    @Column(name = "stage", nullable = false, length = 16)
    private String stage;

    /** 首剂日龄（天）：第 1 剂计划日期 = 出生日期 + 首剂日龄 */
    @Column(name = "first_dose_age_days", nullable = false)
    private Integer firstDoseAgeDays;

    /** 剂次间隔（天）：第 N 剂计划日期 = 第 1 剂 + (N-1) × 间隔 */
    @Column(name = "dose_interval_days", nullable = false)
    private Integer doseIntervalDays;

    /** 总剂次 */
    @Column(name = "total_doses", nullable = false)
    private Integer totalDoses;

    /** 宽限天数：计划日期 + 宽限天数仍未接种 → 漏种 */
    @Column(name = "grace_days", nullable = false)
    private Integer graceDays;

    /** 默认剂量（ml/头份），确认接种时可覆盖 */
    @Column(name = "dose_ml", nullable = false, precision = 8, scale = 2)
    private BigDecimal doseMl;

    /** ACTIVE 启用 / DISABLED 停用（停用后不再生成新任务） */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "note", length = 128)
    private String note;
}
