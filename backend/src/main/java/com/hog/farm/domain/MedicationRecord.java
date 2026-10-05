package com.hog.farm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 用药记录：支持补登、纠错和撤销。休药期以未撤销且未到期的记录动态计算。
 */
@Entity
@Table(name = "medication_record")
@Getter
@Setter
public class MedicationRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 一次批量保存的幂等请求号；同一请求号重试不会重复登记 */
    @Column(name = "request_no", nullable = false, unique = true, length = 48)
    private String requestNo;

    @Column(name = "pig_id", nullable = false)
    private Long pigId;

    @Column(name = "ear_tag", length = 32)
    private String earTag;

    @Column(name = "medicine_name", nullable = false, length = 64)
    private String medicineName;

    @Column(name = "dose", length = 64)
    private String dose;

    @Column(name = "medication_date", nullable = false)
    private LocalDate medicationDate;

    /** 休药天数；休药截止日 = 用药日期 + 休药天数，出栏日必须晚于截止日 */
    @Column(name = "withdrawal_days", nullable = false)
    private Integer withdrawalDays;

    @Column(name = "operator", length = 32)
    private String operator;

    @Column(name = "remark", length = 128)
    private String remark;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 纠错保留原记录编号；首次登记为空 */
    @Column(name = "supersedes_id")
    private Long supersedesId;

    @Column(name = "revoked", nullable = false)
    private Boolean revoked = Boolean.FALSE;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "revoke_reason", length = 128)
    private String revokeReason;

    @Column(name = "trace_no", length = 32)
    private String traceNo;
}
