package com.hog.farm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 免疫记录：一头猪一次疫苗注射。疫苗名称必填、剂量 > 0、免疫日期不能晚于今天。
 */
@Entity
@Table(name = "vaccine_record")
@Getter
@Setter
public class VaccineRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 猪只 id */
    @Column(name = "pig_id", nullable = false)
    private Long pigId;

    /** 耳标号（冗余，便于列表直接展示） */
    @Column(name = "ear_tag", length = 32)
    private String earTag;

    /** 疫苗名称：必填 */
    @Column(name = "vaccine_name", nullable = false, length = 64)
    private String vaccineName;

    /** 免疫剂量（ml/头份）：必须 > 0 */
    @Column(name = "dose_ml", nullable = false, precision = 8, scale = 2)
    private BigDecimal doseMl;

    /** 免疫日期：不能晚于今天 */
    @Column(name = "vaccine_date", nullable = false)
    private LocalDate vaccineDate;

    /** 下次免疫日期 */
    @Column(name = "next_date")
    private LocalDate nextDate;

    /** 免疫员 */
    @Column(name = "operator", length = 32)
    private String operator;

    @Column(name = "remark", length = 128)
    private String remark;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 来源免疫计划（手工登记为 null） */
    @Column(name = "plan_id")
    private Long planId;

    /** 来源接种任务：唯一，保证同一任务重试不产生重复免疫记录 */
    @Column(name = "task_id")
    private Long taskId;

    /** 计划内剂次（手工登记为 null） */
    @Column(name = "dose_no")
    private Integer doseNo;

    /** 确认批次号：与任务上的 request_no 对应，用于幂等回放 */
    @Column(name = "request_no", length = 48)
    private String requestNo;

    /** 是否逾期未做下次免疫 */
    @Transient
    public boolean isOverdue() {
        return nextDate != null && nextDate.isBefore(LocalDate.now());
    }
}
