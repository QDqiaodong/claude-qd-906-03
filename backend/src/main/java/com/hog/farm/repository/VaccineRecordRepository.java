package com.hog.farm.repository;

import com.hog.farm.domain.VaccineRecord;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VaccineRecordRepository extends JpaRepository<VaccineRecord, Long> {

    List<VaccineRecord> findAllByOrderByVaccineDateDescIdDesc();

    List<VaccineRecord> findByPigIdOrderByVaccineDateDesc(Long pigId);

    /** 等效接种记录判定：同一猪只同一疫苗的历史记录（按日期升序做贪心匹配） */
    List<VaccineRecord> findByPigIdAndVaccineNameOrderByVaccineDateAscIdAsc(Long pigId, String vaccineName);

    List<VaccineRecord> findByRequestNoOrderByIdAsc(String requestNo);

    boolean existsByRequestNo(String requestNo);

    boolean existsByTaskId(Long taskId);
}
