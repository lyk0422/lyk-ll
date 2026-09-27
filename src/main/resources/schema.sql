-- 载重平衡服务建表脚本（H2 MySQL 兼容模式，亦可在 MySQL 8 执行）
-- 所有重量单位 kg，力臂为站位英寸，重心以 %MAC 表示；时间戳为 Asia/Shanghai 本地时间。

-- 合成飞机：空重、结构限制与几何参数
CREATE TABLE IF NOT EXISTS aircraft (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    tail_no      VARCHAR(20)  NOT NULL COMMENT '机尾号',
    aircraft_type VARCHAR(50) NOT NULL COMMENT '机型（合成）',
    oew_kg       INT          NOT NULL COMMENT '使用空重 OEW，kg',
    oew_arm      DECIMAL(10,2) NOT NULL COMMENT '空机合力臂，站位英寸',
    mzfw_kg      INT          NOT NULL COMMENT '最大无油重量 MZFW，kg',
    mtow_kg      INT          NOT NULL COMMENT '最大起飞重量 MTOW，kg',
    mlw_kg       INT          NOT NULL COMMENT '最大着陆重量 MLW，kg',
    lemac        DECIMAL(10,2) NOT NULL COMMENT '平均空气动力弦前缘 LEMAC，英寸',
    mac_length   DECIMAL(10,2) NOT NULL COMMENT '平均空气动力弦长度 MAC，英寸',
    fuel_arm     DECIMAL(10,2) NOT NULL COMMENT '燃油合力臂，站位英寸',
    CONSTRAINT uk_aircraft_tail_no UNIQUE (tail_no)
);

