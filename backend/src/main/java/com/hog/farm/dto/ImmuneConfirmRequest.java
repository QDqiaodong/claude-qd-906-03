package com.hog.farm.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.Data;

/**
 * 批量确认接种请求：一次确认可处理多头猪（多个任务）。
 * 漏种任务必须登记补种日期与原因；requestNo 为幂等键，重试不产生重复免疫记录。
 */
@Data
public class ImmuneConfirmRequest {

    /** 确认批次号（前端每次打开确认框生成一次）；同一 requestNo 重复提交返回原结果 */
    private String requestNo;

    /** 要确认的任务 id 列表 */
    private List<Long> taskIds;

    /** 接种日期（正常任务用），默认今天，不能晚于今天 */
    private LocalDate confirmDate;

    /** 剂量（ml/头份），留空用计划默认剂量 */
    private BigDecimal doseMl;

    private String operator;

    /** 补种日期（选中任务含漏种时必填） */
    private LocalDate makeUpDate;

    /** 补种原因（选中任务含漏种时必填） */
    private String makeUpReason;
}
