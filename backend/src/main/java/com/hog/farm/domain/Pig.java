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
 * 猪只个体：以耳标号为唯一标识。
 * 状态流转 在场 → 转栏 → 出栏 / 死亡 / 淘汰，后三者是终态，不允许再转栏。
 */
@Entity
@Table(name = "pig")
@Getter
@Setter
public class Pig implements TraceSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 耳标号：全场唯一，如 ET-20250312-018 */
    @Column(name = "ear_tag", nullable = false, unique = true, length = 32)
    private String earTag;

    /** 品种：YORKSHIRE / LANDRACE / DUROC / PIETRAIN / BERKSHIRE */
    @Column(name = "breed", nullable = false, length = 24)
    private String breed;

    /** 性别：MALE 公 / FEMALE 母 */
    @Column(name = "gender", length = 8)
    private String gender;

    /** 出生日期：不能晚于今天 */
    @Column(name = "birth_date", nullable = false)
    private LocalDate birthDate;

    /** 来源：SELF 自繁 / PURCHASE 外购 */
    @Column(name = "source_type", length = 16)
    private String sourceType;

    /** 当前所在栏位 id */
    @Column(name = "pen_id")
    private Long penId;

    /** 当前所在栋舍 id（与栏位冗余，便于按栋舍统计） */
    @Column(name = "house_id")
    private Long houseId;

    /** 所属出栏批次 id */
    @Column(name = "batch_id")
    private Long batchId;

    /** 状态：ON_FARM 在场 / TRANSFERRED 转栏 / SOLD 出栏 / DEAD 死亡 / CULLED 淘汰 */
    @Column(name = "status", length = 16)
    private String status;

    /** 最近称重（kg） */
    @Column(name = "weight_kg", precision = 8, scale = 2)
    private BigDecimal weightKg;

    /** 状态变更日期 */
    @Column(name = "status_date")
    private LocalDate statusDate;

    @Column(name = "note", length = 128)
    private String note;

    @Override
    public String getTraceCode() {
        return earTag;
    }

    @Override
    public String getTraceName() {
        return earTag;
    }

    /** 日龄（天） */
    @Transient
    public long getAgeInDays() {
        if (birthDate == null) {
            return 0;
        }
        return java.time.temporal.ChronoUnit.DAYS.between(birthDate, LocalDate.now());
    }
}