-- 合成重心包线：以（重量 kg，重心 %MAC）为顶点的多边形，边界点合法
CREATE TABLE IF NOT EXISTS envelope_point (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    aircraft_id BIGINT        NOT NULL COMMENT '所属飞机 ID',
    seq_no      INT           NOT NULL COMMENT '顶点顺序，自 0 起逆时针',
    weight_kg   INT           NOT NULL COMMENT '包线顶点重量，kg',
    mac_pct     DECIMAL(8,4)  NOT NULL COMMENT '包线顶点重心，%MAC',
    CONSTRAINT fk_envelope_aircraft FOREIGN KEY (aircraft_id) REFERENCES aircraft (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_envelope_seq ON envelope_point (aircraft_id, seq_no);

-- 合成业载站位（座位区/货舱），决定各业载的力臂
CREATE TABLE IF NOT EXISTS load_station (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    aircraft_id BIGINT        NOT NULL COMMENT '所属飞机 ID',
    station_code VARCHAR(20)  NOT NULL COMMENT '站位代码，如 PAX_F/CARGO_F',
    arm         DECIMAL(10,2) NOT NULL COMMENT '站位力臂，站位英寸',
    label       VARCHAR(100)  NOT NULL COMMENT '站位说明',
    CONSTRAINT fk_station_aircraft FOREIGN KEY (aircraft_id) REFERENCES aircraft (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_station_code ON load_station (aircraft_id, station_code);

-- 航班（合成）
CREATE TABLE IF NOT EXISTS flight (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    flight_no      VARCHAR(20)  NOT NULL COMMENT '航班号',
    origin         VARCHAR(10)  NOT NULL COMMENT '出发机场 IATA',
    destination    VARCHAR(10)  NOT NULL COMMENT '到达机场 IATA',
    aircraft_id    BIGINT       NOT NULL COMMENT '执飞飞机 ID',
    scheduled_time TIMESTAMP    NOT NULL COMMENT '计划起飞时间，Asia/Shanghai',
    created_at     TIMESTAMP    NOT NULL COMMENT '创建时间',
    CONSTRAINT fk_flight_aircraft FOREIGN KEY (aircraft_id) REFERENCES aircraft (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_flight_no_time ON flight (flight_no, scheduled_time);

-- 舱单版本：预配→值机→装载确认→双岗复核→放行关闭；重打产生新版本链
CREATE TABLE IF NOT EXISTS manifest_version (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    flight_id           BIGINT       NOT NULL COMMENT '航班 ID',
    version_no          INT          NOT NULL COMMENT '版本号，自 1 起',
    parent_version_id   BIGINT       NULL COMMENT 'LMC/重打父版本 ID；首版为 NULL',
    request_id          VARCHAR(64)  NOT NULL COMMENT '创建该版本的幂等请求 ID',
    fingerprint         CHAR(64)     NOT NULL COMMENT 'SHA-256 指纹，覆盖 requestId 与全部业务字段',
    status              VARCHAR(20)  NOT NULL COMMENT 'PRELIMINARY/CHECKIN/LOADED/REVIEWED/CLOSED/SUPERSEDED',
    passenger_count     INT          NOT NULL COMMENT '旅客人数',
    payload_kg          DECIMAL(10,2) NOT NULL COMMENT '总业载重量，kg（含客货邮行）',
    fuel_kg             DECIMAL(10,2) NOT NULL COMMENT '加油量，kg',
    zfw_kg              DECIMAL(10,2) NOT NULL COMMENT '无油重量 ZFW=OEW+业载，kg',
    tow_kg              DECIMAL(10,2) NOT NULL COMMENT '起飞重量 TOW=ZFW+燃油，kg',
    cg_zfw_pct          DECIMAL(8,4) NOT NULL COMMENT '无油重心，%MAC',
    cg_tow_pct          DECIMAL(8,4) NOT NULL COMMENT '起飞重心，%MAC',
    within_limits       BOOLEAN      NOT NULL COMMENT '重量与包线是否全部合法',
    lmc_pax_delta       INT          NOT NULL DEFAULT 0 COMMENT '本版累计 LMC 旅客增量，人',
    lmc_weight_delta    DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '本版累计 LMC 货物增量，kg',
    active_flag         BIGINT       NULL COMMENT '有效版本哨兵：有效=flight_id，作废=NULL',
    created_by          VARCHAR(64)  NOT NULL COMMENT '创建人岗位账号',
    created_at          TIMESTAMP    NOT NULL COMMENT '创建时间',
    updated_at          TIMESTAMP    NOT NULL COMMENT '最近更新时间',
    closed_at           TIMESTAMP    NULL COMMENT '放行关闭时间；NULL 为未关闭',
    CONSTRAINT uk_manifest_request UNIQUE (request_id),
    CONSTRAINT uk_manifest_active UNIQUE (flight_id, active_flag)
);
CREATE INDEX IF NOT EXISTS idx_manifest_flight ON manifest_version (flight_id);

-- 业载明细快照，挂舱单版本；人数/件数与重量均可复算
CREATE TABLE IF NOT EXISTS load_detail (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    manifest_id    BIGINT        NOT NULL COMMENT '所属舱单版本 ID',
    category       VARCHAR(20)   NOT NULL COMMENT 'PASSENGER/BAGGAGE/CARGO/MAIL',
    station_code   VARCHAR(20)   NOT NULL COMMENT '装载站位代码',
    piece_count    INT           NOT NULL COMMENT '人数或件数；旅客为人，其余为件',
    unit_weight_kg DECIMAL(8,2)  NOT NULL COMMENT '单位重量，kg/人或 kg/件',
    total_weight_kg DECIMAL(10,2) NOT NULL COMMENT '合计重量=piece_count*unit_weight，kg',
    seq_no         INT           NOT NULL COMMENT '录入顺序',
    CONSTRAINT fk_detail_manifest FOREIGN KEY (manifest_id) REFERENCES manifest_version (id)
);
CREATE INDEX IF NOT EXISTS idx_detail_manifest ON load_detail (manifest_id);

-- LMC 修正链：限内累计，超限标记并要求重打
CREATE TABLE IF NOT EXISTS lmc_log (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    manifest_id      BIGINT       NOT NULL COMMENT '发生修正时的舱单版本 ID',
    request_id       VARCHAR(64)  NOT NULL COMMENT '幂等请求 ID',
    passenger_delta  INT          NOT NULL COMMENT '本次旅客增量，人（可负）',
    weight_delta_kg  DECIMAL(10,2) NOT NULL COMMENT '本次货物增量，kg（可负）',
    fuel_delta_kg    DECIMAL(10,2) NOT NULL COMMENT '本次燃油增量，kg（可负）',
    cumulative_pax   INT          NOT NULL COMMENT '累计旅客增量，人',
    cumulative_weight DECIMAL(10,2) NOT NULL COMMENT '累计货物增量，kg',
    within_lmc_limit BOOLEAN      NOT NULL COMMENT '是否在 ±5 人/±500kg 限额内（恰达含）',
    new_version_id   BIGINT       NULL COMMENT '超限重打后产生的新版本 ID；限内为 NULL',
    created_at       TIMESTAMP    NOT NULL COMMENT '发生时间',
    CONSTRAINT uk_lmc_request UNIQUE (request_id)
);
CREATE INDEX IF NOT EXISTS idx_lmc_manifest ON lmc_log (manifest_id);

-- 双岗复核确认：两个不同岗位各一条，唯一约束保证并发下唯一成功
CREATE TABLE IF NOT EXISTS review_confirmation (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    manifest_id BIGINT      NOT NULL COMMENT '舱单版本 ID',
    request_id  VARCHAR(64) NOT NULL COMMENT '幂等请求 ID',
    fingerprint CHAR(64)    NOT NULL COMMENT 'SHA-256 指纹，覆盖 requestId 与业务字段',
    role_code   VARCHAR(30) NOT NULL COMMENT '复核岗位：LOAD_CONTROLLER 配载 / FLIGHT_DISPATCHER 签派',
    reviewer    VARCHAR(64) NOT NULL COMMENT '签字人工号',
    created_at  TIMESTAMP   NOT NULL COMMENT '签字时间',
    CONSTRAINT uk_review_request UNIQUE (request_id),
    CONSTRAINT uk_review_manifest_role UNIQUE (manifest_id, role_code)
);

-- 关闭后迟到的值机变化：只登记事件并告警，不改已关闭舱单
CREATE TABLE IF NOT EXISTS late_event (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    flight_id     BIGINT      NOT NULL COMMENT '航班 ID',
    manifest_id   BIGINT      NOT NULL COMMENT '关闭时的舱单版本 ID',
    passenger_count INT       NOT NULL COMMENT '迟到旅客人数',
    weight_kg     DECIMAL(10,2) NOT NULL COMMENT '迟到业载重量，kg',
    payload_json  CLOB        NOT NULL COMMENT '迟到变化原始报文',
    alerted       BOOLEAN     NOT NULL COMMENT '是否已告警',
    created_at    TIMESTAMP   NOT NULL COMMENT '事件登记时间'
);
CREATE INDEX IF NOT EXISTS idx_late_flight ON late_event (flight_id);

-- 通用幂等键：同键同参回放首次结果，同键异参冲突
CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id   VARCHAR(64) PRIMARY KEY COMMENT '幂等请求 ID',
    fingerprint  CHAR(64)    NOT NULL COMMENT '首次请求指纹',
    http_status  INT         NOT NULL COMMENT '首次响应 HTTP 状态码',
    result_json  CLOB        NOT NULL COMMENT '首次响应体快照',
    created_at   TIMESTAMP   NOT NULL COMMENT '首次请求时间'
);

-- 集装器舱位主数据（合成）：整数毫米力臂、最大整数公斤载荷（含皮重，恰达合法）
CREATE TABLE IF NOT EXISTS cargo_compartment (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    aircraft_id       BIGINT       NOT NULL COMMENT '所属飞机 ID',
    compartment_code  VARCHAR(20)  NOT NULL COMMENT '舱位代码，如 11P/22P',
    arm_mm            INT          NOT NULL COMMENT '舱位力臂，整数毫米（站位基准）',
    max_load_kg       INT          NOT NULL COMMENT '最大载荷，整数公斤（含皮重，恰达合法）',
    allowed_uld_types VARCHAR(100) NOT NULL COMMENT '允许集装器类型代码（器号前 3 位），逗号分隔',
    CONSTRAINT fk_compartment_aircraft FOREIGN KEY (aircraft_id) REFERENCES aircraft (id),
    CONSTRAINT uk_compartment_code UNIQUE (aircraft_id, compartment_code)
);

-- 受控舱单的装配图：每次提交写一张新图（版本自 1 起），历史图只读
CREATE TABLE IF NOT EXISTS cargo_loading_plan (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    flight_id          BIGINT      NOT NULL COMMENT '所属航班 ID（图版本在航班范围内自 1 起单调递增）',
    manifest_id        BIGINT      NOT NULL COMMENT '所属舱单版本 ID（图挂具体版本，重打后旧图只读）',
    manifest_version_no INT        NOT NULL COMMENT '提交时舱单版本号快照，用于关闭前核对',
    plan_version       INT         NOT NULL COMMENT '装配图版本号，自 1 起，每次成功提交 +1',
    request_id         VARCHAR(64) NOT NULL COMMENT '创建该图的幂等请求 ID',
    fingerprint        CHAR(64)    NOT NULL COMMENT 'SHA-256 指纹，覆盖 requestId、版本、集合与精确时刻',
    cargo_net_kg       INT         NOT NULL COMMENT '净重合计，kg（Σ 逐项净重）',
    cargo_gross_kg     INT         NOT NULL COMMENT '总货重，kg（Σ(皮重+净重)，含皮重）',
    total_moment_kg_mm BIGINT      NOT NULL COMMENT '货运力矩合计，kg·mm（Σ 含皮重总重×整数毫米力臂，精确求和）',
    fuel_kg            DECIMAL(10,2) NOT NULL COMMENT '提交时舱单加油量快照，kg；加油/LMC 后不一致须重提',
    lmc_pax_delta      INT         NOT NULL COMMENT '提交时舱单累计 LMC 旅客增量快照，人',
    lmc_weight_delta   DECIMAL(10,2) NOT NULL COMMENT '提交时舱单累计 LMC 货物增量快照，kg',
    cg_zfw_pct         DECIMAL(8,4)  NOT NULL COMMENT '按图力矩重算的无油重心，%MAC',
    cg_tow_pct         DECIMAL(8,4)  NOT NULL COMMENT '按图力矩重算的起飞重心，%MAC',
    within_limits      BOOLEAN     NOT NULL COMMENT '按图重算后重量与包线是否全部合法',
    created_by         VARCHAR(64) NOT NULL COMMENT '提交人岗位账号',
    occurred_at        TIMESTAMP   NOT NULL COMMENT '请求声明的精确业务时刻',
    created_at         TIMESTAMP   NOT NULL COMMENT '落库时间',
    CONSTRAINT uk_plan_request UNIQUE (request_id),
    CONSTRAINT uk_plan_version UNIQUE (flight_id, plan_version),
    CONSTRAINT fk_plan_manifest FOREIGN KEY (manifest_id) REFERENCES manifest_version (id)
);
CREATE INDEX IF NOT EXISTS idx_plan_manifest ON cargo_loading_plan (manifest_id);

-- 装配图逐项：唯一集装器编号、舱位、非负皮重（kg）、正净重（kg）；同图器号与舱位均唯一
CREATE TABLE IF NOT EXISTS cargo_loading_item (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id          BIGINT      NOT NULL COMMENT '所属装配图 ID',
    seq_no           INT         NOT NULL COMMENT '提交集合中的顺序，自 0 起',
    uld_no           VARCHAR(20) NOT NULL COMMENT '集装器唯一编号（前 3 位为类型代码）',
    compartment_code VARCHAR(20) NOT NULL COMMENT '装载舱位代码',
    arm_mm           INT         NOT NULL COMMENT '提交时舱位力臂快照，整数毫米',
    tare_kg          INT         NOT NULL COMMENT '皮重，非负整数公斤',
    net_kg           INT         NOT NULL COMMENT '净重，正整数公斤',
    gross_kg         INT         NOT NULL COMMENT '含皮重总重=皮重+净重，整数公斤',
    CONSTRAINT fk_item_plan FOREIGN KEY (plan_id) REFERENCES cargo_loading_plan (id),
    CONSTRAINT uk_item_uld UNIQUE (plan_id, uld_no),
    CONSTRAINT uk_item_compartment UNIQUE (plan_id, compartment_code)
);
CREATE INDEX IF NOT EXISTS idx_item_uld ON cargo_loading_item (uld_no);

-- 实物占用：同一集装器在未关闭航班间唯一；关闭放行原子释放（active_flag 置 NULL）
CREATE TABLE IF NOT EXISTS cargo_uld_occupancy (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    uld_no      VARCHAR(20) NOT NULL COMMENT '集装器唯一编号',
    flight_id   BIGINT      NOT NULL COMMENT '占用航班 ID',
    manifest_id BIGINT      NOT NULL COMMENT '占用时舱单版本 ID',
    plan_id     BIGINT      NOT NULL COMMENT '占用时装配图 ID',
    active_flag BIGINT      NULL COMMENT '占用哨兵：占用=1，已释放=NULL',
    occupied_at TIMESTAMP   NOT NULL COMMENT '占用时间',
    released_at TIMESTAMP   NULL COMMENT '释放时间；NULL 为仍占用',
    CONSTRAINT fk_occ_plan FOREIGN KEY (plan_id) REFERENCES cargo_loading_plan (id),
    CONSTRAINT uk_uld_active UNIQUE (uld_no, active_flag)
);
CREATE INDEX IF NOT EXISTS idx_occ_flight ON cargo_uld_occupancy (flight_id, active_flag);
