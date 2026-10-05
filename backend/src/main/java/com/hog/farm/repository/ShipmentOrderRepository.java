package com.hog.farm.repository;

import com.hog.farm.domain.ShipmentOrder;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShipmentOrderRepository extends JpaRepository<ShipmentOrder, Long> {

    Optional<ShipmentOrder> findByRequestNo(String requestNo);
}
