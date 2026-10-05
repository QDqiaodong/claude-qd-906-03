package com.hog.farm.repository;

import com.hog.farm.domain.Transfer;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferRepository extends JpaRepository<Transfer, Long> {

    /** 同一猪只同日重复转栏拦截 */
    boolean existsByPigIdAndTransferDate(Long pigId, LocalDate transferDate);

    List<Transfer> findAllByOrderByTransferDateDescIdDesc();

    List<Transfer> findByPigIdOrderByTransferDateDesc(Long pigId);
}
