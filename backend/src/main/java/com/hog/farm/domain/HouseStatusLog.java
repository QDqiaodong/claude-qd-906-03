package com.hog.farm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 栋舍状态切换日志：每一次生效的状态切换都留痕。
 * request_key 由（栋舍 id + 目标状态 + 前置状态）构成唯一键，
 * 同一次切换的重复提交在数据库层面被挡住，不能重复生效、不能重复写入安置结论。
 */
@Entity
@Table(name = "house_status_log")
@Getter
@Setter
public class HouseStatusLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "house_id", nullable = false)
    private Long houseId;

    @Column(name = "house_code", length = 32)
    private String houseCode;

    /** 切换前状态 */
    @Column(name = "from_status", length = 16)
    private String fromStatus;

    /** 切换后状态 */
    @Column(name = "to_status", length = 16)
    private String toStatus;

    /** 生效时存栏头数（检修 / 恢复时的原地照护存栏、待清空时的待迁出存栏） */
    @Column(name = "pig_count")
    private Integer pigCount;

    /** 操作员确认的处置说明（原地照护 / 待清空迁出 / 完成停用 / 恢复在用） */
    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "operator", length = 32)
    private String operator;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 幂等键：house_id|from_status|to_status|时间窗内由唯一索引保证不重复生效 */
    @Column(name = "request_key", nullable = false, unique = true, length = 128)
    private String requestKey;
}
