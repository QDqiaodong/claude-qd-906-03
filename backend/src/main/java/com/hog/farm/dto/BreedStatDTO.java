package com.hog.farm.dto;

import com.hog.farm.common.Dict;
import lombok.Getter;
import lombok.Setter;

/**
 * 品种存栏分布（JPQL select new，3 参显式构造器）。
 * avgWeight 由 JPQL avg() 得出，JDBC 返回 Double。
 */
@Getter
@Setter
public class BreedStatDTO {

    private String breed;
    private String breedName;
    private String breedColor;
    private Long pigCount;
    private Double avgWeight;

    public BreedStatDTO(String breed, Long pigCount, Double avgWeight) {
        this.breed = breed;
        this.breedName = Dict.breedName(breed);
        this.breedColor = Dict.breedColor(breed);
        this.pigCount = pigCount;
        this.avgWeight = avgWeight == null ? 0D : avgWeight;
    }
}
