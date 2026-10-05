package com.hog.farm.dto;

import java.time.LocalDate;
import lombok.Data;

/** 用药记录纠错 */
@Data
public class MedicationUpdateRequest {

    private String medicineName;
    private String dose;
    private LocalDate medicationDate;
    private Integer withdrawalDays;
    private String operator;
    private String remark;
    private String requestNo;
}
