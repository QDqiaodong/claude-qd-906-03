package com.hog.farm.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Data;

/** 饲料批次请求（饲料批次同时是溯源记录的四种多态来源之一） */
@Data
public class FeedBatchRequest {

    /** 饲料批次号，唯一 */
    private String feedNo;

    private String feedName;

    /** CREEP / NURSERY / FINISHER / SOW / BOAR */
    private String feedType;

    private String supplier;

    private BigDecimal quantityKg;

    private LocalDate produceDate;

    private LocalDate expireDate;

    /** NORMAL / USED_UP / SCRAPPED */
    private String status;

    private String note;
}
