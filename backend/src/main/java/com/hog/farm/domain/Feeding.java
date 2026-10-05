package com.hog.farm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 饲喂记录：某个栏位一次投料。单次投料量必须 > 0 且不能超过单次上限。
 */
@Entity
@Table(name = "feeding")
@Getter
@Setter
public class Feeding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 饲料批次 id */
    @Column(name = "feed_batch_id")
    private Long feedBatchId;

    /** 被饲喂栏位 id */
    @Column(name = "pen_id")
    private Long penId;

    /** 饲料名称 */
    @Column(name = "feed_name", nullable = false, length = 64)
    private String feedName;

    /** 饲料类型：CREEP / NURSERY / FINISHER / SOW / BOAR */
    @Column(name = "feed_type", length = 24)
    private String feedType;

    /** 单次投料量（kg）：> 0 且有上限 */
    @Column(name = "amount_kg", nullable = false, precision = 10, scale = 2)
    private BigDecimal amountKg;

    /** 该栏当次饲喂头数 */
    @Column(name = "pig_count")
    private Integer pigCount;

    /** 饲喂时间 */
    @Column(name = "feed_time")
    private LocalDateTime feedTime;

    /** 投料人 */
    @Column(name = "operator", length = 32)
    private String operator;

    @Column(name = "remark", length = 128)
    private String remark;

    /** 平均每头投料量（kg） */
    @Transient
    public BigDecimal getPerHeadKg() {
        int heads = pigCount == null ? 0 : pigCount;
        BigDecimal amount = amountKg == null ? BigDecimal.ZERO : amountKg;
        if (heads <= 0) {
            return BigDecimal.ZERO;
        }
        return amount.divide(BigDecimal.valueOf(heads), 2, RoundingMode.HALF_UP);
    }
}
