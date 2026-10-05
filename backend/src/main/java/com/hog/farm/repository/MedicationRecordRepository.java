package com.hog.farm.repository;

import com.hog.farm.domain.MedicationRecord;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MedicationRecordRepository extends JpaRepository<MedicationRecord, Long> {

    Optional<MedicationRecord> findByRequestNo(String requestNo);

    boolean existsByRequestNo(String requestNo);

    List<MedicationRecord> findByPigIdAndRevokedFalseOrderByMedicationDateDescIdDesc(Long pigId);

    List<MedicationRecord> findByPigIdOrderByMedicationDateDescIdDesc(Long pigId);

    List<MedicationRecord> findByPigIdInAndRevokedFalseOrderByMedicationDateDescIdDesc(List<Long> pigIds);

    List<MedicationRecord> findAllByRevokedFalseOrderByMedicationDateDescIdDesc();

    List<MedicationRecord> findAllByOrderByMedicationDateDescIdDesc();
}
