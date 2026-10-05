package com.hog.farm.service;

import com.hog.farm.common.BizException;
import com.hog.farm.common.Dict;
import com.hog.farm.domain.Batch;
import com.hog.farm.domain.FeedBatch;
import com.hog.farm.domain.House;
import com.hog.farm.domain.Pig;
import com.hog.farm.domain.TraceRecord;
import com.hog.farm.domain.TraceSource;
import com.hog.farm.domain.VaccineRecord;
import com.hog.farm.dto.TraceRequest;
import com.hog.farm.dto.TraceRequest.TargetRef;
import com.hog.farm.dto.VaccineRequest;
import com.hog.farm.dto.Views.TargetVO;
import com.hog.farm.dto.Views.TraceVO;
import com.hog.farm.dto.Views.VaccineVO;
import com.hog.farm.repository.BatchRepository;
import com.hog.farm.repository.FeedBatchRepository;
import com.hog.farm.repository.HouseRepository;
import com.hog.farm.repository.PigRepository;
import com.hog.farm.repository.TraceRecordRepository;
import com.hog.farm.repository.VaccineRecordRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 防疫免疫与溯源。
 *
 * <p>溯源记录是本仓的 {@code @ManyToAny} 落点：一条 TraceRecord 通过集合表 trace_target
 * 同时持有若干个「任意类型的来源对象」（猪只 / 出栏批次 / 栋舍 / 饲料批次）。
 * 写入走 {@code record.getTargets().add(实体)}，读取走 {@code record.getTargets()} 再按实体类型还原标签。</p>
 */
