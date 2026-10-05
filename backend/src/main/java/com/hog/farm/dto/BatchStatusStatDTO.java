package com.hog.farm.dto;

import com.hog.farm.common.Dict;
import lombok.Getter;
import lombok.Setter;

/**
 * 出栏批次状态分布（JPQL select new，4 参显式构造器）。
 */
@Getter
@Setter
public class BatchStatusStatDTO {

    private String status;
    private String statusName;
    private String statusColor;
    private Long batchCount;
    private Long intakeTotal;
    private Long soldTotal;

    public BatchStatusStatDTO(String status, Long batchCount, Long intakeTotal, Long soldTotal) {
        this.status = status;
        this.statusName = Dict.batchStatusName(status);
        this.statusColor = Dict.batchStatusColor(status);
        this.batchCount = batchCount;
        this.intakeTotal = intakeTotal;
        this.soldTotal = soldTotal;
    }
}
