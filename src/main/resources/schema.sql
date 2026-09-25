-- 风电场限电出力分配服务：H2 MySQL 兼容模式自动建表（合成数据，仅存于 JVM 生命周期）。

-- 风场表：接收新指令时对风场行加行锁，保证同风场并发接收唯一受理。
CREATE TABLE IF NOT EXISTS wind_farm (
    farm_code   VARCHAR(64) PRIMARY KEY COMMENT '风场编码',
    farm_name   VARCHAR(128) NOT NULL COMMENT '风场名称',
    created_at  TIMESTAMP NOT NULL COMMENT '创建时刻'
);

-- 机组表：技术出力下限固定为额定容量的 15%；可用出力随最新回报刷新。
CREATE TABLE IF NOT EXISTS wind_turbine (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    farm_code          VARCHAR(64) NOT NULL COMMENT '所属风场编码',
    turbine_code       VARCHAR(64) NOT NULL COMMENT '机组编码（风场内唯一）',
    rated_power_kw     INT NOT NULL COMMENT '额定装机容量，单位 kW',
    technical_min_kw   INT NOT NULL COMMENT '技术出力下限，额定容量的 15%，单位 kW',
    available_power_kw INT NOT NULL COMMENT '最新可用出力，单位 kW',
    updated_at         TIMESTAMP NOT NULL COMMENT '可用出力最近更新时刻',
    CONSTRAINT uk_turbine_farm_code UNIQUE (farm_code, turbine_code)
);

-- 限电指令表：active_flag 仅执行中指令为 'Y'，其余为 NULL，配合唯一索引保证同风场至多一个执行中指令。
CREATE TABLE IF NOT EXISTS curtail_order (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no              VARCHAR(64) NOT NULL COMMENT '指令单号',
    farm_code             VARCHAR(64) NOT NULL COMMENT '风场编码',
    request_id            VARCHAR(96) NOT NULL COMMENT '接收请求幂等键',
    fingerprint           VARCHAR(64) NOT NULL COMMENT '覆盖 requestId 与全部业务字段的 SHA-256 指纹',
    target_power_kw       INT NOT NULL COMMENT '限电后全场目标出力，单位 kW',
    status                VARCHAR(16) NOT NULL COMMENT '状态：RECEIVED/ALLOCATED/DISPATCHED/SUPERSEDED/SETTLED',
    current_cycle_no      INT NOT NULL COMMENT '当前执行轮次，初值 1，告警重分摊递增',
    superseded_by_order_id BIGINT COMMENT '作废本指令的新指令 id，NULL 表示未被切换',
    active_flag           CHAR(1) COMMENT '执行中标记 Y；非执行中为 NULL（唯一索引允许多个 NULL）',
    received_at           TIMESTAMP NOT NULL COMMENT '接收时刻',
    settled_at            TIMESTAMP COMMENT '日结封存时刻，NULL 表示未封存',
    CONSTRAINT uk_order_no UNIQUE (order_no),
    CONSTRAINT uk_order_request UNIQUE (request_id),
    CONSTRAINT uk_order_active_farm UNIQUE (farm_code, active_flag)
);

-- 下发轮次表：每轮 due_at = dispatched_at + 10 分钟，恰满即判定。
CREATE TABLE IF NOT EXISTS dispatch_cycle (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id       BIGINT NOT NULL COMMENT '所属指令 id',
    cycle_no       INT NOT NULL COMMENT '轮次序号，从 1 开始',
    dispatched_at  TIMESTAMP NOT NULL COMMENT '下发时刻（Asia/Shanghai）',
    due_at         TIMESTAMP NOT NULL COMMENT '下发满 10 分钟判定时刻',
    CONSTRAINT uk_cycle_no UNIQUE (order_id, cycle_no)
);

-- 分摊明细表：各轮单机分摊，目标出力不低于技术下限，全场合计恰等于指令目标。
CREATE TABLE IF NOT EXISTS allocation (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id           BIGINT NOT NULL COMMENT '所属指令 id',
    cycle_no           INT NOT NULL COMMENT '所属轮次',
    turbine_id         BIGINT NOT NULL COMMENT '机组 id',
    base_available_kw  INT NOT NULL COMMENT '分摊时机组可用出力，单位 kW',
    target_power_kw    INT NOT NULL COMMENT '分摊后单机保留出力目标，单位 kW，不低于技术下限',
    curtail_kw         INT NOT NULL COMMENT '单机压减出力，单位 kW'
);

-- 执行回报表：按机组＋出力时刻去重，request_id 幂等。
CREATE TABLE IF NOT EXISTS execution_report (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id   VARCHAR(96) NOT NULL COMMENT '回报请求幂等键',
    order_id     BIGINT NOT NULL COMMENT '归属指令 id',
    turbine_id   BIGINT NOT NULL COMMENT '机组 id',
    measured_at  TIMESTAMP NOT NULL COMMENT '回报中的出力时刻（UTC 存储）',
    power_kw     INT NOT NULL COMMENT '实测出力，单位 kW',
    received_at  TIMESTAMP NOT NULL COMMENT '服务收到时刻（UTC 存储）',
    CONSTRAINT uk_report_request UNIQUE (request_id),
    CONSTRAINT uk_report_turbine_time UNIQUE (turbine_id, measured_at)
);

-- 告警链：每轮至多一条，重分摊后回填新轮次号。
CREATE TABLE IF NOT EXISTS alert_event (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id                BIGINT NOT NULL COMMENT '所属指令 id',
    cycle_no                INT NOT NULL COMMENT '触发告警的轮次',
    type                    VARCHAR(32) NOT NULL COMMENT '告警类型：TARGET_NOT_REACHED',
    fired_at                TIMESTAMP NOT NULL COMMENT '告警触发时刻',
    farm_actual_kw          INT COMMENT '告警时刻全场实测出力合计，单位 kW',
    reallocated_cycle_no    INT COMMENT '触发的重分摊新轮次号，NULL 表示尚未重分摊',
    CONSTRAINT uk_alert_cycle UNIQUE (order_id, cycle_no)
);

-- 封存后迟到回报：不进入执行曲线，仅登记事件。
CREATE TABLE IF NOT EXISTS late_report_event (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id     BIGINT NOT NULL COMMENT '目标（已封存/作废）指令 id',
    turbine_code VARCHAR(64) NOT NULL COMMENT '机组编码',
    measured_at  TIMESTAMP NOT NULL COMMENT '回报出力时刻',
    power_kw     INT COMMENT '实测出力，单位 kW',
    request_id   VARCHAR(96) COMMENT '回报请求幂等键',
    recorded_at  TIMESTAMP NOT NULL COMMENT '登记为迟到事件的时刻'
);

-- 幂等记录表：request_id 唯一，保存首次响应体供重放。
CREATE TABLE IF NOT EXISTS idempotent_record (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id    VARCHAR(96) NOT NULL COMMENT '请求幂等键',
    biz_type      VARCHAR(16) NOT NULL COMMENT '业务类型：ORDER/REPORT',
    fingerprint   VARCHAR(64) NOT NULL COMMENT '覆盖 requestId 与全部业务字段的指纹',
    ref_id        BIGINT COMMENT '首建业务实体 id',
    response_body CLOB NOT NULL COMMENT '首次响应 JSON，重放时原样返回',
    created_at    TIMESTAMP NOT NULL COMMENT '首次受理时刻',
    CONSTRAINT uk_idempotent_request UNIQUE (request_id)
);