@Service
public class TraceService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final TraceRecordRepository traceRepository;
    private final VaccineRecordRepository vaccineRepository;
    private final PigRepository pigRepository;
    private final BatchRepository batchRepository;
    private final HouseRepository houseRepository;
    private final FeedBatchRepository feedBatchRepository;
    private final com.hog.farm.repository.ImmunePlanRepository immunePlanRepository;

    public TraceService(TraceRecordRepository traceRepository, VaccineRecordRepository vaccineRepository,
                        PigRepository pigRepository, BatchRepository batchRepository,
                        HouseRepository houseRepository, FeedBatchRepository feedBatchRepository,
                        com.hog.farm.repository.ImmunePlanRepository immunePlanRepository) {
        this.traceRepository = traceRepository;
        this.vaccineRepository = vaccineRepository;
        this.pigRepository = pigRepository;
        this.batchRepository = batchRepository;
        this.houseRepository = houseRepository;
        this.feedBatchRepository = feedBatchRepository;
        this.immunePlanRepository = immunePlanRepository;
    }

    // ---------------- 免疫 ----------------

    public List<VaccineVO> listVaccines() {
        List<VaccineVO> result = new ArrayList<>();
        for (VaccineRecord record : vaccineRepository.findAllByOrderByVaccineDateDescIdDesc()) {
            result.add(toVaccineVO(record));
        }
        return result;
    }

    @Transactional
    public VaccineVO createVaccine(VaccineRequest request) {
        if (request == null || request.getPigId() == null) {
            throw new BizException("请选择被免疫的猪只");
        }
        if (!StringUtils.hasText(request.getVaccineName())) {
            throw new BizException("疫苗名称不能为空");
        }
        if (request.getDoseMl() == null || request.getDoseMl().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("免疫剂量必须大于 0");
        }
        if (request.getDoseMl().compareTo(Dict.MAX_DOSE_ML) > 0) {
            throw new BizException("单次免疫剂量不得超过 " + Dict.MAX_DOSE_ML + "ml");
        }
        if (request.getVaccineDate() == null) {
            throw new BizException("免疫日期不能为空");
        }
        if (request.getVaccineDate().isAfter(LocalDate.now())) {
            throw new BizException("免疫日期不能晚于今天：" + request.getVaccineDate());
        }
        if (request.getNextDate() != null && !request.getNextDate().isAfter(request.getVaccineDate())) {
            throw new BizException("下次免疫日期必须晚于本次免疫日期");
        }
        Pig pig = pigRepository.findById(request.getPigId())
                .orElseThrow(() -> new BizException("猪只不存在：" + request.getPigId()));
        if (Dict.isTerminalPigStatus(pig.getStatus())) {
            throw new BizException("猪只 " + pig.getEarTag() + " 已"
                    + Dict.pigStatusName(pig.getStatus()) + "，不能再做免疫");
        }
        VaccineRecord record = new VaccineRecord();
        record.setPigId(pig.getId());
        record.setEarTag(pig.getEarTag());
        record.setVaccineName(request.getVaccineName());
        record.setDoseMl(request.getDoseMl());
        record.setVaccineDate(request.getVaccineDate());
        record.setNextDate(request.getNextDate());
        record.setOperator(request.getOperator());
        record.setRemark(request.getRemark());
        record.setCreatedAt(LocalDateTime.now());
        return toVaccineVO(vaccineRepository.save(record));
    }

    // ---------------- 溯源（@ManyToAny） ----------------

    /**
     * 时间线：按事件时间倒序，每个节点带多态来源标签。
     * @ManyToAny 集合在 Hibernate 6.5 里是懒加载的，这里开只读事务，
     * 保证把 targets 还原成来源标签时 Session 还在。
     */
    @Transactional(readOnly = true)
    public List<TraceVO> listTraces(String traceType) {
        List<TraceRecord> records = StringUtils.hasText(traceType)
                ? traceRepository.findByTraceTypeOrderByEventTimeDescIdDesc(traceType)
                : traceRepository.findAllByOrderByEventTimeDescIdDesc();
        List<TraceVO> result = new ArrayList<>();
        for (TraceRecord record : records) {
            result.add(toTraceVO(record));
        }
        return result;
    }

    /**
     * 新建溯源记录：把真实存在的来源实体塞进 @ManyToAny 集合后保存。
     * 规则：至少挂一个来源对象；来源类型必须是四种之一；来源对象必须真实存在。
     */
    @Transactional
    public TraceRecord createTrace(TraceRequest request) {
        if (request == null) {
            throw new BizException("溯源内容不能为空");
        }
        if (!Dict.isValidTraceType(request.getTraceType())) {
            throw new BizException("溯源事件类型只能是 免疫 / 转栏 / 饲喂 / 出栏 / 死亡 / 检疫");
        }
        if (request.getTargets() == null || request.getTargets().isEmpty()) {
            throw new BizException("溯源记录必须至少挂一个来源对象");
        }
        String traceNo = StringUtils.hasText(request.getTraceNo())
                ? request.getTraceNo().trim()
                : nextTraceNo();
        if (traceRepository.existsByTraceNo(traceNo)) {
            throw new BizException("溯源单号已存在：" + traceNo);
        }

        List<TraceSource> sources = new ArrayList<>();
        for (TargetRef ref : request.getTargets()) {
            sources.add(resolveSource(ref));
        }

        TraceRecord record = new TraceRecord();
        record.setTraceNo(traceNo);
        record.setTraceType(request.getTraceType());
        record.setEventTitle(StringUtils.hasText(request.getEventTitle())
                ? request.getEventTitle() : Dict.traceTypeName(request.getTraceType()) + "事件");
        record.setEventDesc(request.getEventDesc());
        record.setOperator(request.getOperator());
        record.setEventTime(request.getEventTime() == null ? LocalDateTime.now() : request.getEventTime());
        // 走 @ManyToAny：集合里放的是真实实体，Hibernate 按判别列写入 trace_target
        record.setTargets(sources);
        return traceRepository.save(record);
    }

    /** 单条溯源记录（带多态来源标签） */
    @Transactional(readOnly = true)
    public TraceVO traceVO(Long id) {
        TraceRecord record = traceRepository.findById(id)
                .orElseThrow(() -> new BizException("溯源记录不存在：" + id));
        return toTraceVO(record);
    }

    /** 追加来源对象：在已有溯源记录上再挂一个多态来源 */
    @Transactional
    public TraceRecord attachTarget(Long id, TargetRef ref) {
        TraceRecord record = traceRepository.findById(id)
                .orElseThrow(() -> new BizException("溯源记录不存在：" + id));
        TraceSource source = resolveSource(ref);
        for (TraceSource existing : record.getTargets()) {
            if (existing.getClass().equals(source.getClass()) && source.getId().equals(existing.getId())) {
                throw new BizException("该来源对象已经挂在当前溯源记录上");
            }
        }
        record.getTargets().add(source);
        return traceRepository.save(record);
    }

    /** 按类型解析并校验来源对象真实存在 */
    private TraceSource resolveSource(TargetRef ref) {
        if (ref == null || !Dict.isValidTargetType(ref.getTargetType())) {
            throw new BizException("来源类型只能是 猪只 / 出栏批次 / 栋舍 / 饲料批次");
        }
        if (ref.getTargetId() == null) {
            throw new BizException("来源对象 id 不能为空");
        }
        Long id = ref.getTargetId();
        switch (ref.getTargetType()) {
            case "PIG":
                return pigRepository.findById(id)
                        .orElseThrow(() -> new BizException("来源对象不存在：猪只 #" + id));
            case "BATCH":
                return batchRepository.findById(id)
                        .orElseThrow(() -> new BizException("来源对象不存在：出栏批次 #" + id));
            case "HOUSE":
                return houseRepository.findById(id)
                        .orElseThrow(() -> new BizException("来源对象不存在：栋舍 #" + id));
            case "FEED_BATCH":
                return feedBatchRepository.findById(id)
                        .orElseThrow(() -> new BizException("来源对象不存在：饲料批次 #" + id));
            default:
                throw new BizException("来源类型只能是 猪只 / 出栏批次 / 栋舍 / 饲料批次");
        }
    }

    /** 供前端下拉的候选来源对象 */
    public List<TargetVO> targetOptions(String targetType) {
        List<TargetVO> result = new ArrayList<>();
        if ("PIG".equals(targetType)) {
            for (Pig pig : pigRepository.findAllByOrderByEarTagAsc()) {
                result.add(buildTargetVO("PIG", pig.getId(), pig.getTraceCode(), pig.getTraceName()));
            }
        } else if ("BATCH".equals(targetType)) {
            for (Batch batch : batchRepository.findAllByOrderByBatchNoAsc()) {
                result.add(buildTargetVO("BATCH", batch.getId(), batch.getTraceCode(), batch.getTraceName()));
            }
        } else if ("HOUSE".equals(targetType)) {
            for (House house : houseRepository.findAllByOrderByHouseCodeAsc()) {
                result.add(buildTargetVO("HOUSE", house.getId(), house.getTraceCode(), house.getTraceName()));
            }
        } else if ("FEED_BATCH".equals(targetType)) {
            for (FeedBatch feedBatch : feedBatchRepository.findAllByOrderByFeedNoAsc()) {
                result.add(buildTargetVO("FEED_BATCH", feedBatch.getId(), feedBatch.getTraceCode(),
                        feedBatch.getTraceName()));
            }
        }
        return result;
    }

    private String nextTraceNo() {
        String prefix = "TR-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMddHHmmss")) + "-";
        String no = prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        while (traceRepository.existsByTraceNo(no)) {
            no = prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        }
        return no;
    }

    private TraceVO toTraceVO(TraceRecord record) {
        TraceVO vo = new TraceVO();
        vo.setId(record.getId());
        vo.setTraceNo(record.getTraceNo());
        vo.setTraceType(record.getTraceType());
        vo.setTraceTypeName(Dict.traceTypeName(record.getTraceType()));
        vo.setTraceTypeColor(Dict.traceTypeColor(record.getTraceType()));
        vo.setEventTitle(record.getEventTitle());
        vo.setEventDesc(record.getEventDesc());
        vo.setOperator(record.getOperator());
        vo.setEventTime(record.getEventTime() == null ? null : record.getEventTime().toString());
        vo.setDay(record.getEventTime() == null ? "" : record.getEventTime().format(DAY));
        // 读：@ManyToAny 集合里已经是真实实体，按实体类型还原来源标签
        for (TraceSource source : record.getTargets()) {
            String type = source instanceof Pig ? "PIG"
                    : source instanceof Batch ? "BATCH"
                    : source instanceof House ? "HOUSE" : "FEED_BATCH";
            vo.getTargets().add(buildTargetVO(type, source.getId(), source.getTraceCode(), source.getTraceName()));
        }
        return vo;
    }

    private VaccineVO toVaccineVO(VaccineRecord record) {
        VaccineVO vo = new VaccineVO();
        vo.setId(record.getId());
        vo.setPigId(record.getPigId());
        vo.setEarTag(record.getEarTag());
        vo.setVaccineName(record.getVaccineName());
        vo.setDoseMl(record.getDoseMl());
        vo.setVaccineDate(record.getVaccineDate() == null ? null : record.getVaccineDate().toString());
        vo.setNextDate(record.getNextDate() == null ? null : record.getNextDate().toString());
        vo.setOperator(record.getOperator());
        vo.setRemark(record.getRemark());
        vo.setOverdue(Boolean.valueOf(record.isOverdue()));
        vo.setPlanId(record.getPlanId());
        vo.setDoseNo(record.getDoseNo());
        if (record.getPlanId() != null) {
            vo.setPlanCode(immunePlanRepository.findById(record.getPlanId())
                    .map(com.hog.farm.domain.ImmunePlan::getPlanCode).orElse(""));
        }
        return vo;
    }

    private static TargetVO buildTargetVO(String type, Long id, String code, String name) {
        TargetVO vo = new TargetVO();
        vo.setTargetType(type);
        vo.setTargetTypeName(Dict.targetTypeName(type));
        vo.setTargetId(id);
        vo.setTargetCode(code);
        vo.setTargetName(name);
        return vo;
    }
}
