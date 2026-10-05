package com.hog.farm.repository;

import com.hog.farm.domain.ImmunePlan;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImmunePlanRepository extends JpaRepository<ImmunePlan, Long> {

    boolean existsByPlanCode(String planCode);

    List<ImmunePlan> findAllByOrderByPlanCodeAsc();
}
