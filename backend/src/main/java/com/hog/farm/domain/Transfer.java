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
 * 转栏记录：一头猪从一个栏位搬到另一个栏位。
 * 同一猪只同日重复转栏要拦截，目标栏位剩余容量不足要拦截。
 */
@Entity
@Table(name = "transfer")
@Getter
@Setter
public class Transfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 猪只 id */
    @Column(name = "pig_id", nullable = false)
    private Long pigId;

    /** 耳标号（冗余，便于列表直接展示） */
    @Column(name = "ear_tag", length = 32)
    private String earTag;

    /** 源栏位 id */
    @Column(name = "from_pen_id")
    private Long fromPenId;

    /** 目标栏位 id */
    @Column(name = "to_pen_id", nullable = false)
    private Long toPenId;

    /** 转栏日期：同一猪只同日只能转一次 */
    @Column(name = "transfer_date", nullable = false)
    private LocalDate transferDate;

    /** 转栏原因：日龄转栏 / 断奶转栏 / 隔离观察 / 病弱调栏 / 密度调整 / 出栏前集中 */
    @Column(name = "reason", length = 24)
    private String reason;

    /** 操作人 */
    @Column(name = "operator", length = 32)
    private String operator;

    @Column(name = "remark", length = 128)
    private String remark;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
