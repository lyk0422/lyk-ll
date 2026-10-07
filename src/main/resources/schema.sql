-- 轧钢排产服务建表脚本（H2 MODE=MySQL，亦兼容标准 MySQL 8 语法）
-- 所有 id 由应用通过 id_alloc 分配，便于跨库移植与并发控制。

-- 合成宽度档位表：width_mm 为板宽（毫米），grade_no 为档位序号，数值越大板越窄。
CREATE TABLE IF NOT EXISTS width_grade (
    width_mm INT NOT NULL PRIMARY KEY,
    grade_no INT NOT NULL
);

-- 全局主键分配表：seq_name 序列名，next_val 下一个可分配主键
CREATE TABLE IF NOT EXISTS id_alloc (
    seq_name VARCHAR(32) NOT NULL PRIMARY KEY,
    next_val BIGINT NOT NULL
);

-- 排产单：status 取值 DRAFT/VALIDATED/ISSUED/EXECUTING/FROZEN；
-- effective=1 表示该产线当前唯一生效版本；locked=1 表示实绩达换辊阈值后锁定后续排程；
-- roll_change_required=1 表示系统提示需要换辊；frozen_at 非空表示日终冻结时刻。
CREATE TABLE IF NOT EXISTS schedule_order (
    id BIGINT NOT NULL PRIMARY KEY,
    request_id VARCHAR(64),
    fingerprint VARCHAR(64) NOT NULL,
    line_code VARCHAR(32) NOT NULL,
    unit_code VARCHAR(32) NOT NULL,
    version_no INT NOT NULL,
    base_version_id BIGINT,
    status VARCHAR(16) NOT NULL,
    effective TINYINT NOT NULL DEFAULT 0,
    locked TINYINT NOT NULL DEFAULT 0,
    roll_change_required TINYINT NOT NULL DEFAULT 0,
    frozen_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 排产块（轧制作业）：seq 为整单块序（从1开始），campaign_no 为换辊周期号（从1开始），
-- seq_in_campaign 为该周期内块序；rolling_km 为计划轧制里程（公里）；
-- warmup=1 表示烫辊材；locked=1 表示该作业被实绩换辊阈值锁定；
-- start_time/end_time 构成半开作业区间 [start_time, end_time)。
CREATE TABLE IF NOT EXISTS schedule_item (
    id BIGINT NOT NULL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    seq INT NOT NULL,
    campaign_no INT NOT NULL,
    seq_in_campaign INT NOT NULL,
    contract_no VARCHAR(32) NOT NULL,
    width_mm INT NOT NULL,
    rolling_km DECIMAL(10,3) NOT NULL,
    warmup TINYINT NOT NULL DEFAULT 0,
    locked TINYINT NOT NULL DEFAULT 0,
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP NOT NULL,
    CONSTRAINT fk_item_order FOREIGN KEY (order_id) REFERENCES schedule_order(id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_item_order_seq ON schedule_item(order_id, seq);
CREATE UNIQUE INDEX IF NOT EXISTS uk_item_order_contract ON schedule_item(order_id, contract_no);

-- 换辊窗口：半开区间 [start_time, end_time)，端点相接不冲突。
CREATE TABLE IF NOT EXISTS roll_change_window (
    id BIGINT NOT NULL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    campaign_no INT NOT NULL,
    line_code VARCHAR(32) NOT NULL,
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP NOT NULL,
    CONSTRAINT fk_window_order FOREIGN KEY (order_id) REFERENCES schedule_order(id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_window_order_campaign ON roll_change_window(order_id, campaign_no);

-- 产线生效版本：每个 line_code 最多一行，靠主键保证并发下达只有一个版本生效。
CREATE TABLE IF NOT EXISTS effective_version (
    line_code VARCHAR(32) NOT NULL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    since_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 轧制实绩：按“合同号＋道次”唯一去重；rolling_km 为该道次实绩里程（公里）；
-- recorded_at 为实绩发生时刻，created_at 为回传入库时刻。
CREATE TABLE IF NOT EXISTS actual_result (
    id BIGINT NOT NULL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    contract_no VARCHAR(32) NOT NULL,
    pass_no INT NOT NULL,
    rolling_km DECIMAL(10,3) NOT NULL,
    recorded_at TIMESTAMP NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_actual_order FOREIGN KEY (order_id) REFERENCES schedule_order(id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_actual_contract_pass ON actual_result(contract_no, pass_no);

-- 迟到实绩事件：冻结后回传的实绩只登记事件，不改变已冻结里程与换辊结论。
CREATE TABLE IF NOT EXISTS late_actual_event (
    id BIGINT NOT NULL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    contract_no VARCHAR(32) NOT NULL,
    pass_no INT NOT NULL,
    rolling_km DECIMAL(10,3) NOT NULL,
    recorded_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 轧辊寿命台账：status 取值 IN_SERVICE/BANNED（累计里程达周期上限即禁排）；
-- total_km 为累计实绩里程（公里，不随换班上机重置，返修新代从 0 起另立台账行）；
-- cycle_limit_km 为周期上限（公里）；
-- coils_since_mount 为本次上机后已轧卷数（前 5 卷为首用烫辊位）；
-- mounted_at 为当前上机时刻（UTC）；frozen_at 非空表示日冻结时刻（UTC），
-- 冻结后迟到实绩只记迟到事件，不改已冻结里程；
-- physical_roll_id 为合成物理辊唯一编号；generation_no 为该物理辊代次号（从 1 起，返修一次加 1）；
-- diameter_mm 为该代磨后直径（毫米，保留两位小数）；首代上机不采集直径，允许为空，
-- 返修代次必填且不得大于上一代直径。
CREATE TABLE IF NOT EXISTS roll_life (
    roll_no VARCHAR(32) NOT NULL PRIMARY KEY,
    physical_roll_id BIGINT NOT NULL,
    generation_no INT NOT NULL DEFAULT 1,
    status VARCHAR(16) NOT NULL,
    total_km DECIMAL(10,3) NOT NULL DEFAULT 0,
    cycle_limit_km DECIMAL(10,3) NOT NULL,
    diameter_mm DECIMAL(8,2) NULL,
    coils_since_mount INT NOT NULL DEFAULT 0,
    mounted_at TIMESTAMP NOT NULL,
    frozen_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_roll_life_physical ON roll_life(physical_roll_id);

-- 换辊实绩：按“轧辊号＋实绩时刻”唯一去重，乱序回传按 recorded_at 归位；
-- first_use=1 表示新辊上机后前 5 卷的首用标记；rolling_km 为该卷实绩里程（公里）。
CREATE TABLE IF NOT EXISTS roll_change_actual (
    id BIGINT NOT NULL PRIMARY KEY,
    roll_no VARCHAR(32) NOT NULL,
    rolling_km DECIMAL(10,3) NOT NULL,
    first_use TINYINT NOT NULL DEFAULT 0,
    recorded_at TIMESTAMP NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_roll_actual_roll_time ON roll_change_actual(roll_no, recorded_at);

-- 轧辊迟到实绩事件：日冻结后回传的实绩只登记事件，不改已冻结里程与禁排结论。
CREATE TABLE IF NOT EXISTS roll_late_event (
    id BIGINT NOT NULL PRIMARY KEY,
    roll_no VARCHAR(32) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    rolling_km DECIMAL(10,3) NOT NULL,
    recorded_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 幂等记录：fingerprint 覆盖 requestId 与全部业务字段；response_body 保存首次响应 JSON。
CREATE TABLE IF NOT EXISTS idempotent_record (
    request_id VARCHAR(64) NOT NULL PRIMARY KEY,
    biz_type VARCHAR(32) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    http_status INT NOT NULL,
    response_body CLOB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 合成物理辊：首代上机时登记，physical_id 为全寿命唯一编号（由 id_alloc 分配）；
-- current_generation_no 为当前成功代次号（从 1 起）；repair_count 为累计成功返修次数（上限 2）；
-- version 为聚合乐观锁版本（从 1 起，成功返修加 1，旧版本提交返回 409）。
CREATE TABLE IF NOT EXISTS physical_roll (
    physical_id BIGINT NOT NULL PRIMARY KEY,
    current_roll_no VARCHAR(32) NOT NULL,
    current_generation_no INT NOT NULL DEFAULT 1,
    repair_count INT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_physical_current_roll FOREIGN KEY (current_roll_no) REFERENCES roll_life(roll_no)
);

-- 返修放行申请：只有“寿命到期且已冻结”的当前代次可申请；
-- status 取值 PENDING（待两人检验）/APPROVED（两人合格，已原子建立下一代）/REJECTED（任一检验不合格，终止）；
-- old_roll_no 为旧代辊号；new_roll_no 为拟用新辊号（申请时该辊尚不存在于台账，放行建代后成为下一代辊号）；
-- ground_diameter_mm 为磨后直径（毫米，保留两位，>=500 且不得大于上一代直径）；
-- new_cycle_limit_km 为新一代周期公里上限（正数且不超过上一代上限）；
-- inspection_cert 为检验凭据；expected_version 为提交时物理辊聚合版本，成功时校验并加 1；
-- successor_roll_no 为成功建立的下一代辊号，终止时为空。每个旧代次最多一个成功后继（唯一索引裁决并发双建）。
CREATE TABLE IF NOT EXISTS roll_repair_request (
    id BIGINT NOT NULL PRIMARY KEY,
    physical_id BIGINT NOT NULL,
    old_roll_no VARCHAR(32) NOT NULL,
    new_roll_no VARCHAR(32) NOT NULL,
    ground_diameter_mm DECIMAL(8,2) NOT NULL,
    new_cycle_limit_km DECIMAL(10,3) NOT NULL,
    inspection_cert VARCHAR(256) NOT NULL,
    expected_version INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    successor_roll_no VARCHAR(32) NULL,
    successor_key VARCHAR(32) GENERATED ALWAYS AS
        (CASE WHEN status = 'APPROVED' THEN old_roll_no ELSE NULL END),
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_repair_physical FOREIGN KEY (physical_id) REFERENCES physical_roll(physical_id),
    CONSTRAINT fk_repair_old_roll FOREIGN KEY (old_roll_no) REFERENCES roll_life(roll_no)
);
-- 每个旧代次最多一个成功后继：仅成功行占用 successor_key，终止行不阻碍另提新申请。
CREATE UNIQUE INDEX IF NOT EXISTS uk_repair_successor ON roll_repair_request(successor_key);

-- 返修检验确认：同一申请需两名不同检验员（inspector_id 唯一）均合格才放行；
-- approved=0 表示不合格，一旦出现即整单终止且不建新辊。
CREATE TABLE IF NOT EXISTS roll_repair_inspection (
    id BIGINT NOT NULL PRIMARY KEY,
    request_id_pk BIGINT NOT NULL,
    inspector_id VARCHAR(64) NOT NULL,
    approved TINYINT NOT NULL,
    cert_no VARCHAR(128) NOT NULL,
    inspected_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_inspection_repair FOREIGN KEY (request_id_pk) REFERENCES roll_repair_request(id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_inspection_request_inspector
    ON roll_repair_inspection(request_id_pk, inspector_id);

-- 旧代迟到实绩证据：返修建代后回传到旧辊号的实绩只落证据，不改旧代冻结里程、不影响新代台账。
CREATE TABLE IF NOT EXISTS roll_repair_late_evidence (
    id BIGINT NOT NULL PRIMARY KEY,
    old_roll_no VARCHAR(32) NOT NULL,
    physical_id BIGINT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    rolling_km DECIMAL(10,3) NOT NULL,
    recorded_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_repair_late_old_roll ON roll_repair_late_evidence(old_roll_no);
