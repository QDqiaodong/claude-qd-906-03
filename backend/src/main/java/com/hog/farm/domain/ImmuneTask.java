package com.hog.farm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 接种任务：免疫计划为在场猪生成的到期接种待办。
 *
 * <p>执行口径为「生成时名单快照」：任务在生成时按当时在场猪只与现状固化为快照行，
 * 之后转栏、日龄增长不影响已生成任务（任务挂在猪只上，转栏不丢）；
 * 猪只死亡 / 出栏 / 淘汰或出现等效接种记录时，任务进入可追溯的终止状态；
 * 确认接种时逐头校验猪只现状，每头猪、同一计划和剂次只形成一个完成结果。</p>
 */
@Entity
@Table(name = "immune_task", uniqueConstraints = {
        @UniqueConstraint(name = "uk_task_dose", columnNames = {"plan_id", "pig_id", "dose_no"})})
@Getter
@Setter
public class ImmuneTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属免疫计划 */
    @Column(name = "plan_id", nullable = false)
    private Long planId;

    /** 猪只 id（任务跟随猪只，转栏不丢） */
    @Column(name = "pig_id", nullable = false)
    private Long pigId;

    /** 耳标号（冗余，便于列表直接展示） */
    @Column(name = "ear_tag", length = 32)
    private String earTag;

    /** 第几剂（从 1 开始） */
    @Column(name = "dose_no", nullable = false)
    private Integer doseNo;

    /** 计划日期：出生日期 + 首剂日龄 + (剂次-1) × 间隔；完成后仍保留 */
    @Column(name = "planned_date", nullable = false)
    private LocalDate plannedDate;

    /** 宽限天数（生成时从计划快照，避免计划调整后历史任务口径漂移） */
    @Column(name = "grace_days", nullable = false)
    private Integer graceDays;

    /** 生成时所在栏位（快照；当前栏位以猪只台账为准） */
    @Column(name = "pen_id")
    private Long penId;

    /** 形成依据：人类可读的日期推算链，如「出生 03-18 + 首剂 21 日龄 → 第 1 剂 04-08」 */
    @Column(name = "basis", length = 255)
    private String basis;

    /** PENDING 待接种 / MISSED 漏种 / DONE 已完成 / MADE_UP 已补种 / TERMINATED 已终止 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 终止原因：PIG_DEAD / PIG_SOLD / PIG_CULLED / EQUIVALENT / PLAN_DISABLED */
    @Column(name = "terminate_reason", length = 24)
    private String terminateReason;

    @Column(name = "terminated_at")
    private LocalDateTime terminatedAt;

    /** 补种日期（漏种任务必须登记补种日期与原因才能完成） */
    @Column(name = "make_up_date")
    private LocalDate makeUpDate;

    /** 补种原因 */
    @Column(name = "make_up_reason", length = 128)
    private String makeUpReason;

    /** 实际接种日期（确认日期或补种日期；与计划日期一起保留，用于核对逾期天数） */
    @Column(name = "completed_date")
    private LocalDate completedDate;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** 完成结果对应的免疫记录（每头猪、同一计划和剂次只形成一个） */
    @Column(name = "vaccine_record_id")
    private Long vaccineRecordId;

    /** 确认批次号：幂等键，重新提交同一批次不产生重复免疫记录 */
    @Column(name = "request_no", length = 48)
    private String requestNo;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
