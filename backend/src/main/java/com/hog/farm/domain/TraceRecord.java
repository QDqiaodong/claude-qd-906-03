package com.hog.farm.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.AnyDiscriminator;
import org.hibernate.annotations.AnyDiscriminatorValue;
import org.hibernate.annotations.AnyKeyJavaClass;
import org.hibernate.annotations.ManyToAny;

/**
 * 溯源 / 事件记录：一条记录可以同时挂若干个「任意类型的来源对象」。
 *
 * <p>这里是本仓的后端技术主线：Hibernate 6.5 的 {@code @ManyToAny} 多态关联集合。
 * 关联落在独立集合表 {@code trace_target}（trace_record_id + target_type + target_id），
 * target_type 取值为 PIG / BATCH / HOUSE / FEED_BATCH，分别指向猪只、出栏批次、栋舍、饲料批次。
 * 新增溯源记录时把真实实体塞进 {@code targets} 集合再保存，读取时 Hibernate 按判别列把实体取回来。</p>
 */
@Entity
@Table(name = "trace_record")
@Getter
@Setter
public class TraceRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 溯源单号：唯一，如 TR-20250312-001 */
    @Column(name = "trace_no", nullable = false, unique = true, length = 32)
    private String traceNo;

    /** 事件类型：IMMUNE / TRANSFER / FEEDING / SALE / DEATH / CHECK */
    @Column(name = "trace_type", nullable = false, length = 24)
    private String traceType;

    /** 事件标题 */
    @Column(name = "event_title", length = 64)
    private String eventTitle;

    /** 事件描述 */
    @Column(name = "event_desc", length = 255)
    private String eventDesc;

    /** 记录人 */
    @Column(name = "operator", length = 32)
    private String operator;

    /** 事件发生时间 */
    @Column(name = "event_time")
    private LocalDateTime eventTime;

    /**
     * 多态来源对象集合（至少挂一个）。
     * 走 @ManyToAny + 字符串判别列 + Long 主键 + 集合表 trace_target。
     * 不直接参与 JSON 序列化，由 TraceService 转成「来源类型 + 编号」的标签。
     */
    @ManyToAny(fetch = FetchType.EAGER)
    @AnyDiscriminator(DiscriminatorType.STRING)
    @AnyKeyJavaClass(Long.class)
    @AnyDiscriminatorValue(discriminator = "PIG", entity = Pig.class)
    @AnyDiscriminatorValue(discriminator = "BATCH", entity = Batch.class)
    @AnyDiscriminatorValue(discriminator = "HOUSE", entity = House.class)
    @AnyDiscriminatorValue(discriminator = "FEED_BATCH", entity = FeedBatch.class)
    @JoinTable(name = "trace_target",
            joinColumns = @JoinColumn(name = "trace_record_id"),
            inverseJoinColumns = @JoinColumn(name = "target_id"))
    @Column(name = "target_type")
    @JsonIgnore
    private List<TraceSource> targets = new ArrayList<>();
}
