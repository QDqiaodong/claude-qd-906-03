package com.hog.farm.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 饲喂投料请求 */
@Data
public class FeedingRequest {

    private Long feedBatchId;

    private Long penId;

    /** 饲料名称 */
    private String feedName;

    /** CREEP / NURSERY / FINISHER / SOW / BOAR */
    private String feedType;

    /** 单次投料量（kg），> 0 且有上限 */
    private BigDecimal amountKg;

    private Integer pigCount;

    private LocalDateTime feedTime;

    private String operator;

    private String remark;
}
