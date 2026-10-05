package com.hog.farm.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Data;

/** 出栏批次新建 / 更新请求 */
@Data
public class BatchRequest {

    /** 批次号，全场唯一 */
    private String batchNo;

    private String batchName;

    private Long houseId;

    private String breed;

    /** 入栏头数，必须 > 0 */
    private Integer intakeCount;

    private LocalDate startDate;

    private LocalDate expectDate;

    /** BUILDING / RAISING / SELLING / CLEARED */
    private String status;

    private String buyer;

    private BigDecimal avgWeightKg;

    private String note;
}
