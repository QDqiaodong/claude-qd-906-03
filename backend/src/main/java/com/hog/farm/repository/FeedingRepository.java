package com.hog.farm.repository;

import com.hog.farm.domain.Feeding;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeedingRepository extends JpaRepository<Feeding, Long> {

    List<Feeding> findAllByOrderByFeedTimeDesc();

    List<Feeding> findByPenIdOrderByFeedTimeDesc(Long penId);
}
