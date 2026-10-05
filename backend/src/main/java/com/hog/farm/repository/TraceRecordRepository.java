package com.hog.farm.repository;

import com.hog.farm.domain.TraceRecord;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TraceRecordRepository extends JpaRepository<TraceRecord, Long> {

    boolean existsByTraceNo(String traceNo);

    Optional<TraceRecord> findByTraceNo(String traceNo);

    /** 时间线：按事件时间倒序 */
    List<TraceRecord> findAllByOrderByEventTimeDescIdDesc();

    List<TraceRecord> findByTraceTypeOrderByEventTimeDescIdDesc(String traceType);
}
