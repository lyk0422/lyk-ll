-- 轧钢排产服务建表脚本（H2 MySQL 兼容模式，合成数据，仅 JVM 生命周期内有效）
-- 时间字段均为 Asia/Shanghai 本地时间（TIMESTAMP，无时区）；里程单位：公里；宽度单位：毫米。

CREATE TABLE IF NOT EXISTS production_line (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    line_code VARCHAR(64) NOT NULL COMMENT '产线编码，全局唯一',
    line_name VARCHAR(128) NOT NULL COMMENT '产线名称',
    CONSTRAINT uk_production_line_code UNIQUE (line_code)
) COMMENT '轧制产线（合成数据）';

CREATE TABLE IF NOT EXISTS schedule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '排产幂等请求号，由调用方生成',
    fingerprint VARCHAR(64) NOT NULL COMMENT '幂等指纹：requestId 与全部业务字段的 SHA-256',
    line_id BIGINT NOT NULL COMMENT '所属产线',
    unit_no VARCHAR(64) NOT NULL COMMENT '轧制单元号，同单元内版本递增',
    version INT NOT NULL COMMENT '排产版本号，从 1 开始；下达后修改生成新版本',
    status VARCHAR(16) NOT NULL COMMENT '状态：DRAFT 编制/VALIDATED 已校验/RELEASED 已下达/EXECUTING 执行中/FROZEN 已冻结（终态）',
    effective TINYINT NOT NULL DEFAULT 0 COMMENT '是否当前生效版本：1 生效，0 未生效或已被取代',
    locked TINYINT NOT NULL DEFAULT 0 COMMENT '实绩里程达换辊阈值后锁定：1 锁定，0 正常',
    roll_change_required TINYINT NOT NULL DEFAULT 0 COMMENT '是否需要换辊提示：1 需要，0 不需要',
    actual_mileage_km DECIMAL(12,3) NOT NULL DEFAULT 0 COMMENT '本换辊周期内已回传实绩累计里程（公里），换辊后清零',
    created_at TIMESTAMP NOT NULL COMMENT '创建时间（Asia/Shanghai）',
    updated_at TIMESTAMP NOT NULL COMMENT '最近更新时间（Asia/Shanghai）',
    CONSTRAINT uk_schedule_request UNIQUE (request_id),
    CONSTRAINT uk_schedule_unit_version UNIQUE (line_id, unit_no, version),
    CONSTRAINT fk_schedule_line FOREIGN KEY (line_id) REFERENCES production_line (id)
) COMMENT '排产单主表，按 编制→校验→下达→执行→冻结 流转';

CREATE TABLE IF NOT EXISTS schedule_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    schedule_id BIGINT NOT NULL COMMENT '所属排产单',
    seq INT NOT NULL COMMENT '轧制顺序号，单元内从 1 递增',
    contract_no VARCHAR(64) NOT NULL COMMENT '轧制合同号（合成数据）',
    width_mm INT NOT NULL COMMENT '成品宽度（毫米），须落在合成档位表内',
    mileage_km DECIMAL(12,3) NOT NULL COMMENT '计划轧制里程（公里）',
    warmup TINYINT NOT NULL DEFAULT 0 COMMENT '是否烫辊材：1 是，0 否；烫辊材须排在换辊后前 5 块',
    planned_start TIMESTAMP NOT NULL COMMENT '计划开始时间（Asia/Shanghai）',
    planned_end TIMESTAMP NOT NULL COMMENT '计划结束时间（Asia/Shanghai），半开区间 [start, end)',
    CONSTRAINT uk_schedule_item_seq UNIQUE (schedule_id, seq),
    CONSTRAINT fk_item_schedule FOREIGN KEY (schedule_id) REFERENCES schedule (id)
) COMMENT '排产单轧制明细';

CREATE TABLE IF NOT EXISTS roll_change_window (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    schedule_id BIGINT NOT NULL COMMENT '所属排产单',
    window_start TIMESTAMP NOT NULL COMMENT '换辊窗口开始（Asia/Shanghai），半开区间起点',
    window_end TIMESTAMP NOT NULL COMMENT '换辊窗口结束（Asia/Shanghai），半开区间终点',
    CONSTRAINT fk_window_schedule FOREIGN KEY (schedule_id) REFERENCES schedule (id)
) COMMENT '计划换辊窗口，与轧制作业按半开区间 [start, end) 判定冲突';

CREATE TABLE IF NOT EXISTS line_effective_schedule (
    line_id BIGINT PRIMARY KEY COMMENT '产线，主键保证同产线同时刻唯一生效版本',
    schedule_id BIGINT NOT NULL COMMENT '当前生效排产单',
    updated_at TIMESTAMP NOT NULL COMMENT '生效时间（Asia/Shanghai）',
    CONSTRAINT fk_effective_schedule FOREIGN KEY (schedule_id) REFERENCES schedule (id)
) COMMENT '产线当前生效排产版本，并发下达由主键保证唯一成功';

CREATE TABLE IF NOT EXISTS actual_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    schedule_id BIGINT NOT NULL COMMENT '所属排产单',
    contract_no VARCHAR(64) NOT NULL COMMENT '轧制合同号',
    pass_no INT NOT NULL COMMENT '道次号，与合同号联合去重归位',
    mileage_km DECIMAL(12,3) NOT NULL COMMENT '本道次实绩里程（公里）',
    occurred_at TIMESTAMP NOT NULL COMMENT '实绩发生时间（Asia/Shanghai）',
    recorded_at TIMESTAMP NOT NULL COMMENT '回传入库时间（Asia/Shanghai）',
    CONSTRAINT uk_actual_contract_pass UNIQUE (schedule_id, contract_no, pass_no),
    CONSTRAINT fk_actual_schedule FOREIGN KEY (schedule_id) REFERENCES schedule (id)
) COMMENT '轧制实绩，按 合同号+道次 去重';

CREATE TABLE IF NOT EXISTS late_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    schedule_id BIGINT NOT NULL COMMENT '所属已冻结排产单',
    contract_no VARCHAR(64) NOT NULL COMMENT '轧制合同号',
    pass_no INT NOT NULL COMMENT '道次号',
    mileage_km DECIMAL(12,3) NOT NULL COMMENT '迟到实绩里程（公里），不并入已冻结里程',
    occurred_at TIMESTAMP NOT NULL COMMENT '实绩发生时间（Asia/Shanghai）',
    recorded_at TIMESTAMP NOT NULL COMMENT '回传入库时间（Asia/Shanghai）',
    CONSTRAINT fk_late_schedule FOREIGN KEY (schedule_id) REFERENCES schedule (id)
) COMMENT '日冻结后迟到实绩事件，仅记录不改已冻结结论';

CREATE TABLE IF NOT EXISTS idempotency_key (
    request_id VARCHAR(64) PRIMARY KEY COMMENT '幂等请求号',
    fingerprint VARCHAR(64) NOT NULL COMMENT '幂等指纹：requestId 与全部业务字段的 SHA-256；同键异参返回 409',
    http_status INT NOT NULL COMMENT '首次响应的 HTTP 状态码',
    response_body CLOB NOT NULL COMMENT '首次响应报文快照，同键同参重放时原样返回',
    created_at TIMESTAMP NOT NULL COMMENT '首次受理时间（Asia/Shanghai）'
) COMMENT '幂等受理记录，覆盖排产与实绩回传入口';
