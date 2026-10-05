package com.hog.farm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;

/**
 * 栏位：隶属于某个栋舍，一个栏位关若干头猪。
 * 容量必须为正，转入时要校验剩余容量。
 */
@Entity
@Table(name = "pen")
@Getter
@Setter
public class Pen {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属栋舍 id */
    @Column(name = "house_id", nullable = false)
    private Long houseId;

    /** 栏位编号：全场唯一，如 HS-A01-03 */
    @Column(name = "pen_code", nullable = false, unique = true, length = 32)
    private String penCode;

    /** 栏位名称，如 A01 第 3 栏 */
    @Column(name = "pen_name", length = 64)
    private String penName;

    /** 容量（头），必须 > 0 */
    @Column(name = "capacity", nullable = false)
    private Integer capacity;

    /** 当前存栏头数 */
    @Column(name = "pig_count")
    private Integer pigCount;

    /** 栏位状态：NORMAL 正常 / FULL 满栏 / CLEANING 清洗消毒 / REPAIR 维修 */
    @Column(name = "status", length = 16)
    private String status;

    @Column(name = "note", length = 128)
    private String note;

    /** 剩余容量：容量 - 存栏，最小 0 */
    @Transient
    public int getRemaining() {
        int capacityValue = capacity == null ? 0 : capacity;
        int used = pigCount == null ? 0 : pigCount;
        return Math.max(0, capacityValue - used);
    }

    /** 存栏率（0~1），容量为空时为 0 */
    @Transient
    public double getUsageRate() {
        int capacityValue = capacity == null ? 0 : capacity;
        if (capacityValue <= 0) {
            return 0;
        }
        int used = pigCount == null ? 0 : pigCount;
        return Math.min(1.0, used * 1.0 / capacityValue);
    }
}
