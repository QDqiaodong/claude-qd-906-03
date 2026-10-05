package com.hog.farm.repository;

import com.hog.farm.domain.HouseStatusLog;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HouseStatusLogRepository extends JpaRepository<HouseStatusLog, Long> {

    boolean existsByRequestKey(String requestKey);

    List<HouseStatusLog> findByHouseIdOrderByCreatedAtDescIdDesc(Long houseId);

    List<HouseStatusLog> findAllByOrderByCreatedAtDescIdDesc();
}
