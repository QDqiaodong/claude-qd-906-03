package com.hog.farm.repository;

import com.hog.farm.domain.FeedBatch;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeedBatchRepository extends JpaRepository<FeedBatch, Long> {

    boolean existsByFeedNo(String feedNo);

    Optional<FeedBatch> findByFeedNo(String feedNo);

    List<FeedBatch> findAllByOrderByFeedNoAsc();

    List<FeedBatch> findByStatusOrderByFeedNoAsc(String status);
}
