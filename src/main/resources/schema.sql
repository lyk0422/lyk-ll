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
    biz_type      VARCHAR(16) NOT NULL COMMENT '业务类型：ORDER/REPORT/PLAN/PLAN_ORDER',
    fingerprint   VARCHAR(64) NOT NULL COMMENT '覆盖 requestId 与全部业务字段的指纹',
    ref_id        BIGINT COMMENT '首建业务实体 id',
    response_body CLOB NOT NULL COMMENT '首次响应 JSON，重放时原样返回',
    created_at    TIMESTAMP NOT NULL COMMENT '首次受理时刻',
    CONSTRAINT uk_idempotent_request UNIQUE (request_id)
);

-- 日前计划版本表：发布即不可变；current_flag 仅当前版本为 'Y'，历史版本为 NULL，
-- 配合唯一索引保证同风场同 UTC 日期至多一个当前版本；新版发布原子替换指针并保留历史。
CREATE TABLE IF NOT EXISTS day_ahead_plan_version (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    farm_code     VARCHAR(64) NOT NULL COMMENT '风场编码',
    plan_date     DATE NOT NULL COMMENT '计划适用的 UTC 日期',
    version_no    INT NOT NULL COMMENT '同风场同日期版本号，从 1 开始，成功发布加 1',
    current_flag  CHAR(1) COMMENT '当前版本标记 Y；历史版本为 NULL（唯一索引允许多个 NULL）',
    request_id    VARCHAR(96) NOT NULL COMMENT '发布请求幂等键',
    fingerprint   VARCHAR(64) NOT NULL COMMENT '覆盖 requestId、版本、96 段集合与精确时刻的结构化指纹',
    published_at  TIMESTAMP NOT NULL COMMENT '发布时刻（UTC 存储）',
    CONSTRAINT uk_plan_version_no UNIQUE (farm_code, plan_date, version_no),
    CONSTRAINT uk_plan_current UNIQUE (farm_code, plan_date, current_flag),
    CONSTRAINT uk_plan_request UNIQUE (request_id)
);

-- 日前计划段明细表：每版本完整 96 个十五分钟半开时段（segment_index 0..95，UTC），
-- 每段覆盖全场每台机组一条非负整数 kW 核定计划；版本不可变，明细随之不可变。
CREATE TABLE IF NOT EXISTS day_ahead_plan_segment (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    version_id    BIGINT NOT NULL COMMENT '所属计划版本 id',
    segment_index INT NOT NULL COMMENT '时段序号 0..95：[date 00:00UTC + 15i 分, +15 分) 半开区间',
    turbine_id    BIGINT NOT NULL COMMENT '机组 id',
    power_kw      INT NOT NULL COMMENT '该机组该段核定计划出力，单位 kW，非负且不超过合成装机容量',
    CONSTRAINT uk_plan_segment_turbine UNIQUE (version_id, segment_index, turbine_id)
);
CREATE INDEX IF NOT EXISTS idx_plan_segment_version ON day_ahead_plan_segment(version_id);

-- 计划绑定增量列（旧指令为 NULL，旧入口行为不变）。
-- 绑定日前计划的限电指令：冻结受理时的版本 id/版本号、UTC 日期与时段序号。
ALTER TABLE curtail_order ADD COLUMN IF NOT EXISTS plan_version_id BIGINT COMMENT '绑定的日前计划版本 id，NULL 表示未绑定计划的旧模式指令';
ALTER TABLE curtail_order ADD COLUMN IF NOT EXISTS plan_version_no INT COMMENT '绑定的计划版本号（冗余留证），NULL 表示未绑定';
ALTER TABLE curtail_order ADD COLUMN IF NOT EXISTS plan_date DATE COMMENT '绑定计划的 UTC 日期，NULL 表示未绑定';
ALTER TABLE curtail_order ADD COLUMN IF NOT EXISTS segment_index INT COMMENT '绑定计划的时段序号 0..95（UTC 半开区间），NULL 表示未绑定';

-- 计划绑定指令的分摊留证：计划核定出力与该轮实时可用出力分别冻结；
-- base_available_kw 即参与出力 = min(实时可用, 核定计划)；超时重分摊仍引用原版本计划值，仅刷新实时值。
ALTER TABLE allocation ADD COLUMN IF NOT EXISTS planned_power_kw INT COMMENT '该轮冻结的日前核定计划出力，单位 kW，NULL 表示未绑定计划的旧指令';
ALTER TABLE allocation ADD COLUMN IF NOT EXISTS realtime_available_kw INT COMMENT '该轮时刻的机组实时可用出力，单位 kW，NULL 表示未绑定计划的旧指令';

