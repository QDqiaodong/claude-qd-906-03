package com.hog.farm.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Data;

/** 猪只新建 / 更新请求 */
@Data
public class PigRequest {

    /** 耳标号，全场唯一 */
    private String earTag;

    /** 品种 5 选 1 */
    private String breed;

    /** MALE / FEMALE */
    private String gender;

    /** 出生日期，不能晚于今天 */
    private LocalDate birthDate;

    /** SELF 自繁 / PURCHASE 外购 */
    private String sourceType;

    private Long penId;

    private Long houseId;

    private Long batchId;

    /** ON_FARM / TRANSFERRED / SOLD / DEAD / CULLED */
    private String status;

    private BigDecimal weightKg;

    private LocalDate statusDate;

    private String note;
}
