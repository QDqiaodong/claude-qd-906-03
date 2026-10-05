package com.hog.farm.repository;

import com.hog.farm.domain.Batch;
import com.hog.farm.dto.BatchStatusStatDTO;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BatchRepository extends JpaRepository<Batch, Long> {

    boolean existsByBatchNo(String batchNo);

    Optional<Batch> findByBatchNo(String batchNo);

    List<Batch> findAllByOrderByBatchNoAsc();

    List<Batch> findByStatusOrderByBatchNoAsc(String status);

    /** 出栏提交和批次内补药先锁批次，防止两个操作员同时累计扣减。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Batch b where b.id = :id")
    Optional<Batch> lockById(@Param("id") Long id);

    @Query("select new com.hog.farm.dto.BatchStatusStatDTO(b.status, count(b), "
            + "coalesce(sum(b.intakeCount), 0), coalesce(sum(b.soldCount), 0)) "
            + "from Batch b group by b.status")
    List<BatchStatusStatDTO> statByStatus();
}
