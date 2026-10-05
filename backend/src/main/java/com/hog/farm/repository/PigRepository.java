package com.hog.farm.repository;

import com.hog.farm.domain.Pig;
import com.hog.farm.dto.BreedStatDTO;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PigRepository extends JpaRepository<Pig, Long> {

    boolean existsByEarTag(String earTag);

    Optional<Pig> findByEarTag(String earTag);

    List<Pig> findAllByOrderByEarTagAsc();

    List<Pig> findByPenIdOrderByEarTagAsc(Long penId);

    /** 按冗余栋舍 id 查（存栏处置核对、位置不明异常审计用） */
    List<Pig> findByHouseIdOrderByPenIdAscEarTagAsc(Long houseId);

    List<Pig> findByStatusOrderByEarTagAsc(String status);

    List<Pig> findByBatchIdOrderByEarTagAsc(Long batchId);

    List<Pig> findByBatchIdOrderByIdAsc(Long batchId);

    /** 出栏 / 用药事务内按固定顺序加行锁，防止并发放行和确认期间补药。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Pig p where p.id in :ids order by p.id asc")
    List<Pig> lockByIds(@Param("ids") List<Long> ids);

    @Query("select p from Pig p where p.penId = :penId order by p.id asc")
    List<Pig> findByPenIdOrderByIdAsc(@Param("penId") Long penId);

    long countByStatus(String status);

    /** 品种存栏分布：只统计在场的（已出栏 / 死亡 / 淘汰不入统计） */
    @Query("select new com.hog.farm.dto.BreedStatDTO(p.breed, count(p), coalesce(avg(p.weightKg), 0.0)) "
            + "from Pig p where p.status not in ('SOLD', 'DEAD', 'CULLED') group by p.breed")
    List<BreedStatDTO> statByBreed();
}
