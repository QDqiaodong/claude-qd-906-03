package com.hog.farm.dto;

import lombok.Data;

/** 撤销一条错误用药记录 */
@Data
public class MedicationRevokeRequest {

    private String reason;
    private String operator;
    private String requestNo;
}
