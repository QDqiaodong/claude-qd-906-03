-- 规模化养猪场 · 栋舍与出栏批次管理
-- 库名 hog_farm；application.yml 里 ddl-auto: none，表结构全部由本文件在 MySQL 镜像构建期导入。
-- 关键：镜像构建期跑 initdb 时 mysql 客户端默认 latin1，会把 UTF-8 中文存成双编码，
--       因此每个 .sql 文件的第一句必须是 SET NAMES utf8mb4。
SET NAMES utf8mb4;
SET character_set_client = utf8mb4;
SET character_set_connection = utf8mb4;
SET character_set_results = utf8mb4;

CREATE DATABASE IF NOT EXISTS hog_farm
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE hog_farm;

SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS trace_target;
DROP TABLE IF EXISTS trace_record;
DROP TABLE IF EXISTS shipment_order;
DROP TABLE IF EXISTS medication_record;
DROP TABLE IF EXISTS immune_task;
DROP TABLE IF EXISTS immune_plan;
DROP TABLE IF EXISTS vaccine_record;
DROP TABLE IF EXISTS feeding;
DROP TABLE IF EXISTS transfer;
DROP TABLE IF EXISTS feed_batch;
DROP TABLE IF EXISTS batch;
DROP TABLE IF EXISTS pig;
DROP TABLE IF EXISTS pen;
DROP TABLE IF EXISTS house_status_log;
DROP TABLE IF EXISTS house;
SET FOREIGN_KEY_CHECKS = 1;

