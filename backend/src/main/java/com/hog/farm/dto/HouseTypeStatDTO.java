package com.hog.farm.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 栋舍类型分布（JPQL select new）。
 * 注意：JPQL 的构造器表达式要求显式构造器，参数个数与类型必须完全匹配，
 * 这里手写了 5 参构造器，不依赖 Lombok 的 @AllArgsConstructor。
 */
@Getter
@Setter
public class HouseTypeStatDTO {

    private String houseType;
    private String houseTypeName;
    private Long houseCount;
    private Long penCount;
    private Long pigCount;
    private Long capacity;

    public HouseTypeStatDTO(String houseType, Long houseCount, Long penCount, Long pigCount, Long capacity) {
        this.houseType = houseType;
        this.houseTypeName = com.hog.farm.common.Dict.houseTypeName(houseType);
        this.houseCount = houseCount;
        this.penCount = penCount;
        this.pigCount = pigCount;
        this.capacity = capacity;
    }
}
