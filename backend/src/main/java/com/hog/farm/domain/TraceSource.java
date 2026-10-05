package com.hog.farm.domain;

/**
 * 溯源来源对象的共同父类型。
 *
 * <p>TraceRecord 用 Hibernate {@code @ManyToAny} 持有 {@code List<TraceSource>}，
 * 元素可以是猪只 / 出栏批次 / 栋舍 / 饲料批次中的任意组合；本接口就是那个“任意类型”的上界。
 * 四个实体都实现它，各自再打上 {@code @AnyDiscriminatorValue} 指定判别列取值。</p>
 */
public interface TraceSource {

    Long getId();

    /** 展示用编号（耳标号 / 批次号 / 栋舍编号 / 饲料批次号） */
    String getTraceCode();

    /** 展示用名称 */
    String getTraceName();
}
