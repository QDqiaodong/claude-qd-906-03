package com.hog.farm.service;

import com.hog.farm.common.BizException;
import com.hog.farm.common.Dict;
import com.hog.farm.domain.House;
import com.hog.farm.domain.HouseStatusLog;
import com.hog.farm.domain.Pen;
import com.hog.farm.domain.Pig;
import com.hog.farm.dto.HouseRequest;
import com.hog.farm.dto.HouseStatusRequest;
import com.hog.farm.dto.PenRequest;
import com.hog.farm.dto.Views.AffectedPigVO;
import com.hog.farm.dto.Views.AnomalyVO;
import com.hog.farm.dto.Views.HouseStatusLogVO;
import com.hog.farm.dto.Views.HouseStatusReviewVO;
import com.hog.farm.dto.Views.HouseVO;
import com.hog.farm.dto.Views.PenVO;
import com.hog.farm.repository.HouseRepository;
import com.hog.farm.repository.HouseStatusLogRepository;
import com.hog.farm.repository.PenRepository;
import com.hog.farm.repository.PigRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 栋舍与栏位状态链路（本类是「栋舍能不能放猪、何时生效、存栏如何安置」的唯一裁决处）。
 *
 * 处置规则（在「检修立即生效、原地照护」与「先腾空再生效」之间的明确选择）：
 * 1. 检修 MAINTAIN：立即生效、原地照护。现有存栏与饲养员保留，饲喂 / 用药 / 免疫照常；
 *    但新建入栏、转入、以及任何「恢复到该栋舍」的入口一致拒绝（{@link #assertCanReceive}）。
 * 2. 停用 DISABLED：先腾空再生效。申请先落到 PENDING_DISABLE 待清空（只准出不准进），
 *    全部猪只迁出且栏位无未完操作（清洗消毒 / 维修）后，才能由「完成停用」生效为 DISABLED。
 * 3. 恢复在用 ACTIVE：重新评估栏位维修状态与实际存栏；遗留的超容、位置不明、计数不符异常
 *    一律保留为待处理异常，恢复操作不静默清除任何一条。
 *
 * 并发：状态切换对 house 行加写锁，猪只进入对 house + 目标 pen 加写锁，
 * 锁序固定 house → pen，因此「状态刚受限却多出新猪」与「同一次切换重复生效」都不可能发生。
 */
@Service
public class HouseService {

    /** 检修：立即生效、原地照护 */
    public static final String MODE_MAINTAIN_IN_PLACE = "MAINTAIN_IN_PLACE";
    /** 停用：先腾空再生效 */
    public static final String MODE_EMPTY_FIRST = "EMPTY_FIRST";
    /** 恢复在用 */
    public static final String MODE_REACTIVATE = "REACTIVATE";

    private final HouseRepository houseRepository;
    private final PenRepository penRepository;
    private final PigRepository pigRepository;
    private final HouseStatusLogRepository statusLogRepository;

    public HouseService(HouseRepository houseRepository, PenRepository penRepository,
                        PigRepository pigRepository, HouseStatusLogRepository statusLogRepository) {
        this.houseRepository = houseRepository;
        this.penRepository = penRepository;
        this.pigRepository = pigRepository;
        this.statusLogRepository = statusLogRepository;
    }

    // ================================================================
    // 列表 / 格子图
    // ================================================================

    public List<House> list() {
        return houseRepository.findAllByOrderByHouseCodeAsc();
    }

    public List<Pen> pens(Long houseId) {
        if (houseId == null) {
            return penRepository.findAllByOrderByPenCodeAsc();
        }
        return penRepository.findByHouseIdOrderByPenCodeAsc(houseId);
    }

    public House get(Long id) {
        return houseRepository.findById(id).orElseThrow(() -> new BizException("栋舍不存在：" + id));
    }

    public Pen getPen(Long id) {
        return penRepository.findById(id).orElseThrow(() -> new BizException("栏位不存在：" + id));
    }

    /** 栋舍平面格子图：栋舍 → 若干栏位格子。存栏一律按台账实测，刷新后与实际状态一致。 */
    public List<HouseVO> grid() {
        List<House> houses = houseRepository.findAllByOrderByHouseCodeAsc();
        List<Pen> allPens = penRepository.findAllByOrderByPenCodeAsc();
        List<Pig> allPigs = pigRepository.findAllByOrderByEarTagAsc();
        List<HouseVO> result = new ArrayList<>();
        for (House house : houses) {
            Audit audit = audit(house, allPens, allPigs);
            result.add(toHouseVO(house, audit));
        }
        return result;
    }

    /** 单个栏位格子（前端点格看存栏时用），存栏按台账实测 */
    public PenVO penVO(Long penId) {
        Pen pen = getPen(penId);
        House house = pen.getHouseId() == null ? null : houseRepository.findById(pen.getHouseId()).orElse(null);
        int actual = (int) pigRepository.findByPenIdOrderByIdAsc(penId).stream()
                .filter(pig -> !Dict.isTerminalPigStatus(pig.getStatus()))
                .count();
        return toPenVO(pen, house, actual);
    }

    // ================================================================
    // 栋舍增改（状态不允许从普通编辑口改，只能走状态切换预检 + 确认）
    // ================================================================

    @Transactional
    public House create(HouseRequest request) {
        request.validateTypeAndStatus();
        if (!StringUtils.hasText(request.getHouseCode())) {
            throw new BizException("栋舍编号不能为空");
        }
        if (!Dict.isValidHouseType(request.getHouseType())) {
            throw new BizException("栋舍类型只能为 配怀 / 分娩 / 保育 / 育肥 / 隔离");
        }
        if (houseRepository.existsByHouseCode(request.getHouseCode())) {
            throw new BizException("栋舍编号已存在：" + request.getHouseCode());
        }
        if (request.getStatus() != null && !"ACTIVE".equals(request.getStatus())) {
            throw new BizException("新建栋舍只能是「在用」，检修 / 停用请在建成后走状态切换");
        }
        House house = new House();
        apply(house, request);
        // 默认值写在 create 里，实体字段不带默认值
        house.setStatus("ACTIVE");
        if (house.getPenCount() == null) {
            house.setPenCount(0);
        }
        if (house.getBuiltDate() == null) {
            house.setBuiltDate(LocalDate.now());
        }
        return houseRepository.save(house);
    }

    @Transactional
    public House update(Long id, HouseRequest request) {
        House house = get(id);
        request.validateTypeAndStatus();
        if (StringUtils.hasText(request.getHouseCode())
                && !request.getHouseCode().equals(house.getHouseCode())
                && houseRepository.existsByHouseCode(request.getHouseCode())) {
            throw new BizException("栋舍编号已存在：" + request.getHouseCode());
        }
        // 只改标签 / 备注等资料不能改变栋舍受限状态；受限状态只能走专用的状态切换链路
        if (request.getStatus() != null && !request.getStatus().equals(house.getStatus())) {
            throw new BizException("栋舍状态不能在编辑资料时直接变更，请使用「检修 / 停用 / 恢复」状态切换（含存栏处置确认）");
        }
        if (request.getHouseCode() != null) {
            house.setHouseCode(request.getHouseCode());
        }
        if (request.getHouseName() != null) {
            house.setHouseName(request.getHouseName());
        }
        if (request.getHouseType() != null) {
            house.setHouseType(request.getHouseType());
        }
        ifNotNull(request.getAreaSqm(), house::setAreaSqm);
        ifNotNull(request.getKeeper(), house::setKeeper);
        ifNotNull(request.getBuiltDate(), house::setBuiltDate);
        ifNotNull(request.getNote(), house::setNote);
        return houseRepository.save(house);
    }

    // ================================================================
    // 状态切换：预检（操作员确认前看到受影响栏位、猪只清单和阻塞原因）
    // ================================================================

    @Transactional(readOnly = true)
    public HouseStatusReviewVO reviewStatus(Long id, String targetStatus) {
        if (!Dict.isValidHouseStatus(targetStatus)) {
            throw new BizException("栋舍状态只能为 在用 / 检修 / 待清空 / 停用：" + targetStatus);
        }
        House house = get(id);
        Audit audit = audit(house);
        return buildReview(house, targetStatus, audit);
    }

    /** 操作员确认后的状态切换（对栋舍行加锁，与猪只进入互斥）。 */
    @Transactional
    public HouseStatusReviewVO changeStatus(Long id, HouseStatusRequest request) {
        if (request == null || !Dict.isValidHouseStatus(request.getStatus())) {
            throw new BizException("栋舍状态只能为 在用 / 检修 / 待清空 / 停用");
        }
        if (!StringUtils.hasText(request.getOperator())) {
            throw new BizException("请填写确认操作的操作员");
        }
        String requestNo = StringUtils.hasText(request.getRequestNo())
                ? request.getRequestNo().trim()
                : "HS-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24).toUpperCase();

        // 先对栋舍行加写锁：序列化同一栋舍的所有状态切换，并与猪只进入互斥
        House house = houseRepository.lockById(id)
                .orElseThrow(() -> new BizException("栋舍不存在：" + id));

        // 锁内做幂等检查：同一次确认的并发重放在锁上等待后会看到已存在的留痕，直接返回、不重复生效
        if (statusLogRepository.existsByRequestKey(requestNo)) {
            Audit done = audit(house);
            HouseStatusReviewVO vo = buildReview(house, house.getStatus(), done);
            vo.getWarnings().add("该确认已提交过，本次为幂等重试，状态未重复生效");
            return vo;
        }

        Audit audit = audit(house);
        String fromStatus = house.getStatus();
        String targetStatus = request.getStatus();
        HouseStatusReviewVO review = buildReview(house, targetStatus, audit);

        if (fromStatus.equals(targetStatus)) {
            // 状态已在目标态：无副作用返回（例如待清空期间重复点「申请停用」）
            review.getWarnings().add("栋舍当前已是「" + Dict.houseStatusName(targetStatus) + "」，无需重复切换");
            return review;
        }
        if (!Boolean.TRUE.equals(review.getCanApply())) {
            throw new BizException("栋舍 " + house.getHouseCode() + " 不能切换为「"
                    + Dict.houseStatusName(targetStatus) + "」：" + String.join("；", review.getBlockers()));
        }

        house.setStatus(targetStatus);
        houseRepository.save(house);

        HouseStatusLog log = new HouseStatusLog();
        log.setHouseId(house.getId());
        log.setHouseCode(house.getHouseCode());
        log.setFromStatus(fromStatus);
        log.setToStatus(targetStatus);
        log.setPigCount(audit.actualTotal);
        log.setOperator(request.getOperator());
        log.setReason(buildLogReason(targetStatus, audit, request.getReason()));
        log.setCreatedAt(LocalDateTime.now());
        log.setRequestKey(requestNo);
        statusLogRepository.save(log);

        // 重新按最新状态构造预检结果返回，前端用它直接刷新
        Audit after = audit(house);
        return buildReview(house, targetStatus, after);
    }

    public List<HouseStatusLogVO> statusLogs(Long houseId) {
        List<HouseStatusLog> logs = houseId == null
                ? statusLogRepository.findAllByOrderByCreatedAtDescIdDesc()
                : statusLogRepository.findByHouseIdOrderByCreatedAtDescIdDesc(houseId);
        List<HouseStatusLogVO> result = new ArrayList<>();
        for (HouseStatusLog log : logs) {
            HouseStatusLogVO vo = new HouseStatusLogVO();
            vo.setId(log.getId());
            vo.setHouseId(log.getHouseId());
            vo.setHouseCode(log.getHouseCode());
            vo.setFromStatus(log.getFromStatus());
            vo.setFromStatusName(Dict.houseStatusName(log.getFromStatus()));
            vo.setToStatus(log.getToStatus());
            vo.setToStatusName(Dict.houseStatusName(log.getToStatus()));
            vo.setPigCount(log.getPigCount());
            vo.setReason(log.getReason());
            vo.setOperator(log.getOperator());
            vo.setCreatedAt(log.getCreatedAt() == null ? null : log.getCreatedAt().toString());
            result.add(vo);
        }
        return result;
    }

    // ================================================================
    // 删除
    // ================================================================

    @Transactional
    public void remove(Long id) {
        House house = get(id);
        List<Pen> pens = penRepository.findByHouseIdOrderByPenCodeAsc(id);
        if (!pens.isEmpty()) {
            throw new BizException("栋舍下仍有 " + pens.size() + " 个栏位，请先删除栏位");
        }
        houseRepository.delete(house);
    }

    // ================================================================
    // 栏位增改删
    // ================================================================

    @Transactional
    public Pen createPen(PenRequest request) {
        if (request.getHouseId() == null) {
            throw new BizException("请选择栏位所属栋舍");
        }
        House house = get(request.getHouseId());
        if (house.isDisabled()) {
            throw new BizException("栋舍 " + house.getHouseCode() + " 已停用，不能再新增栏位");
        }
        if (!StringUtils.hasText(request.getPenCode())) {
            throw new BizException("栏位编号不能为空");
        }
        if (penRepository.existsByPenCode(request.getPenCode())) {
            throw new BizException("栏位编号已存在：" + request.getPenCode());
        }
        if (request.getCapacity() == null || request.getCapacity() <= 0) {
            throw new BizException("栏位容量必须大于 0");
        }
        if (request.getCapacity() > Dict.MAX_PEN_CAPACITY) {
            throw new BizException("单个栏位容量不得超过 " + Dict.MAX_PEN_CAPACITY + " 头");
        }
        if (request.getStatus() != null && !Dict.isValidPenStatus(request.getStatus())) {
            throw new BizException("栏位状态非法：" + request.getStatus());
        }
        Pen pen = new Pen();
        pen.setHouseId(house.getId());
        pen.setPenCode(request.getPenCode());
        pen.setPenName(request.getPenName());
        pen.setCapacity(request.getCapacity());
        pen.setPigCount(0);
        pen.setStatus(request.getStatus() == null ? "NORMAL" : request.getStatus());
        pen.setNote(request.getNote());
        Pen saved = penRepository.save(pen);
        house.setPenCount(nvl(house.getPenCount()) + 1);
        houseRepository.save(house);
        return saved;
    }

    @Transactional
    public Pen updatePen(Long id, PenRequest request) {
        Pen pen = getPen(id);
        if (StringUtils.hasText(request.getPenCode())
                && !request.getPenCode().equals(pen.getPenCode())
                && penRepository.existsByPenCode(request.getPenCode())) {
            throw new BizException("栏位编号已存在：" + request.getPenCode());
        }
        if (request.getCapacity() != null) {
            if (request.getCapacity() <= 0) {
                throw new BizException("栏位容量必须大于 0");
            }
            if (request.getCapacity() > Dict.MAX_PEN_CAPACITY) {
                throw new BizException("单个栏位容量不得超过 " + Dict.MAX_PEN_CAPACITY + " 头");
            }
            // 容量下限按台账实测存栏卡，避免超容被静默掩盖
            int actual = countActualInPen(pen.getId());
            if (request.getCapacity() < actual) {
                throw new BizException("容量不能小于当前实际存栏 " + actual + " 头");
            }
        }
        if (request.getStatus() != null && !Dict.isValidPenStatus(request.getStatus())) {
            throw new BizException("栏位状态非法：" + request.getStatus());
        }
        ifNotNull(request.getPenCode(), pen::setPenCode);
        ifNotNull(request.getPenName(), pen::setPenName);
        ifNotNull(request.getCapacity(), pen::setCapacity);
        ifNotNull(request.getStatus(), pen::setStatus);
        ifNotNull(request.getNote(), pen::setNote);
        Pen saved = penRepository.save(pen);
        refreshPenStatus(saved);
        return penRepository.save(saved);
    }

    @Transactional
    public void removePen(Long id) {
        Pen pen = getPen(id);
        int actual = countActualInPen(pen.getId());
        if (actual > 0 || nvl(pen.getPigCount()) > 0) {
            throw new BizException("栏位内仍有 " + Math.max(actual, nvl(pen.getPigCount())) + " 头猪，不能删除");
        }
        penRepository.delete(pen);
        if (pen.getHouseId() != null) {
            House house = houseRepository.findById(pen.getHouseId()).orElse(null);
            if (house != null) {
                house.setPenCount(Math.max(0, nvl(house.getPenCount()) - 1));
                houseRepository.save(house);
            }
        }
    }

    // ================================================================
    // 猪只进入的唯一裁决口：新建入栏 / 转入 / 恢复到该栋舍都走这里
    // ================================================================

    /**
     * 转入 / 入栏校验。调用方必须已在事务内；这里按固定锁序 house → pen 加写锁后复核，
     * 与栋舍状态切换事务互斥，保证「状态切换与猪只进入几乎同时发生」时结果确定：
     * 状态先生效则进入被拒；进入先生效则状态切换按真实存栏给出待清空结论。
     */
    public void assertCanReceive(Long penId, int heads) {
        if (penId == null) {
            throw new BizException("请选择入栏 / 转入的栏位");
        }
        Pen pen = penRepository.findById(penId)
                .orElseThrow(() -> new BizException("栏位不存在：" + penId));
        House house = null;
        if (pen.getHouseId() != null) {
            // 先锁栋舍行（锁序固定 house → pen），受限状态在这里被拒
            house = houseRepository.lockById(pen.getHouseId()).orElse(null);
        }
        assertHouseReceivable(house);
        // 栋舍校验通过后再锁目标栏位（锁序固定 house → pen），在锁下复核栏位状态与容量
        pen = penRepository.lockById(penId)
                .orElseThrow(() -> new BizException("栏位不存在：" + penId));
        if ("REPAIR".equals(pen.getStatus())) {
            throw new BizException("栏位 " + pen.getPenCode() + " 维修中，不能转入猪只");
        }
        if ("CLEANING".equals(pen.getStatus())) {
            throw new BizException("栏位 " + pen.getPenCode() + " 正在清洗消毒，不能转入猪只");
        }
        int need = heads <= 0 ? 1 : heads;
        // 容量按台账实测存栏复核（不信任可能已不符的 pig_count）
        int actual = countActualInPen(pen.getId());
        int remaining = Math.max(0, nvl(pen.getCapacity()) - actual);
        if (remaining < need) {
            throw new BizException("栏位 " + pen.getPenCode() + " 剩余容量不足：实际在栏 "
                    + actual + " 头、仅剩 " + remaining + " 个位置，本次需要 " + need + " 个");
        }
    }

    /** 新建猪只只登记栋舍（无栏位）时的入口校验：受限栋舍同样拒绝。 */
    public void assertHouseCanReceive(Long houseId) {
        if (houseId == null) {
            return;
        }
        House house = houseRepository.lockById(houseId)
                .orElseThrow(() -> new BizException("栋舍不存在：" + houseId));
        assertHouseReceivable(house);
    }

    private void assertHouseReceivable(House house) {
        if (house == null) {
            return;
        }
        if (house.isMaintain()) {
            throw new BizException("栋舍 " + house.getHouseCode()
                    + " 检修中（立即生效、原地照护）：现有猪只保留并继续照护，但不允许新建入栏、转入或恢复到该栋舍");
        }
        if (house.isPendingDisable()) {
            throw new BizException("栋舍 " + house.getHouseCode()
                    + " 正在停用腾空（待清空）：只准迁出不准进入，请待停用完成或恢复在用后再操作");
        }
        if (house.isDisabled()) {
            throw new BizException("栋舍 " + house.getHouseCode() + " 已停用，其下栏位不允许转入猪只");
        }
    }

    /**
     * 栏位存栏数增减（转栏 / 入栏 / 出栏共用）。走原子 UPDATE（pig_count = pig_count + delta），
     * 不用读-改-写：并发转入时各事务持有的栏位实体是事务开始时的旧值，读-改-写会丢更新，
     * 原子自增在数据库行上串行累加，保证 pig_count 与台账实测始终一致。
     */
    @Transactional
    public void changePenPigCount(Long penId, int delta) {
        if (penId == null || delta == 0) {
            return;
        }
        penRepository.updatePigCount(penId, delta);
    }

    private void refreshPenStatus(Pen pen) {
        if (pen.getCapacity() == null || pen.getCapacity() <= 0) {
            return;
        }
        if ("CLEANING".equals(pen.getStatus()) || "REPAIR".equals(pen.getStatus())) {
            return;
        }
        pen.setStatus(nvl(pen.getPigCount()) >= pen.getCapacity() ? "FULL" : "NORMAL");
    }

    // ================================================================
    // 存栏审计：实际存栏、超容、位置不明、计数不符、栏位未完操作
    // 异常在每次评估时重新计算并保留，恢复在用不会静默清除任何一条。
    // ================================================================

    private Audit audit(House house) {
        // 用全量栏位 / 猪只评估：反向位置不明（猪坐在本栋栏位但 house_id 指向别处）才能发现
        return audit(house, penRepository.findAllByOrderByPenCodeAsc(),
                pigRepository.findAllByOrderByEarTagAsc());
    }

    private Audit audit(House house, List<Pen> allPens, List<Pig> allPigs) {
        Audit audit = new Audit();
        List<Pen> pens = new ArrayList<>();
        for (Pen pen : allPens) {
            if (house.getId().equals(pen.getHouseId())) {
                pens.add(pen);
            }
        }
        audit.pens = pens;

        // 全台账按栏位实测（非终态）。注意 allPens 可能只是本栋栏位，故栏位集合以传入为准。
        Map<Long, List<Pig>> pigsByPen = new LinkedHashMap<>();
        List<Pig> pigsClaimedByHouse = new ArrayList<>();
        for (Pig pig : allPigs) {
            if (pig.getPenId() != null && !Dict.isTerminalPigStatus(pig.getStatus())) {
                pigsByPen.computeIfAbsent(pig.getPenId(), key -> new ArrayList<>()).add(pig);
            }
            if (house.getId().equals(pig.getHouseId())) {
                pigsClaimedByHouse.add(pig);
            }
        }
        Map<Long, Pen> penIndex = new LinkedHashMap<>();
        for (Pen pen : allPens) {
            penIndex.put(pen.getId(), pen);
        }

        // 1) 栋舍登记名下的猪只：无栏位 / 终态占位 / 栏位不属于本栋 → 位置不明
        for (Pig pig : pigsClaimedByHouse) {
            if (Dict.isTerminalPigStatus(pig.getStatus())) {
                audit.add(anomaly("LOCATION_UNKNOWN", "PIG", null, pig,
                        "猪只 " + pig.getEarTag() + " 已是「" + Dict.pigStatusName(pig.getStatus())
                                + "」终态，仍登记在本栋，须核实位置并清出"));
                continue;
            }
            if (pig.getPenId() == null) {
                audit.add(anomaly("LOCATION_UNKNOWN", "PIG", null, pig,
                        "猪只 " + pig.getEarTag() + " 登记在本栋但没有所在栏位，位置不明，须落栏或迁出"));
                continue;
            }
            Pen pen = penIndex.get(pig.getPenId());
            if (pen == null || !house.getId().equals(pen.getHouseId())) {
                audit.add(anomaly("LOCATION_UNKNOWN", "PIG", pen, pig,
                        "猪只 " + pig.getEarTag() + " 的所在栏位 #" + pig.getPenId()
                                + " 不属于本栋或已删除，位置不明，须核实安置"));
                continue;
            }
        }

        int capacityTotal = 0;
        int countTotal = 0;
        int actualTotal = 0;
        int clearedPens = 0;
        int busyPens = 0;
        for (Pen pen : pens) {
            capacityTotal += nvl(pen.getCapacity());
            countTotal += nvl(pen.getPigCount());

            List<Pig> occupants = pigsByPen.getOrDefault(pen.getId(), List.of());
            int actual = occupants.size();
            actualTotal += actual;
            if (actual == 0) {
                clearedPens++;
            }
            audit.actualByPen.put(pen.getId(), actual);

            // 反向位置不明：实际坐在本栋栏位、但栋舍归属不指向本栋
            for (Pig occupant : occupants) {
                if (!house.getId().equals(occupant.getHouseId())) {
                    audit.add(anomaly("LOCATION_UNKNOWN", "PIG", pen, occupant,
                            "猪只 " + occupant.getEarTag() + " 实际坐在 " + pen.getPenCode()
                                    + "，但栋舍归属登记为别处，位置不明，须核实安置"));
                }
            }

            boolean busy = "CLEANING".equals(pen.getStatus()) || "REPAIR".equals(pen.getStatus());
            if (busy) {
                busyPens++;
                audit.add(anomaly("PEN_BUSY", "PEN", pen, null,
                        "栏位 " + pen.getPenCode() + " 处于「" + Dict.penStatusName(pen.getStatus())
                                + "」，停用生效前必须收尾完毕"));
            }
            if (actual > nvl(pen.getCapacity())) {
                audit.add(anomaly("OVER_CAPACITY", "PEN", pen, null,
                        "栏位 " + pen.getPenCode() + " 实际存栏 " + actual + " 头，超过容量 "
                                + pen.getCapacity() + " 头"));
            }
            if (actual != nvl(pen.getPigCount())) {
                audit.add(anomaly("COUNT_MISMATCH", "PEN", pen, null,
                        "栏位 " + pen.getPenCode() + " 计数 " + nvl(pen.getPigCount())
                                + " 与台账实际存栏 " + actual + " 不符，须盘点校正"));
            }
        }

        audit.capacityTotal = capacityTotal;
        audit.countTotal = countTotal;
        audit.actualTotal = actualTotal;
        audit.clearedPenCount = clearedPens;
        audit.busyPenCount = busyPens;
        // 猪只清单用「本栋名下」的完整集合（含位置不明项），预检清单里再过滤终态
        audit.pigs = pigsClaimedByHouse;
        return audit;
    }

    private int countActualInPen(Long penId) {
        return (int) pigRepository.findByPenIdOrderByIdAsc(penId).stream()
                .filter(pig -> !Dict.isTerminalPigStatus(pig.getStatus()))
                .count();
    }

    private AnomalyVO anomaly(String code, String level, Pen pen, Pig pig, String message) {
        AnomalyVO vo = new AnomalyVO();
        vo.setCode(code);
        vo.setCodeName(Dict.houseAnomalyName(code));
        vo.setLevel(level);
        if (pen != null) {
            vo.setPenId(pen.getId());
            vo.setPenCode(pen.getPenCode());
        }
        if (pig != null) {
            vo.setPigId(pig.getId());
            vo.setEarTag(pig.getEarTag());
        }
        vo.setMessage(message);
        return vo;
    }

    // ================================================================
    // 预检组装
    // ================================================================

    private HouseStatusReviewVO buildReview(House house, String target, Audit audit) {
        HouseStatusReviewVO vo = new HouseStatusReviewVO();
        vo.setHouseId(house.getId());
        vo.setHouseCode(house.getHouseCode());
        vo.setHouseName(house.getHouseName());
        vo.setFromStatus(house.getStatus());
        vo.setFromStatusName(Dict.houseStatusName(house.getStatus()));
        vo.setTargetStatus(target);
        vo.setTargetStatusName(Dict.houseStatusName(target));
        vo.setAffectedPenCount(audit.pens.size());
        vo.setAffectedPigCount(audit.actualTotal);
        vo.setBusyPenCount(audit.busyPenCount);
        vo.setAnomalyCount(audit.anomalies.size());
        vo.setClearedPenCount(audit.clearedPenCount);
        vo.setTotalPenCount(audit.pens.size());

        for (Pen pen : audit.pens) {
            vo.getAffectedPens().add(toPenVO(pen, house, audit.actualByPen.getOrDefault(pen.getId(), 0)));
        }
        for (Pig pig : audit.pigs) {
            if (Dict.isTerminalPigStatus(pig.getStatus())) {
                continue;
            }
            AffectedPigVO pigVO = new AffectedPigVO();
            pigVO.setId(pig.getId());
            pigVO.setEarTag(pig.getEarTag());
            pigVO.setBreedName(Dict.breedName(pig.getBreed()));
            pigVO.setStatusName(Dict.pigStatusName(pig.getStatus()));
            pigVO.setPenId(pig.getPenId());
            Pen pen = pig.getPenId() == null ? null : audit.pens.stream()
                    .filter(item -> item.getId().equals(pig.getPenId())).findFirst().orElse(null);
            pigVO.setPenCode(pen == null ? "位置不明" : pen.getPenCode());
            pigVO.setWeightKg(pig.getWeightKg());
            vo.getAffectedPigs().add(pigVO);
        }
        vo.getAnomalies().addAll(audit.anomalies);

        switch (target) {
            case "MAINTAIN" -> fillMaintainReview(house, vo, audit);
            case "PENDING_DISABLE" -> fillPendingDisableReview(house, vo, audit);
            case "DISABLED" -> fillDisabledReview(house, vo, audit);
            case "ACTIVE" -> fillActiveReview(house, vo, audit);
            default -> { }
        }
        return vo;
    }

    /** 检修：立即生效、原地照护。任何非检修态都可申请，不要求腾空，不产生阻塞。 */
    private void fillMaintainReview(House house, HouseStatusReviewVO vo, Audit audit) {
        vo.setDispositionMode(MODE_MAINTAIN_IN_PLACE);
        vo.setDispositionModeName("检修立即生效、原地照护");
        vo.setRuleText("检修立即生效：保留现有存栏与饲养员，饲喂、用药、免疫等照护记录照常；"
                + "生效后冻结一切新增入口（新建入栏、转入、恢复到该栋舍均拒绝），猪只只可迁出。");
        if ("DISABLED".equals(house.getStatus())) {
            vo.getBlockers().add("已停用栋舍不能直接转检修，请先恢复在用");
            vo.setCanApply(false);
            return;
        }
        vo.setCanApply(true);
        if (audit.actualTotal > 0) {
            vo.getWarnings().add("现有 " + audit.actualTotal + " 头猪原地照护，位置与饲养员不变，继续正常饲喂与健康记录");
        }
        if (!audit.anomalies.isEmpty()) {
            vo.getWarnings().add("存在 " + audit.anomalies.size() + " 条存栏异常，检修期间仍保留为待处理项，不会被清除");
        }
    }

    /** 申请停用 → 待清空：立即冻结新增，猪只只准迁出，不要求当下腾空。 */
    private void fillPendingDisableReview(House house, HouseStatusReviewVO vo, Audit audit) {
        vo.setDispositionMode(MODE_EMPTY_FIRST);
        vo.setDispositionModeName("停用：先腾空再生效");
        vo.setRuleText("停用采用「先腾空再生效」：确认后进入待清空，立即冻结新增、只准迁出；"
                + "待全部猪只迁出且栏位无未完操作（清洗消毒 / 维修）后，再执行「完成停用」正式停用。");
        if ("DISABLED".equals(house.getStatus())) {
            vo.getBlockers().add("栋舍已停用，无需再申请停用");
            vo.setCanApply(false);
            return;
        }
        vo.setCanApply(true);
        if (audit.actualTotal > 0) {
            vo.getWarnings().add("生效为待清空后仍有 " + audit.actualTotal + " 头待迁出，已清空栏位 "
                    + audit.clearedPenCount + "/" + audit.pens.size());
        }
        if (audit.busyPenCount > 0) {
            vo.getWarnings().add("有 " + audit.busyPenCount + " 个栏位存在未完操作，正式停用前必须收尾");
        }
    }

    /** 完成停用：仅当无实际存栏、无栏位未完操作时才能生效。 */
    private void fillDisabledReview(House house, HouseStatusReviewVO vo, Audit audit) {
        vo.setDispositionMode(MODE_EMPTY_FIRST);
        vo.setDispositionModeName("停用：先腾空再生效");
        vo.setRuleText("停用生效条件：全部猪只已迁出（实际存栏为 0），且没有清洗消毒 / 维修等栏位未完操作。");
        if (audit.actualTotal > 0) {
            vo.getBlockers().add("仍有 " + audit.actualTotal + " 头猪只未迁出，请先转栏或出栏后再完成停用");
        }
        if (audit.busyPenCount > 0) {
            vo.getBlockers().add("仍有 " + audit.busyPenCount + " 个栏位存在未完操作（清洗消毒 / 维修），请收尾后再完成停用");
        }
        boolean hasLocationIssue = audit.anomalies.stream()
                .anyMatch(item -> "LOCATION_UNKNOWN".equals(item.getCode()));
        if (hasLocationIssue) {
            vo.getBlockers().add("存在位置不明的猪只登记，须先核实并安置，不能带位置不明停用");
        }
        vo.setCanApply(vo.getBlockers().isEmpty());
        if (!vo.getCanApply() && !"PENDING_DISABLE".equals(house.getStatus())) {
            vo.getWarnings().add("可先确认「申请停用」进入待清空，期间只准迁出、冻结新增");
        }
    }

    /** 恢复在用：重新评估栏位维修状态与实际存栏；遗留异常一律保留为待处理异常。 */
    private void fillActiveReview(House house, HouseStatusReviewVO vo, Audit audit) {
        vo.setDispositionMode(MODE_REACTIVATE);
        vo.setDispositionModeName("恢复在用（重新评估）");
        vo.setRuleText("恢复在用会重新评估栏位维修状态与实际存栏；遗留的超容、位置不明、计数不符异常"
                + "一律保留为待处理异常，不会被恢复操作静默清除。");
        if ("ACTIVE".equals(house.getStatus())) {
            vo.setCanApply(true);
            return;
        }
        // 无论从检修、待清空还是停用恢复，都重新评估并保留遗留异常
        vo.setCanApply(true);
        long repairPens = audit.pens.stream().filter(pen -> "REPAIR".equals(pen.getStatus())).count();
        if (repairPens > 0) {
            vo.getWarnings().add("有 " + repairPens + " 个栏位仍在维修：该栏位本身仍拒绝转入，其余栏位恢复接收");
        }
        if (audit.actualTotal > 0) {
            vo.getWarnings().add("恢复后将重新接收新猪；当前实际存栏 " + audit.actualTotal + " 头已重新核算");
        }
        if (!audit.anomalies.isEmpty()) {
            vo.getWarnings().add("保留 " + audit.anomalies.size()
                    + " 条待处理异常（超容 / 位置不明 / 计数不符 / 未完操作），请逐项处置，恢复操作不会清除它们");
        }
    }

    private String buildLogReason(String target, Audit audit, String operatorReason) {
        String prefix = switch (target) {
            case "MAINTAIN" -> "检修立即生效、原地照护；冻结新增，存栏 " + audit.actualTotal + " 头";
            case "PENDING_DISABLE" -> "申请停用、进入待清空；只准迁出，待迁出 " + audit.actualTotal + " 头";
            case "DISABLED" -> "完成停用生效；停用前实际存栏 0、栏位无未完操作";
            case "ACTIVE" -> "恢复在用；重新评估栏位与存栏，遗留异常 " + audit.anomalies.size() + " 条保留";
            default -> "状态切换";
        };
        return StringUtils.hasText(operatorReason) ? prefix + "；" + operatorReason.trim() : prefix;
    }

    // ================================================================
    // VO 组装
    // ================================================================

    public HouseVO toHouseVO(House house, Audit audit) {
        HouseVO vo = new HouseVO();
        vo.setId(house.getId());
        vo.setHouseCode(house.getHouseCode());
        vo.setHouseName(house.getHouseName());
        vo.setHouseType(house.getHouseType());
        vo.setHouseTypeName(Dict.houseTypeName(house.getHouseType()));
        vo.setHouseTypeColor(Dict.houseTypeColor(house.getHouseType()));
        vo.setAreaSqm(house.getAreaSqm());
        vo.setKeeper(house.getKeeper());
        vo.setBuiltDate(str(house.getBuiltDate()));
        vo.setPenCount(nvl(house.getPenCount()));
        vo.setStatus(house.getStatus());
        vo.setStatusName(Dict.houseStatusName(house.getStatus()));
        vo.setStatusColor(Dict.houseStatusColor(house.getStatus()));
        vo.setNote(house.getNote());
        vo.setCapacity(audit.capacityTotal);
        vo.setPigCount(audit.countTotal);
        vo.setActualPigCount(audit.actualTotal);
        vo.setUsageRate(audit.capacityTotal <= 0 ? 0D
                : Math.min(1D, audit.actualTotal * 1D / audit.capacityTotal));
        vo.setRestricted(house.isRestricted());
        vo.setMaintain(house.isMaintain());
        vo.setPendingDisable(house.isPendingDisable());
        vo.setDisabled(house.isDisabled());
        vo.setClearedPenCount(audit.clearedPenCount);
        vo.setPendingMoveCount(house.isPendingDisable() || house.isMaintain() ? audit.actualTotal : 0);
        vo.setRestrictReason(buildRestrictReason(house, audit));
        vo.setAnomalies(audit.anomalies);
        for (Pen pen : audit.pens) {
            vo.getPens().add(toPenVO(pen, house, audit.actualByPen.getOrDefault(pen.getId(), 0)));
        }
        return vo;
    }

    private String buildRestrictReason(House house, Audit audit) {
        if (house.isMaintain()) {
            return "检修中：冻结新增（入栏 / 转入 / 恢复均拒绝），" + audit.actualTotal + " 头原地照护、饲喂与健康记录照常";
        }
        if (house.isPendingDisable()) {
            return "待清空：只准迁出不准进入，仍有 " + audit.actualTotal + " 头待迁出、"
                    + audit.busyPenCount + " 个栏位未完操作；清空 " + audit.clearedPenCount
                    + "/" + audit.pens.size() + " 栏";
        }
        if (house.isDisabled()) {
            if (audit.actualTotal > 0 || audit.busyPenCount > 0) {
                return "已停用但检出遗留存栏 / 未完操作（待处理异常），停止一切新增入口";
            }
            return "已停用：停止一切新增入口";
        }
        return null;
    }

    public PenVO toPenVO(Pen pen, House house) {
        return toPenVO(pen, house, null);
    }

    public PenVO toPenVO(Pen pen, House house, Integer actualCount) {
        PenVO vo = new PenVO();
        vo.setId(pen.getId());
        vo.setHouseId(pen.getHouseId());
        vo.setHouseCode(house == null ? "" : house.getHouseCode());
        vo.setHouseName(house == null ? "" : house.getHouseName());
        vo.setPenCode(pen.getPenCode());
        vo.setPenName(pen.getPenName());
        vo.setCapacity(nvl(pen.getCapacity()));
        vo.setPigCount(nvl(pen.getPigCount()));
        int actual = actualCount == null ? nvl(pen.getPigCount()) : actualCount;
        vo.setActualPigCount(actual);
        vo.setRemaining(Math.max(0, nvl(pen.getCapacity()) - actual));
        vo.setUsageRate(nvl(pen.getCapacity()) <= 0 ? 0D
                : Math.min(1.0, actual * 1.0 / nvl(pen.getCapacity())));
        vo.setStatus(pen.getStatus());
        vo.setStatusName(Dict.penStatusName(pen.getStatus()));
        vo.setBusy("CLEANING".equals(pen.getStatus()) || "REPAIR".equals(pen.getStatus()));
        vo.setOverCapacity(actual > nvl(pen.getCapacity()));
        vo.setCountMismatch(actual != nvl(pen.getPigCount()));
        vo.setNote(pen.getNote());
        return vo;
    }

    private void apply(House house, HouseRequest request) {
        house.setHouseCode(request.getHouseCode());
        house.setHouseName(request.getHouseName());
        house.setHouseType(request.getHouseType());
        house.setAreaSqm(request.getAreaSqm());
        house.setKeeper(request.getKeeper());
        house.setBuiltDate(request.getBuiltDate());
        house.setStatus(request.getStatus());
        house.setNote(request.getNote());
    }

    private static <T> void ifNotNull(T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }

    private static int nvl(Integer value) {
        return value == null ? 0 : value;
    }

    private static String str(LocalDate date) {
        return date == null ? null : date.toString();
    }

    /** 一次存栏审计的中间结果 */
    private static final class Audit {
        private List<Pen> pens = new ArrayList<>();
        private List<Pig> pigs = new ArrayList<>();
        private Map<Long, Integer> actualByPen = new LinkedHashMap<>();
        private List<AnomalyVO> anomalies = new ArrayList<>();
        private int capacityTotal;
        private int countTotal;
        private int actualTotal;
        private int clearedPenCount;
        private int busyPenCount;

        private void add(AnomalyVO vo) {
            anomalies.add(vo);
        }
    }
}