-- ---------------- 栋舍 ----------------
-- 类型：GESTATION 配怀 / FARROWING 分娩 / NURSERY 保育 / FINISHING 育肥 / ISOLATION 隔离
-- 状态：ACTIVE 在用 / MAINTAIN 检修（立即生效、原地照护，冻结新增）
--       PENDING_DISABLE 待清空（停用申请已受理，先腾空、只准迁出）/ DISABLED 停用（腾空收尾后才生效）
CREATE TABLE house (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    house_code VARCHAR(32) NOT NULL UNIQUE,
    house_name VARCHAR(64),
    house_type VARCHAR(24) NOT NULL,
    area_sqm DECIMAL(10,2),
    keeper VARCHAR(32),
    built_date DATE,
    pen_count INT DEFAULT 0,
    status VARCHAR(16),
    note VARCHAR(128)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 栋舍状态切换日志 ----------------
-- 每次生效的切换都留痕（安置结论可追踪）；request_key 唯一，同一次确认不能重复生效。
CREATE TABLE house_status_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    house_id BIGINT NOT NULL,
    house_code VARCHAR(32),
    from_status VARCHAR(16),
    to_status VARCHAR(16),
    pig_count INT,
    reason VARCHAR(255),
    operator VARCHAR(32),
    created_at DATETIME,
    request_key VARCHAR(128) NOT NULL UNIQUE,
    KEY idx_house_status_log (house_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 栏位 ----------------
-- capacity 必须 > 0；pig_count 由转栏 / 入栏 / 出栏维护
CREATE TABLE pen (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    house_id BIGINT NOT NULL,
    pen_code VARCHAR(32) NOT NULL UNIQUE,
    pen_name VARCHAR(64),
    capacity INT NOT NULL,
    pig_count INT DEFAULT 0,
    status VARCHAR(16),
    note VARCHAR(128),
    KEY idx_pen_house (house_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 猪只个体 ----------------
-- 品种 5 选 1；状态 ON_FARM 在场 / TRANSFERRED 转栏 / SOLD 出栏 / DEAD 死亡 / CULLED 淘汰
CREATE TABLE pig (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    ear_tag VARCHAR(32) NOT NULL UNIQUE,
    breed VARCHAR(24) NOT NULL,
    gender VARCHAR(8),
    birth_date DATE NOT NULL,
    source_type VARCHAR(16),
    pen_id BIGINT,
    house_id BIGINT,
    batch_id BIGINT,
    status VARCHAR(16),
    weight_kg DECIMAL(8,2),
    status_date DATE,
    note VARCHAR(128),
    KEY idx_pig_pen (pen_id),
    KEY idx_pig_batch (batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 出栏批次 ----------------
-- 状态 BUILDING 在建 → RAISING 在养 → SELLING 出栏中 → CLEARED 已清栏
CREATE TABLE batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no VARCHAR(32) NOT NULL UNIQUE,
    batch_name VARCHAR(64),
    house_id BIGINT,
    breed VARCHAR(24),
    intake_count INT NOT NULL,
    sold_count INT DEFAULT 0,
    dead_count INT DEFAULT 0,
    start_date DATE,
    expect_date DATE,
    status VARCHAR(16),
    buyer VARCHAR(64),
    avg_weight_kg DECIMAL(8,2),
    note VARCHAR(128)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 饲料批次 ----------------
-- 它同时是溯源记录 @ManyToAny 的四种可选来源之一
CREATE TABLE feed_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    feed_no VARCHAR(32) NOT NULL UNIQUE,
    feed_name VARCHAR(64) NOT NULL,
    feed_type VARCHAR(24),
    supplier VARCHAR(64),
    quantity_kg DECIMAL(10,2),
    produce_date DATE,
    expire_date DATE,
    status VARCHAR(16),
    note VARCHAR(128)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 转栏记录 ----------------
CREATE TABLE transfer (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    pig_id BIGINT NOT NULL,
    ear_tag VARCHAR(32),
    from_pen_id BIGINT,
    to_pen_id BIGINT NOT NULL,
    transfer_date DATE NOT NULL,
    reason VARCHAR(24),
    operator VARCHAR(32),
    remark VARCHAR(128),
    created_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 饲喂记录 ----------------
CREATE TABLE feeding (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    feed_batch_id BIGINT,
    pen_id BIGINT,
    feed_name VARCHAR(64) NOT NULL,
    feed_type VARCHAR(24),
    amount_kg DECIMAL(10,2) NOT NULL,
    pig_count INT,
    feed_time DATETIME,
    operator VARCHAR(32),
    remark VARCHAR(128)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 免疫记录 ----------------
-- plan_id / task_id / dose_no / request_no：由免疫计划确认或补种时回填（手工登记为 NULL）；
-- task_id 唯一：同一接种任务重试不会产生重复免疫记录
CREATE TABLE vaccine_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    pig_id BIGINT NOT NULL,
    ear_tag VARCHAR(32),
    vaccine_name VARCHAR(64) NOT NULL,
    dose_ml DECIMAL(8,2) NOT NULL,
    vaccine_date DATE NOT NULL,
    next_date DATE,
    operator VARCHAR(32),
    remark VARCHAR(128),
    created_at DATETIME,
    plan_id BIGINT,
    task_id BIGINT,
    dose_no INT,
    request_no VARCHAR(48),
    UNIQUE KEY uk_vaccine_task (task_id),
    KEY idx_vaccine_request (request_no),
    KEY idx_vaccine_pig (pig_id, vaccine_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 免疫计划规则 ----------------
-- 按 疫苗 + 适用生长阶段 + 首剂日龄 + 剂次间隔 + 宽限天数 定义；
-- 第 N 剂计划日期 = 出生日期 + 首剂日龄 + (N-1) × 剂次间隔；超过 计划日期 + 宽限天数 仍未接种 → 漏种
CREATE TABLE immune_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_code VARCHAR(32) NOT NULL UNIQUE,
    vaccine_name VARCHAR(64) NOT NULL,
    stage VARCHAR(16) NOT NULL,
    first_dose_age_days INT NOT NULL,
    dose_interval_days INT NOT NULL,
    total_doses INT NOT NULL,
    grace_days INT NOT NULL,
    dose_ml DECIMAL(8,2) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    note VARCHAR(128)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 接种任务 ----------------
-- 执行口径：生成时名单快照。任务挂在猪只上，转栏不丢；
-- 死亡 / 出栏 / 淘汰 / 已有等效接种记录 → TERMINATED（终止原因与时间可追溯）；
-- uk_task_dose：每头猪、同一计划和剂次只能存在一个任务，完成结果唯一。
CREATE TABLE immune_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id BIGINT NOT NULL,
    pig_id BIGINT NOT NULL,
    ear_tag VARCHAR(32),
    dose_no INT NOT NULL,
    planned_date DATE NOT NULL,
    grace_days INT NOT NULL,
    pen_id BIGINT,
    basis VARCHAR(255),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    terminate_reason VARCHAR(24),
    terminated_at DATETIME,
    make_up_date DATE,
    make_up_reason VARCHAR(128),
    completed_date DATE,
    completed_at DATETIME,
    vaccine_record_id BIGINT,
    request_no VARCHAR(48),
    created_at DATETIME,
    UNIQUE KEY uk_task_dose (plan_id, pig_id, dose_no),
    KEY idx_task_status (status, planned_date),
    KEY idx_task_pig (pig_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 用药记录与休药期 ----------------
-- 撤销为软删除；纠错会软撤销旧记录并写入 supersedes_id 指向的新记录。
CREATE TABLE medication_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_no VARCHAR(48) NOT NULL UNIQUE,
    pig_id BIGINT NOT NULL,
    ear_tag VARCHAR(32),
    medicine_name VARCHAR(64) NOT NULL,
    dose VARCHAR(64),
    medication_date DATE NOT NULL,
    withdrawal_days INT NOT NULL,
    operator VARCHAR(32),
    remark VARCHAR(128),
    created_at DATETIME,
    supersedes_id BIGINT,
    revoked TINYINT(1) NOT NULL DEFAULT 0,
    revoked_at DATETIME,
    revoke_reason VARCHAR(128),
    trace_no VARCHAR(32),
    KEY idx_med_pig (pig_id, revoked, medication_date),
    KEY idx_med_trace (trace_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 猪只级出栏放行单 ----------------
CREATE TABLE shipment_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_no VARCHAR(48) NOT NULL UNIQUE,
    batch_id BIGINT NOT NULL,
    release_mode VARCHAR(16) NOT NULL,
    ship_date DATE NOT NULL,
    released_count INT NOT NULL,
    released_pig_ids VARCHAR(2000),
    operator VARCHAR(32),
    buyer VARCHAR(64),
    trace_no VARCHAR(32),
    created_at DATETIME,
    KEY idx_ship_batch (batch_id),
    KEY idx_ship_trace (trace_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 溯源 / 事件记录（本仓后端主线：@ManyToAny） ----------------
CREATE TABLE trace_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trace_no VARCHAR(32) NOT NULL UNIQUE,
    trace_type VARCHAR(32) NOT NULL,
    event_title VARCHAR(64),
    event_desc VARCHAR(255),
    operator VARCHAR(32),
    event_time DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- @ManyToAny 的集合表：一条溯源记录可以挂若干个任意类型的来源对象
-- target_type：PIG 猪只 / BATCH 出栏批次 / HOUSE 栋舍 / FEED_BATCH 饲料批次
CREATE TABLE trace_target (
    trace_record_id BIGINT NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_id BIGINT NOT NULL,
    KEY idx_target_ref (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 种子数据 ----------------

INSERT INTO house (house_code, house_name, house_type, area_sqm, keeper, built_date, pen_count, status, note) VALUES
('HS-A01', '一号配怀舍', 'GESTATION', 860.00, '王建国', '2021-03-18', 2, 'ACTIVE', '限位栏饲养，配种后 30 天转入'),
('HS-A02', '二号配怀舍', 'GESTATION', 780.00, '王建国', '2021-05-06', 2, 'ACTIVE', '后备母猪配种区'),
('HS-B01', '分娩一舍',   'FARROWING', 920.00, '李秀兰', '2020-11-02', 2, 'ACTIVE', '产床 24 张，全进全出'),
('HS-B02', '分娩二舍',   'FARROWING', 880.00, '李秀兰', '2021-08-14', 2, 'MAINTAIN', '产床加热线检修中'),
('HS-C01', '保育一舍',   'NURSERY',   640.00, '张海涛', '2022-01-09', 2, 'ACTIVE', '断奶仔猪 21 日龄转入'),
('HS-D01', '育肥一舍',   'FINISHING', 1200.00, '陈立军', '2020-06-21', 3, 'ACTIVE', '自由采食，自动清粪'),
('HS-D02', '育肥二舍',   'FINISHING', 1150.00, '陈立军', '2021-02-27', 2, 'ACTIVE', '大栏饲养，风机水帘降温'),
('HS-E01', '隔离观察舍', 'ISOLATION', 320.00, '赵德明', '2022-09-15', 1, 'PENDING_DISABLE', '已申请停用、待清空：观察猪迁出后完成停用，期间禁止转入');

INSERT INTO pen (house_id, pen_code, pen_name, capacity, pig_count, status, note) VALUES
(1, 'HS-A01-01', 'A01 第 1 栏', 6, 3, 'NORMAL', '妊娠前期'),
(1, 'HS-A01-02', 'A01 第 2 栏', 6, 0, 'NORMAL', '空栏待配'),
(2, 'HS-A02-01', 'A02 第 1 栏', 6, 2, 'NORMAL', '后备母猪'),
(2, 'HS-A02-02', 'A02 第 2 栏', 6, 0, 'CLEANING', '冲洗消毒中'),
(3, 'HS-B01-01', 'B01 第 1 栏', 1, 1, 'FULL', '待产母猪'),
(3, 'HS-B01-02', 'B01 第 2 栏', 1, 0, 'CLEANING', '产后空栏消毒'),
(4, 'HS-B02-01', 'B02 第 1 栏', 1, 0, 'NORMAL', '检修后待启用'),
(4, 'HS-B02-02', 'B02 第 2 栏', 1, 0, 'REPAIR', '产床护栏维修'),
(5, 'HS-C01-01', 'C01 第 1 栏', 20, 3, 'NORMAL', '断奶 3 周内'),
(5, 'HS-C01-02', 'C01 第 2 栏', 20, 0, 'NORMAL', '空栏预热'),
(6, 'HS-D01-01', 'D01 第 1 栏', 30, 3, 'NORMAL', '育肥中期'),
(6, 'HS-D01-02', 'D01 第 2 栏', 30, 0, 'NORMAL', '清栏后空置'),
(6, 'HS-D01-03', 'D01 第 3 栏', 30, 0, 'CLEANING', '出栏后冲洗'),
(7, 'HS-D02-01', 'D02 第 1 栏', 30, 1, 'NORMAL', '育肥后期'),
(7, 'HS-D02-02', 'D02 第 2 栏', 30, 0, 'NORMAL', '待装猪'),
(8, 'HS-E01-01', 'E01 观察 1 栏', 8, 1, 'NORMAL', '疑似呼吸道症状观察');

INSERT INTO pig (ear_tag, breed, gender, birth_date, source_type, pen_id, house_id, batch_id, status, weight_kg, status_date, note) VALUES
('ET-2601-0101', 'YORKSHIRE', 'FEMALE', '2026-01-12', 'SELF', 1, 1, 7, 'ON_FARM', 118.50, '2026-01-12', '一胎母猪，膘情中等'),
('ET-2601-0102', 'YORKSHIRE', 'FEMALE', '2026-01-15', 'SELF', 1, 1, 7, 'ON_FARM', 121.20, '2026-01-15', '二胎母猪'),
('ET-2601-0103', 'LANDRACE',  'FEMALE', '2026-01-22', 'SELF', 1, 1, NULL, 'TRANSFERRED', 116.80, '2026-08-26', '8 月由 A02 转入'),
('ET-2601-0201', 'LANDRACE',  'FEMALE', '2026-02-03', 'SELF', 3, 2, NULL, 'ON_FARM', 124.60, '2026-02-03', '后备母猪待配'),
('ET-2601-0202', 'DUROC',     'MALE',   '2026-02-09', 'PURCHASE', 3, 2, NULL, 'ON_FARM', 132.40, '2026-03-01', '外购种公猪'),
('ET-2512-0006', 'YORKSHIRE', 'FEMALE', '2025-12-06', 'SELF', 5, 3, NULL, 'ON_FARM', 186.30, '2025-12-06', '经产母猪，预产期临近'),
('ET-2603-0301', 'PIETRAIN',  'FEMALE', '2026-03-11', 'SELF', 9, 5, 4, 'ON_FARM', 42.70, '2026-04-02', '保育中期'),
('ET-2603-0302', 'PIETRAIN',  'MALE',   '2026-03-14', 'SELF', 9, 5, 4, 'ON_FARM', 40.90, '2026-04-02', '保育中期'),
('ET-2603-0303', 'BERKSHIRE', 'FEMALE', '2026-03-18', 'SELF', 9, 5, NULL, 'ON_FARM', 43.60, '2026-04-02', '个体偏小单独观察'),
('ET-2601-0401', 'LANDRACE',  'FEMALE', '2026-01-25', 'SELF', 11, 6, 3, 'ON_FARM', 108.20, '2026-05-10', '育肥中期'),
('ET-2601-0402', 'LANDRACE',  'MALE',   '2026-01-27', 'SELF', 11, 6, 3, 'ON_FARM', 110.40, '2026-05-10', '育肥中期'),
('ET-2601-0403', 'YORKSHIRE', 'FEMALE', '2026-01-30', 'SELF', 11, 6, 6, 'TRANSFERRED', 113.90, '2026-07-18', '7 月由 D02 转入'),
('ET-2601-0501', 'DUROC',     'MALE',   '2026-01-08', 'PURCHASE', 14, 7, 2, 'ON_FARM', 126.70, '2026-02-14', '外购育肥，长势良好'),
('ET-2601-0502', 'DUROC',     'FEMALE', '2026-01-11', 'PURCHASE', NULL, NULL, 1, 'SOLD', 118.00, '2026-06-20', '已交双汇屠宰'),
('ET-2602-0503', 'BERKSHIRE', 'MALE',   '2026-02-15', 'SELF', NULL, NULL, 2, 'DEAD', 26.40, '2026-05-28', '呼吸道疾病死亡'),
('ET-2604-0601', 'BERKSHIRE', 'MALE',   '2026-04-05', 'SELF', 16, 8, NULL, 'ON_FARM', 58.40, '2026-08-02', '咳嗽隔离观察中');

INSERT INTO batch (batch_no, batch_name, house_id, breed, intake_count, sold_count, dead_count, start_date, expect_date, status, buyer, avg_weight_kg, note) VALUES
('BATCH-2601-01', '元月育肥一批', 6, 'LANDRACE', 320, 314, 5, '2025-10-08', '2026-03-20', 'CLEARED', '双汇屠宰', 118.60, '已清栏结算'),
('BATCH-2601-02', '元月育肥二批', 7, 'DUROC', 280, 268, 7, '2025-10-22', '2026-04-06', 'SELLING', '雨润食品', 121.40, '分批出栏中'),
('BATCH-2602-01', '二月育肥三批', 6, 'LANDRACE', 260, 0, 3, '2025-11-15', '2026-05-02', 'RAISING', '', 96.80, '预计下月开栏'),
('BATCH-2603-01', '三月保育转育肥', 5, 'PIETRAIN', 180, 0, 2, '2025-12-06', '2026-06-10', 'RAISING', '', 74.20, '体重未达标，继续育肥'),
('BATCH-2604-01', '四月新入栏批', 7, 'DUROC', 300, 0, 0, '2026-01-09', '2026-07-15', 'BUILDING', '', 32.60, '断奶仔猪刚转入'),
('BATCH-2604-02', '四月续养批', 6, 'YORKSHIRE', 240, 96, 4, '2025-11-28', '2026-05-18', 'SELLING', '金锣肉制品', 116.20, '已出栏 96 头'),
('BATCH-2605-01', '五月扩繁批', 1, 'YORKSHIRE', 120, 0, 1, '2026-02-18', '2026-08-25', 'BUILDING', '', 88.50, '扩繁后备群'),
('BATCH-2605-02', '五月外购育肥批', 7, 'BERKSHIRE', 150, 0, 0, '2026-03-02', '2026-09-08', 'RAISING', '', 82.30, '外购架子猪');

INSERT INTO feed_batch (feed_no, feed_name, feed_type, supplier, quantity_kg, produce_date, expire_date, status, note) VALUES
('FB-2601-01', '仔猪教槽料 800',   'CREEP',    '新希望六和', 2000.00, '2026-01-05', '2026-07-05', 'NORMAL', '含乳清粉，诱食性好'),
('FB-2601-02', '保育浓缩料 4011',  'NURSERY',  '新希望六和', 5000.00, '2026-01-12', '2026-07-12', 'NORMAL', '保育阶段主用料'),
('FB-2602-01', '育肥配合料 552',   'FINISHER', '大北农',   12000.00, '2026-02-08', '2026-08-08', 'NORMAL', '育肥中期自由采食'),
('FB-2602-02', '育肥后期料 553',   'FINISHER', '大北农',    8000.00, '2026-02-20', '2026-08-20', 'NORMAL', '出栏前 30 天使用'),
('FB-2603-01', '妊娠母猪料 566',   'SOW',      '正大集团',  6000.00, '2026-03-03', '2026-09-03', 'NORMAL', '限饲阶段'),
('FB-2603-02', '哺乳母猪料 567',   'SOW',      '正大集团',  4500.00, '2026-03-16', '2026-09-16', 'NORMAL', '哺乳期高能高蛋白'),
('FB-2604-01', '种公猪料 568',     'BOAR',     '禾丰牧业',  1500.00, '2026-04-02', '2026-10-02', 'NORMAL', '公猪专用'),
('FB-2512-02', '教槽补奶粉',       'CREEP',    '禾丰牧业',   800.00, '2025-12-18', '2026-06-18', 'USED_UP', '已过期，仅留样');

INSERT INTO transfer (pig_id, ear_tag, from_pen_id, to_pen_id, transfer_date, reason, operator, remark, created_at) VALUES
(1, 'ET-2601-0101', NULL, 1, '2026-02-10', 'WEAN',   '王建国', '断奶转入配怀舍', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 210 DAY),
(2, 'ET-2601-0102', NULL, 1, '2026-02-12', 'WEAN',   '王建国', '断奶转入配怀舍', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 208 DAY),
(3, 'ET-2601-0103', 3,    1, '2026-08-26', 'DENSITY','王建国', '密度调整由 A02 转入', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 32 DAY),
(4, 'ET-2601-0201', NULL, 3, '2026-03-06', 'GROWTH', '王建国', '日龄转栏', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 190 DAY),
(5, 'ET-2601-0202', NULL, 3, '2026-03-01', 'GROWTH', '王建国', '外购公猪入栏', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 195 DAY),
(6, 'ET-2512-0006', 6,    5, '2026-07-30', 'GROWTH', '李秀兰', '转入产床待产', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 58 DAY),
(7, 'ET-2603-0301', 10,   9, '2026-04-02', 'WEAN',   '张海涛', '断奶转入保育', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 150 DAY),
(8, 'ET-2603-0302', 10,   9, '2026-04-02', 'WEAN',   '张海涛', '断奶转入保育', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 150 DAY),
(9, 'ET-2603-0303', 10,   9, '2026-04-02', 'WEAN',   '张海涛', '断奶转入保育', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 150 DAY),
(10, 'ET-2601-0401', 13,  11, '2026-05-10', 'GROWTH','陈立军', '保育转育肥', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 112 DAY),
(11, 'ET-2601-0402', 13,  11, '2026-05-10', 'GROWTH','陈立军', '保育转育肥', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 112 DAY),
(12, 'ET-2601-0403', 15,  11, '2026-07-18', 'DENSITY','陈立军', '由 D02 转入合栏', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 70 DAY),
(13, 'ET-2601-0501', NULL,14, '2026-02-14', 'GROWTH','陈立军', '外购育肥入栏', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 210 DAY),
(16, 'ET-2604-0601', 11,  16, '2026-08-02', 'ISOLATE','赵德明', '咳嗽转隔离观察', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 55 DAY);

INSERT INTO feeding (feed_batch_id, pen_id, feed_name, feed_type, amount_kg, pig_count, feed_time, operator, remark) VALUES
(5, 1,  '妊娠母猪料 566', 'SOW',      18.00, 3,  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY,  '王建国', '限饲 6kg/头·天'),
(5, 3,  '妊娠母猪料 566', 'SOW',      12.00, 2,  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY,  '王建国', '限饲 6kg/头·天'),
(6, 5,  '哺乳母猪料 567', 'SOW',       7.50, 1,  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY,  '李秀兰', '产前加喂'),
(2, 9,  '保育浓缩料 4011','NURSERY',  24.00, 3,  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY,  '张海涛', '自由采食'),
(3, 11, '育肥配合料 552', 'FINISHER', 96.00, 3,  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY,  '陈立军', '自由采食'),
(3, 14, '育肥配合料 552', 'FINISHER', 32.00, 1,  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY,  '陈立军', '自由采食'),
(4, 14, '育肥后期料 553', 'FINISHER', 30.00, 1,  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY,  '陈立军', '出栏前催肥'),
(1, 10, '仔猪教槽料 800', 'CREEP',    15.00, 0,  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY,  '张海涛', '空栏试喂'),
(7, 3,  '种公猪料 568',   'BOAR',      6.00, 1,  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 DAY,  '王建国', '公猪单喂'),
(3, 12, '育肥配合料 552', 'FINISHER',120.00, 0,  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 DAY,  '陈立军', '清栏前剩余料'),
(2, 9,  '保育浓缩料 4011','NURSERY',  22.50, 3,  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 DAY,  '张海涛', '自由采食'),
(6, 16, '哺乳母猪料 567', 'SOW',       4.00, 1,  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 DAY,  '赵德明', '隔离舍单独投喂');

INSERT INTO vaccine_record (pig_id, ear_tag, vaccine_name, dose_ml, vaccine_date, next_date, operator, remark, created_at, plan_id, task_id, dose_no, request_no) VALUES
(1,  'ET-2601-0101', '猪瘟活疫苗',        2.00, '2026-03-05', '2026-09-05', '周防疫员', '颈部肌注', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 180 DAY, NULL, NULL, NULL, NULL),
(2,  'ET-2601-0102', '猪瘟活疫苗',        2.00, '2026-03-05', '2026-09-05', '周防疫员', '颈部肌注', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 180 DAY, NULL, NULL, NULL, NULL),
(3,  'ET-2601-0103', '口蹄疫 O 型灭活疫苗', 3.00, '2026-04-12', '2026-10-12', '周防疫员', '耳后肌注', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 150 DAY, NULL, NULL, NULL, NULL),
(4,  'ET-2601-0201', '细小病毒灭活疫苗',   2.00, '2026-04-20', NULL, '周防疫员', '配种前免疫', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 145 DAY, NULL, NULL, NULL, NULL),
(5,  'ET-2601-0202', '伪狂犬病活疫苗',     2.00, '2026-05-08', '2026-11-08', '周防疫员', '滴鼻 + 肌注', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 130 DAY, NULL, NULL, NULL, NULL),
(6,  'ET-2512-0006', '猪传染性胃肠炎二联苗', 4.00, '2026-05-18', NULL, '周防疫员', '产前跟胎免疫', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 120 DAY, NULL, NULL, NULL, NULL),
(7,  'ET-2603-0301', '圆环病毒灭活疫苗',   1.00, '2026-05-25', NULL, '周防疫员', '仔猪 14 日龄', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 115 DAY, 3, 9, 1, NULL),
(8,  'ET-2603-0302', '圆环病毒灭活疫苗',   1.00, '2026-05-25', NULL, '周防疫员', '仔猪 14 日龄', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 115 DAY, NULL, NULL, NULL, NULL),
(9,  'ET-2603-0303', '支原体灭活疫苗',     2.00, '2026-06-02', NULL, '周防疫员', '仔猪 21 日龄', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 108 DAY, NULL, NULL, NULL, NULL),
(10, 'ET-2601-0401', '高致病性蓝耳病活疫苗', 2.00, '2026-06-15', '2026-12-15', '周防疫员', '育肥期加强', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 95 DAY, NULL, NULL, NULL, NULL),
(11, 'ET-2601-0402', '猪瘟活疫苗',        2.00, '2026-06-22', '2026-12-22', '周防疫员', '二免', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 88 DAY, 1, 12, 1, NULL),
(13, 'ET-2601-0501', '口蹄疫 O 型灭活疫苗', 3.00, '2026-07-06', '2027-01-06', '周防疫员', '外购猪入场免疫', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 74 DAY, NULL, NULL, NULL, NULL),
(13, 'ET-2601-0501', '猪瘟活疫苗',        2.00, '2026-02-05', NULL, '周防疫员', '漏种补种：仔猪腹泻痊愈后补种', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 242 DAY, 1, 6, 1, 'IMM-SEED-260205-01');

-- ---------------- 免疫计划规则种子 ----------------
INSERT INTO immune_plan (plan_code, vaccine_name, stage, first_dose_age_days, dose_interval_days, total_doses, grace_days, dose_ml, status, note) VALUES
('IP-2601', '猪瘟活疫苗',           'ALL',      21, 180, 2, 7, 2.00, 'ACTIVE', '全群猪瘟基础免疫：21 日龄首免，180 天后加强'),
('IP-2602', '口蹄疫 O 型灭活疫苗',   'NURSERY',  35, 30,  2, 5, 3.00, 'ACTIVE', '保育阶段口蹄疫两免'),
('IP-2603', '圆环病毒灭活疫苗',      'SUCKLING', 14, 21,  1, 7, 1.00, 'ACTIVE', '仔猪 14 日龄圆环单针');

-- ---------------- 接种任务种子（生成时名单快照） ----------------
-- 覆盖看板各分组：待接种 / 临期（今天到期、宽限期内）/ 漏种 / 已补种 / 已终止（死亡、等效记录）
INSERT INTO immune_task (plan_id, pig_id, ear_tag, dose_no, planned_date, grace_days, pen_id, basis, status, terminate_reason, terminated_at, make_up_date, make_up_reason, completed_date, completed_at, vaccine_record_id, request_no, created_at) VALUES
(1, 9,  'ET-2603-0303', 1, '2026-04-08', 7, 9,  '出生 2026-03-18 + 首剂日龄 21 天 → 第 1 剂计划 2026-04-08', 'MISSED', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 180 DAY),
(1, 9,  'ET-2603-0303', 2, '2026-10-05', 7, 9,  '第 1 剂计划 2026-04-08 + 剂次间隔 180 天 × 1 → 第 2 剂计划 2026-10-05', 'PENDING', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY),
(1, 8,  'ET-2603-0302', 1, '2026-04-04', 7, 9,  '出生 2026-03-14 + 首剂日龄 21 天 → 第 1 剂计划 2026-04-04', 'MISSED', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 184 DAY),
(1, 8,  'ET-2603-0302', 2, '2026-10-01', 7, 9,  '第 1 剂计划 2026-04-04 + 剂次间隔 180 天 × 1 → 第 2 剂计划 2026-10-01', 'PENDING', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY),
(1, 10, 'ET-2601-0401', 1, '2026-02-15', 7, 11, '出生 2026-01-25 + 首剂日龄 21 天 → 第 1 剂计划 2026-02-15', 'MISSED', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 232 DAY),
(1, 13, 'ET-2601-0501', 1, '2026-01-29', 7, 14, '出生 2026-01-08 + 首剂日龄 21 天 → 第 1 剂计划 2026-01-29', 'MADE_UP', NULL, NULL, '2026-02-05', '仔猪腹泻痊愈后补种', '2026-02-05', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 242 DAY, 13, 'IMM-SEED-260205-01', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 250 DAY),
(1, 13, 'ET-2601-0501', 2, '2026-07-28', 7, 14, '第 1 剂计划 2026-01-29 + 剂次间隔 180 天 × 1 → 第 2 剂计划 2026-07-28', 'MISSED', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 100 DAY),
(1, 15, 'ET-2602-0503', 1, '2026-03-08', 7, NULL, '出生 2026-02-15 + 首剂日龄 21 天 → 第 1 剂计划 2026-03-08', 'TERMINATED', 'PIG_DEAD', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 130 DAY, NULL, NULL, NULL, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 220 DAY),
(3, 7,  'ET-2603-0301', 1, '2026-03-25', 7, 9,  '出生 2026-03-11 + 首剂日龄 14 天 → 第 1 剂计划 2026-03-25', 'TERMINATED', 'EQUIVALENT', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 133 DAY, NULL, NULL, NULL, NULL, 7, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 194 DAY),
(1, 16, 'ET-2604-0601', 1, '2026-04-26', 7, 16, '出生 2026-04-05 + 首剂日龄 21 天 → 第 1 剂计划 2026-04-26', 'MISSED', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 162 DAY),
(1, 16, 'ET-2604-0601', 2, '2026-10-23', 7, 16, '第 1 剂计划 2026-04-26 + 剂次间隔 180 天 × 1 → 第 2 剂计划 2026-10-23', 'PENDING', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY),
(1, 11, 'ET-2601-0402', 1, '2026-02-17', 7, 11, '出生 2026-01-27 + 首剂日龄 21 天 → 第 1 剂计划 2026-02-17', 'TERMINATED', 'EQUIVALENT', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 105 DAY, NULL, NULL, NULL, NULL, 11, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 231 DAY);

INSERT INTO medication_record
(request_no, pig_id, ear_tag, medicine_name, dose, medication_date, withdrawal_days, operator, remark, created_at, revoked, trace_no) VALUES
('MED-SEED-261003-01', 13, 'ET-2601-0501', '阿莫西林可溶性粉', '2g/头', '2026-10-01', 14, '赵德明', '呼吸道症状预防用药，10 月 15 日前不得出栏', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY, 0, 'TR-261001-011'),
('MED-SEED-261003-02', 10, 'ET-2601-0401', '氟苯尼考注射液', '1.5ml/头', '2026-09-24', 10, '赵德明', '咳嗽治疗，休药期已满可出栏', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 9 DAY, 0, 'TR-260924-012'),
('MED-SEED-261003-03', 11, 'ET-2601-0402', '替米考星注射液', '2ml/头', '2026-10-02', 7, '赵德明', '咳嗽治疗，10 月 10 日前不得出栏', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY, 0, 'TR-261002-013');

INSERT INTO trace_record (trace_no, trace_type, event_title, event_desc, operator, event_time) VALUES
('TR-260815-001', 'IMMUNE',  '全群猪瘟免疫',   '对配怀舍、育肥舍在群猪只统一免疫猪瘟活疫苗，剂量 2ml/头', '周防疫员', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 42 DAY),
('TR-260822-002', 'TRANSFER','保育转育肥搬迁', 'C01 保育舍 3 头仔猪达 21 日龄，转至 D01 育肥舍', '张海涛', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 35 DAY),
('TR-260829-003', 'FEEDING', '育肥料批次更换', 'D01/D02 育肥舍更换育肥配合料 552，投料 96kg', '陈立军', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 28 DAY),
('TR-260905-004', 'SALE',    '元月二批出栏 60 头', '雨润食品上门收购，出栏均重 121.4kg', '陈立军', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 21 DAY),
('TR-260912-005', 'DEATH',   '育肥舍死淘登记', 'ET-2602-0503 呼吸道疾病死亡，已做无害化处理', '陈立军', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 14 DAY),
('TR-260918-006', 'CHECK',   '隔离舍例行检疫', 'E01 隔离观察舍采样送检，结果待回', '赵德明', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 8 DAY),
('TR-260920-007', 'IMMUNE',  '口蹄疫二免',     '育肥二舍存栏猪只口蹄疫 O 型二免', '周防疫员', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 DAY),
('TR-260924-008', 'TRANSFER','产床转入待产',   'ET-2512-0006 预产期临近，转入 B01 第 1 栏产床', '李秀兰', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY),
('TR-260926-009', 'FEEDING', '教槽料投喂',     '保育舍补投教槽料 800，诱食观察', '张海涛', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
('TR-260927-010', 'CHECK',   '出栏前检疫',     '元月二批出栏前官方检疫，合格准出', '驻场兽医', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
('TR-261001-011', 'MEDICATION','阿莫西林用药登记','ET-2601-0501 使用阿莫西林，休药 14 天，10 月 15 日前不得出栏', '赵德明', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
('TR-260924-012', 'MEDICATION','氟苯尼考用药登记','ET-2601-0401 使用氟苯尼考，休药 10 天，已到可出栏日期', '赵德明', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 9 DAY),
('TR-261002-013', 'MEDICATION','替米考星用药登记','ET-2601-0402 使用替米考星，休药 7 天，10 月 10 日前不得出栏', '赵德明', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY);

-- @ManyToAny 集合表：一条溯源记录挂若干个任意类型的来源对象（这里四种类型都出现了）
INSERT INTO trace_target (trace_record_id, target_type, target_id) VALUES
(1, 'PIG', 1),
(1, 'PIG', 2),
(1, 'HOUSE', 1),
(1, 'FEED_BATCH', 5),
(2, 'PIG', 7),
(2, 'PIG', 8),
(2, 'HOUSE', 6),
(3, 'BATCH', 3),
(3, 'FEED_BATCH', 3),
(3, 'HOUSE', 6),
(4, 'BATCH', 2),
(4, 'PIG', 13),
(5, 'BATCH', 2),
(5, 'PIG', 15),
(6, 'HOUSE', 8),
(6, 'PIG', 16),
(7, 'PIG', 13),
(7, 'FEED_BATCH', 3),
(8, 'PIG', 6),
(8, 'HOUSE', 3),
(9, 'FEED_BATCH', 1),
(9, 'HOUSE', 5),
(10, 'BATCH', 2),
(10, 'HOUSE', 7),
(11, 'PIG', 13),
(11, 'BATCH', 2),
(12, 'PIG', 10),
(12, 'BATCH', 3),
(13, 'PIG', 11),
(13, 'BATCH', 3);
