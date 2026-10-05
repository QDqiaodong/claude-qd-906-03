package com.hog.farm.dto;

import lombok.Data;

/** 栏位新建 / 更新请求 */
@Data
public class PenRequest {

    /** 所属栋舍 id */
    private Long houseId;

    /** 栏位编号，全场唯一 */
    private String penCode;

    private String penName;

    /** 容量（头），必须 > 0 */
    private Integer capacity;

    /** NORMAL / FULL / CLEANING / REPAIR */
    private String status;

    private String note;
}
