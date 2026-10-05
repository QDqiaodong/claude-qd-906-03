package com.hog.farm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/**
 * 饲料批次：一次进场的同型号饲料。
 * 它同时是溯源记录 {@code @ManyToAny} 的四种可选来源之一。
 */
@Entity
@Table(name = "feed_batch")
@Getter
@Setter
public class FeedBatch implements TraceSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 饲料批次号：唯一，如 FB-2503-06 */
    @Column(name = "feed_no", nullable = false, unique = true, length = 32)
    private String feedNo;

    /** 饲料名称 */
    @Column(name = "feed_name", nullable = false, length = 64)
    private String feedName;

    /** 饲料类型：CREEP / NURSERY / FINISHER / SOW / BOAR */
    @Column(name = "feed_type", length = 24)
    private String feedType;

    /** 供应商 */
    @Column(name = "supplier", length = 64)
    private String supplier;

    /** 进货数量（kg） */
    @Column(name = "quantity_kg", precision = 10, scale = 2)
    private BigDecimal quantityKg;

    /** 生产日期 */
    @Column(name = "produce_date")
    private LocalDate produceDate;

    /** 保质期到期日 */
    @Column(name = "expire_date")
    private LocalDate expireDate;

    /** 状态：NORMAL 在库 / USED_UP 用完 / SCRAPPED 报废 */
    @Column(name = "status", length = 16)
    private String status;

    @Column(name = "note", length = 128)
    private String note;

    @Override
    public String getTraceCode() {
        return feedNo;
    }

    @Override
    public String getTraceName() {
        return feedName == null ? feedNo : feedName;
    }
}
