package com.hog.farm.dto;

import com.hog.farm.common.BizException;
import com.hog.farm.common.Dict;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Data;

/** 栋舍新建 / 更新请求 */
@Data
public class HouseRequest {

    /** 栋舍编号，全场唯一 */
    private String houseCode;

    private String houseName;

    /** 栋舍类型：GESTATION / FARROWING / NURSERY / FINISHING / ISOLATION */
    private String houseType;

    private BigDecimal areaSqm;

    private String keeper;

    private LocalDate builtDate;

    /** ACTIVE / MAINTAIN / DISABLED */
    private String status;

    private String note;

    /** 类型与状态的合法性校验（更新时字段为空表示不改） */
    public void validateTypeAndStatus() {
        if (houseType != null && !Dict.isValidHouseType(houseType)) {
            throw new BizException("栋舍类型只能是 配怀 / 分娩 / 保育 / 育肥 / 隔离：" + houseType);
        }
        if (status != null && !Dict.isValidHouseStatus(status)) {
            throw new BizException("栋舍状态非法：" + status);
        }
    }
}
