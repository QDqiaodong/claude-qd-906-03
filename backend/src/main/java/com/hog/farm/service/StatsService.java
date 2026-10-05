package com.hog.farm.service;

import com.hog.farm.domain.Pen;
import com.hog.farm.dto.BatchStatusStatDTO;
import com.hog.farm.dto.BreedStatDTO;
import com.hog.farm.dto.HouseTypeStatDTO;
import com.hog.farm.dto.Views.OverviewVO;
import com.hog.farm.repository.BatchRepository;
import com.hog.farm.repository.FeedBatchRepository;
import com.hog.farm.repository.FeedingRepository;
import com.hog.farm.repository.HouseRepository;
import com.hog.farm.repository.PenRepository;
import com.hog.farm.repository.PigRepository;
import com.hog.farm.repository.TraceRecordRepository;
import com.hog.farm.repository.TransferRepository;
import com.hog.farm.repository.VaccineRecordRepository;
import java.util.List;
import org.springframework.stereotype.Service;

/** 统计：顶部概览数字 + 三张分布（栋舍类型 / 品种 / 批次状态） */
@Service
public class StatsService {

    private final HouseRepository houseRepository;
    private final PenRepository penRepository;
    private final PigRepository pigRepository;
    private final BatchRepository batchRepository;
    private final FeedBatchRepository feedBatchRepository;
    private final TransferRepository transferRepository;
    private final FeedingRepository feedingRepository;
    private final VaccineRecordRepository vaccineRepository;
    private final TraceRecordRepository traceRepository;

    public StatsService(HouseRepository houseRepository, PenRepository penRepository, PigRepository pigRepository,
                        BatchRepository batchRepository, FeedBatchRepository feedBatchRepository,
                        TransferRepository transferRepository, FeedingRepository feedingRepository,
                        VaccineRecordRepository vaccineRepository, TraceRecordRepository traceRepository) {
        this.houseRepository = houseRepository;
        this.penRepository = penRepository;
        this.pigRepository = pigRepository;
        this.batchRepository = batchRepository;
        this.feedBatchRepository = feedBatchRepository;
        this.transferRepository = transferRepository;
        this.feedingRepository = feedingRepository;
        this.vaccineRepository = vaccineRepository;
        this.traceRepository = traceRepository;
    }

    public OverviewVO overview() {
        List<Pen> pens = penRepository.findAllByOrderByPenCodeAsc();
        int capacity = 0;
        int pigCount = 0;
        for (Pen pen : pens) {
            capacity += pen.getCapacity() == null ? 0 : pen.getCapacity();
            pigCount += pen.getPigCount() == null ? 0 : pen.getPigCount();
        }
        OverviewVO vo = new OverviewVO();
        vo.setHouseCount(houseRepository.count());
        vo.setPenCount(penRepository.count());
        vo.setPigCount(pigRepository.count());
        vo.setOnFarmCount(pigRepository.countByStatus("ON_FARM") + pigRepository.countByStatus("TRANSFERRED"));
        vo.setBatchCount(batchRepository.count());
        vo.setFeedBatchCount(feedBatchRepository.count());
        vo.setTransferCount(transferRepository.count());
        vo.setFeedingCount(feedingRepository.count());
        vo.setVaccineCount(vaccineRepository.count());
        vo.setTraceCount(traceRepository.count());
        vo.setPenCapacity(capacity);
        vo.setPenUsageRate(capacity <= 0 ? 0D : Math.min(1D, pigCount * 1D / capacity));
        return vo;
    }

    public List<HouseTypeStatDTO> houseType() {
        return houseRepository.statByHouseType();
    }

    public List<BreedStatDTO> breed() {
        return pigRepository.statByBreed();
    }

    public List<BatchStatusStatDTO> batchStatus() {
        return batchRepository.statByStatus();
    }
}
