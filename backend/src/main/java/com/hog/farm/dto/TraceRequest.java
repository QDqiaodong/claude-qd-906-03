package com.hog.farm.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/** 溯源记录请求：必须至少挂一个多态来源对象 */
@Data
public class TraceRequest {

    /** 溯源单号，不填自动生成 */
    private String traceNo;

    /** IMMUNE / TRANSFER / FEEDING / SALE / DEATH / CHECK */
    private String traceType;

    private String eventTitle;

    private String eventDesc;

    private String operator;

    private LocalDateTime eventTime;

    /** 多态来源对象：类型 + 主键，至少一条 */
    private List<TargetRef> targets = new ArrayList<>();

    /** @ManyToAny 的一个来源引用 */
    @Data
    public static class TargetRef {
        /** PIG / BATCH / HOUSE / FEED_BATCH */
        private String targetType;
        private Long targetId;
    }
}
