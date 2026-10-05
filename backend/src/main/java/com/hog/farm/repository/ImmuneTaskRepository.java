package com.hog.farm.repository;

import com.hog.farm.domain.ImmuneTask;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ImmuneTaskRepository extends JpaRepository<ImmuneTask, Long> {

    boolean existsByPlanIdAndPigIdAndDoseNo(Long planId, Long pigId, Integer doseNo);

    List<ImmuneTask> findByStatusInOrderByPlannedDateAscIdAsc(Collection<String> statuses);

    List<ImmuneTask> findByPigIdOrderByPlannedDateAscIdAsc(Long pigId);

    List<ImmuneTask> findByPlanIdOrderByPlannedDateAscIdAsc(Long planId);

    List<ImmuneTask> findByRequestNoOrderByIdAsc(String requestNo);

    /** 确认接种时逐头加行锁，保证同一任务不会被并发完成两次 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from ImmuneTask t where t.id = :id")
    Optional<ImmuneTask> findByIdForUpdate(@Param("id") Long id);
}
