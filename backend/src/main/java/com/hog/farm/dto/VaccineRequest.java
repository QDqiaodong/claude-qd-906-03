package com.hog.farm.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Data;

/** 免疫记录请求 */
@Data
public class VaccineRequest {

    private Long pigId;

    /** 疫苗名称，必填 */
    private String vaccineName;

    /** 剂量（ml/头份），必须 > 0 */
    private BigDecimal doseMl;

    /** 免疫日期，不能晚于今天 */
    private LocalDate vaccineDate;

    private LocalDate nextDate;

    private String operator;

    private String remark;
}
