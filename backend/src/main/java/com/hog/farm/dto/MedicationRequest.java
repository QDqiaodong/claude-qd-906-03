package com.hog.farm.dto;

import java.time.LocalDate;
import java.util.List;
import lombok.Data;

/** 按勾选猪只或整个在批猪群批量登记用药 */
@Data
public class MedicationRequest {

    /** 显式勾选的猪只；为空且 batchId 有值时，取该批次当前未终态猪只 */
    private List<Long> pigIds;

    private Long batchId;

    private String medicineName;

    /** 剂量 / 规格说明，如 2ml/头 */
    private String dose;

    private LocalDate medicationDate;

    private Integer withdrawalDays;

    private String operator;

    private String remark;

    /** 前端生成；为空时后端生成。重复请求号重试不会产生重复记录 */
    private String requestNo;
}
