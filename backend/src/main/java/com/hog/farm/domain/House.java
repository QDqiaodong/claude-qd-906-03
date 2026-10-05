package com.hog.farm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/**
 * 栋舍：猪场的最小建筑单元（配怀 / 分娩 / 保育 / 育肥 / 隔离）。
 * 状态链路：
 * - ACTIVE 在用：一切入口正常。
 * - MAINTAIN 检修：立即生效、原地照护。存栏保留、饲养员不变，饲喂 / 健康事件照常记录，
 *   但新建入栏、转入、恢复到该栋舍的入口一律拒绝（见 HouseService#assertCanReceive）。
 * - PENDING_DISABLE 待清空：停用申请已受理，只准出不准进；全部猪只迁出且栏位无未完操作后，
 *   才能由「完成停用」切换为 DISABLED。
 * - DISABLED 停用：仅当无存栏且无未完操作时才可达；其下所有栏位都不允许再放入猪只。
 */
@Entity
@Table(name = "house")
@Getter
@Setter
public class House implements TraceSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 栋舍编号：全场唯一，如 HS-A01 */
    @Column(name = "house_code", nullable = false, unique = true, length = 32)
    private String houseCode;

    /** 栋舍名称，如 一号配怀舍 */
    @Column(name = "house_name", length = 64)
    private String houseName;

    /** 栋舍类型：GESTATION / FARROWING / NURSERY / FINISHING / ISOLATION */
    @Column(name = "house_type", nullable = false, length = 24)
    private String houseType;

    /** 建筑面积（㎡） */
    @Column(name = "area_sqm", precision = 10, scale = 2)
    private BigDecimal areaSqm;

    /** 栋舍饲养员 */
    @Column(name = "keeper", length = 32)
    private String keeper;

    /** 投产日期 */
    @Column(name = "built_date")
    private LocalDate builtDate;

    /** 栏位数量（由栏位增删维护） */
    @Column(name = "pen_count")
    private Integer penCount;

    /** 栋舍状态：ACTIVE 在用 / MAINTAIN 检修（立即生效、原地照护）/ PENDING_DISABLE 待清空 / DISABLED 停用 */
    @Column(name = "status", length = 16)
    private String status;

    @Column(name = "note", length = 128)
    private String note;

    @Override
    public String getTraceCode() {
        return houseCode;
    }

    @Override
    public String getTraceName() {
        return houseName == null ? houseCode : houseName;
    }

    /** 受限状态：检修 / 待清空 / 停用都拒绝任何新增猪只入口 */
    @Transient
    public boolean isRestricted() {
        return "MAINTAIN".equals(status) || "PENDING_DISABLE".equals(status) || "DISABLED".equals(status);
    }

    @Transient
    public boolean isMaintain() {
        return "MAINTAIN".equals(status);
    }

    /** 待清空：停用已受理、等待猪只全部迁出与栏位未完操作收尾 */
    @Transient
    public boolean isPendingDisable() {
        return "PENDING_DISABLE".equals(status);
    }

    @Transient
    public boolean isDisabled() {
        return "DISABLED".equals(status);
    }
}
