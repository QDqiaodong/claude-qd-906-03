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
 * 一次已提交的猪只级出栏放行单。request_no 唯一，保证重复提交和网络重试不重复扣减。
 */
@Entity
@Table(name = "shipment_order")
@Getter
@Setter
public class ShipmentOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_no", nullable = false, unique = true, length = 48)
    private String requestNo;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    /** ALL_OR_NONE 整批拦截 / PARTIAL 合格先出 */
    @Column(name = "release_mode", nullable = false, length = 16)
    private String releaseMode;

    @Column(name = "ship_date", nullable = false)
    private LocalDate shipDate;

    @Column(name = "released_count", nullable = false)
    private Integer releasedCount;

    @Column(name = "released_pig_ids", length = 2000)
    private String releasedPigIds;

    @Column(name = "operator", length = 32)
    private String operator;

    @Column(name = "buyer", length = 64)
    private String buyer;

    @Column(name = "trace_no", length = 32)
    private String traceNo;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