-- 机组可用状态列：受控分段计划每分钟事务内重查；任一机组 UNAVAILABLE 则该分钟不执行、计划暂停。
ALTER TABLE wind_turbine ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE'
    COMMENT '机组可用状态：AVAILABLE/UNAVAILABLE；受控分段计划每分钟重查，旧入口不读取';

-- 受控分段计划表：可选分段执行模式；每指令至多一份，旧未启用指令无此记录、行为不变。
-- 版本从 1 起：调用方修改带 expectedVersion、成功加 1；超时重分配亦加 1 并重排守斜率剩余阶梯。
CREATE TABLE IF NOT EXISTS staged_plan (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id         BIGINT NOT NULL COMMENT '所属限电指令 id（每指令至多一份分段计划）',
    plan_version     INT NOT NULL COMMENT '计划版本，从 1 起，成功修改/超时重分配加 1',
    status           VARCHAR(16) NOT NULL COMMENT '状态：EXECUTING/PAUSED/COMPLETED/CANCELED',
    start_at         TIMESTAMP NOT NULL COMMENT '当前版本启动 UTC 时刻；回执分钟时刻=启动时刻+序号分钟',
    current_minute   INT NOT NULL COMMENT '当前版本已确认分钟序号，0 表示尚未确认任何分钟',
    duration_minutes INT NOT NULL COMMENT '当前版本期限分钟数（登记时取各参与机组期限最大值）',
    created_at       TIMESTAMP NOT NULL COMMENT '创建时刻',
    updated_at       TIMESTAMP NOT NULL COMMENT '最近状态/进度/版本变更时刻',
    CONSTRAINT uk_staged_plan_order UNIQUE (order_id)
);

-- 分段计划参与机组表：冻结起始功率、最终目标与斜率；occupied_flag 仅占用中为 'Y'，
-- 配合唯一索引保证每机组同一时刻至多一份执行中分段计划；完成/取消/指令作废时释放为 NULL。
CREATE TABLE IF NOT EXISTS staged_plan_turbine (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id          BIGINT NOT NULL COMMENT '所属分段计划 id',
    turbine_id       BIGINT NOT NULL COMMENT '机组 id',
    start_power_kw   INT NOT NULL COMMENT '当前版本起始整数 kW 实际功率（登记值或重排时最新实际功率）',
    target_power_kw  INT NOT NULL COMMENT '当前版本冻结的最终分摊目标，单位 kW',
    ramp_kw_per_min  INT NOT NULL COMMENT '每分钟正整数升降上限，单位 kW',
    deadline_minutes INT NOT NULL COMMENT '登记期限 1..15 分钟',
    occupied_flag    CHAR(1) COMMENT '占用标记 Y；非占用为 NULL（唯一索引允许多个 NULL）',
    CONSTRAINT uk_staged_plan_turbine UNIQUE (plan_id, turbine_id),
    CONSTRAINT uk_staged_turbine_occupied UNIQUE (turbine_id, occupied_flag)
);

-- 阶梯明细表：每版本每分钟每机组一行冻结功率，每分钟向目标移动不超过升降上限、最后恰到目标；
-- 历史版本阶梯保留可复算。
CREATE TABLE IF NOT EXISTS staged_plan_step (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id      BIGINT NOT NULL COMMENT '所属分段计划 id',
    plan_version INT NOT NULL COMMENT '所属计划版本',
    minute_no    INT NOT NULL COMMENT '分钟序号，从 1 起',
    turbine_id   BIGINT NOT NULL COMMENT '机组 id',
    power_kw     INT NOT NULL COMMENT '该分钟冻结阶梯功率，单位 kW',
    CONSTRAINT uk_staged_step UNIQUE (plan_id, plan_version, minute_no, turbine_id)
);
CREATE INDEX IF NOT EXISTS idx_staged_step_plan ON staged_plan_step(plan_id, plan_version, minute_no);

-- 分钟确认表：整分钟一起确认后登记；缺一机组零更新（无任何记录与实际功率变更）。
CREATE TABLE IF NOT EXISTS staged_minute_confirm (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id      BIGINT NOT NULL COMMENT '所属分段计划 id',
    plan_version INT NOT NULL COMMENT '所属计划版本',
    minute_no    INT NOT NULL COMMENT '确认的分钟序号',
    request_id   VARCHAR(96) NOT NULL COMMENT '回执请求幂等键',
    confirmed_at TIMESTAMP NOT NULL COMMENT '确认时刻（UTC 存储）',
    CONSTRAINT uk_staged_confirm UNIQUE (plan_id, plan_version, minute_no)
);
