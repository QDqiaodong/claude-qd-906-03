package com.hog.farm.dto;

import java.time.LocalDate;
import java.util.List;
import lombok.Data;

/** 转栏请求：支持单头转栏，也支持勾选多头一次搬移 */
@Data
public class TransferRequest {

    /** 猪只 id（单头） */
    private Long pigId;

    /** 猪只 id 列表（批量，和 pigId 二选一） */
    private List<Long> pigIds;

    /** 源栏位 id，可为空（首次入栏） */
    private Long fromPenId;

    /** 目标栏位 id */
    private Long toPenId;

    /** 转栏日期，默认今天 */
    private LocalDate transferDate;

    /** 转栏原因：日龄转栏等 */
    private String reason;

    private String operator;

    private String remark;
}
