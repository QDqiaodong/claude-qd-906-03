package com.hog.farm.dto;

import lombok.Data;

/**
 * 栋舍状态切换请求：操作员确认后提交，必须带操作员。
 * requestNo 由前端生成（确认弹窗一次一个），同一次切换的重复提交不重复生效。
 */
@Data
public class HouseStatusRequest {

    /** ACTIVE / MAINTAIN / PENDING_DISABLE / DISABLED */
    private String status;

    /** 操作员（确认弹窗必填） */
    private String operator;

    /** 操作员确认的处置备注（如「加热线检修，原地照护」） */
    private String reason;

    /** 幂等键：同一次确认的重试返回同一结果，不重复写日志/不重复生效 */
    private String requestNo;
}
