package com.hog.farm.dto;

import java.time.LocalDate;
import java.util.List;
import lombok.Data;

/**
 * 出栏预检 / 确认请求。
 * releaseMode：ALL_OR_NONE 整批拦截优先；PARTIAL 合格猪只先出栏。
 * pigIds 为空时按 id 升序稳定自动选择 count 头合格猪。
 */
@Data
public class ShipmentRequest {

    /** ALL_OR_NONE / PARTIAL */
    private String releaseMode;

    private LocalDate shipDate;

    private List<Long> pigIds;

    /** 不指定猪只时的自动选择头数 */
    private Integer count;

    private String operator;

    private String buyer;

    /** 前端为每次“确认”生成；重复提交和网络重试时返回首次结果 */
    private String requestNo;
}
