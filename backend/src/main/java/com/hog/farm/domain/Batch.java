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
import lombok.Getter;
import lombok.Setter;

/**
 * 出栏批次：同一栋舍同期入栏、同期出栏的一群猪。
 * 状态顺序推进：在建 → 在养 → 出栏中 → 已清栏；累计出栏头数不得超过入栏头数。
 */
@Entity
@Table(name = "batch")
@Getter
@Setter
public class Batch implements TraceSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 批次号：全场唯一，如 BATCH-2025-03 */
    @Column(name = "batch_no", nullable = false, unique = true, length = 32)
    private String batchNo;

    /** 批次名称，如 三月育肥一批 */
    @Column(name = "batch_name", length = 64)
    private String batchName;

    /** 所在栋舍 id */
    @Column(name = "house_id")
    private Long houseId;

    /** 品种 */
    @Column(name = "breed", length = 24)
    private String breed;

    /** 入栏头数：必须 > 0 */
    @Column(name = "intake_count", nullable = false)
    private Integer intakeCount;

    /** 累计出栏头数：不得超过入栏头数 */
    @Column(name = "sold_count")
    private Integer soldCount;

    /** 累计死淘头数 */
    @Column(name = "dead_count")
    private Integer deadCount;

    /** 入栏日期 */
    @Column(name = "start_date")
    private LocalDate startDate;

    /** 预计出栏日期 */
    @Column(name = "expect_date")
    private LocalDate expectDate;

    /** 状态：BUILDING / RAISING / SELLING / CLEARED */
    @Column(name = "status", length = 16)
    private String status;

    /** 收购方 / 屠宰场 */
    @Column(name = "buyer", length = 64)
    private String buyer;

    /** 当前平均体重（kg） */
    @Column(name = "avg_weight_kg", precision = 8, scale = 2)
    private BigDecimal avgWeightKg;

    @Column(name = "note", length = 128)
    private String note;

    @Override
    public String getTraceCode() {
        return batchNo;
    }

    @Override
    public String getTraceName() {
        return batchName == null ? batchNo : batchName;
    }

    /** 剩余存栏：入栏 - 出栏 - 死淘 */
    @Transient
    public int getRemainCount() {
        int intake = intakeCount == null ? 0 : intakeCount;
        int sold = soldCount == null ? 0 : soldCount;
        int dead = deadCount == null ? 0 : deadCount;
        return Math.max(0, intake - sold - dead);
    }

    /** 出栏进度（0~1） */
    @Transient
    public double getSoldRate() {
        int intake = intakeCount == null ? 0 : intakeCount;
        if (intake <= 0) {
            return 0;
        }
        int sold = soldCount == null ? 0 : soldCount;
        return Math.min(1.0, sold * 1.0 / intake);
    }
}
