-- 啤酒发酵批次温控与成熟度判定服务建表脚本（H2 MySQL 兼容模式）
-- 所有时间字段均为业务发生时间（Asia/Shanghai 本地时间，TIMESTAMP 无时区），由调用方传入，便于复算与测试。

CREATE TABLE IF NOT EXISTS fermentation_tank (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tank_code VARCHAR(64) NOT NULL COMMENT '发酵罐编号，全局唯一',
    capacity_liters INT NOT NULL COMMENT '罐容量，单位升',
    CONSTRAINT uk_tank_code UNIQUE (tank_code)
) COMMENT '发酵罐主数据（合成数据）';

CREATE TABLE IF NOT EXISTS process_curve (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    curve_code VARCHAR(64) NOT NULL COMMENT '工艺曲线编码',
    version INT NOT NULL COMMENT '曲线版本号，从 1 开始递增',
    status VARCHAR(16) NOT NULL COMMENT '曲线状态：ACTIVE 生效中 / SUPERSEDED 已被新版本取代',
    created_at TIMESTAMP NOT NULL COMMENT '曲线版本创建时间',
    CONSTRAINT uk_curve_version UNIQUE (curve_code, version)
) COMMENT '发酵工艺曲线（按版本管理，切换版本后旧版本只读保留以便复算）';

CREATE TABLE IF NOT EXISTS curve_segment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    curve_id BIGINT NOT NULL COMMENT '所属工艺曲线版本 ID',
    phase VARCHAR(24) NOT NULL COMMENT '阶段：MAIN_FERMENT 主酵 / COOLING 降温 / MATURATION 后熟',
    seq INT NOT NULL COMMENT '阶段内分段序号，从 1 开始，按经过时长依次生效',
    setpoint_celsius DECIMAL(4,2) NOT NULL COMMENT '本分段温度设定值，单位摄氏度，允许负值',
    duration_hours INT NOT NULL COMMENT '本分段持续时长，单位小时',
    CONSTRAINT fk_segment_curve FOREIGN KEY (curve_id) REFERENCES process_curve (id)
) COMMENT '工艺曲线分段设定（温度设定值与持续时长）';

CREATE TABLE IF NOT EXISTS fermentation_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no VARCHAR(64) NOT NULL COMMENT '批次号，全局唯一',
    tank_id BIGINT NOT NULL COMMENT '入罐发酵罐 ID',
    curve_code VARCHAR(64) NOT NULL COMMENT '使用的工艺曲线编码',
    curve_version INT NOT NULL COMMENT '当前生效的曲线版本号，降温指令事务内切换到最新版本',
    status VARCHAR(24) NOT NULL COMMENT '批次状态：FILLED 满罐 / MAIN_FERMENT 主酵 / COOLING 降温 / MATURATION 后熟 / JUDGED 判定合格待转出 / TRANSFERRED 清酒转出（终态） / REVIEW 整批复审（终态）',
    filled_at TIMESTAMP NOT NULL COMMENT '满罐时间',
    main_ferment_started_at TIMESTAMP NULL COMMENT '主酵开始时间，未开始为 NULL',
    cooling_started_at TIMESTAMP NULL COMMENT '降温指令执行时间，未执行为 NULL',
    maturation_started_at TIMESTAMP NULL COMMENT '后熟开始时间，未开始为 NULL',
    maturation_extended TINYINT NOT NULL DEFAULT 0 COMMENT '后熟是否已延长：0 未延长 / 1 已延长（仅允许一次），延长后要求后熟总时长 14 天',
    judged_at TIMESTAMP NULL COMMENT '最近一次成熟度判定时间，未判定为 NULL',
    transferred_at TIMESTAMP NULL COMMENT '清酒转出时间，未转出为 NULL',
    active_tank_key VARCHAR(64) NULL COMMENT '进行中批次占用发酵罐的占用键（等于罐编号），批次进入终态后置 NULL；唯一约束保证同罐同时刻仅一个进行中批次',
    CONSTRAINT uk_batch_no UNIQUE (batch_no),
    CONSTRAINT uk_batch_active_tank UNIQUE (active_tank_key),
    CONSTRAINT fk_batch_tank FOREIGN KEY (tank_id) REFERENCES fermentation_tank (id)
) COMMENT '发酵批次主表';

CREATE TABLE IF NOT EXISTS temperature_reading (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL COMMENT '所属批次 ID',
    curve_version INT NOT NULL COMMENT '采样时批次生效的曲线版本号，用于复算',
    phase VARCHAR(24) NOT NULL COMMENT '采样时批次所处阶段：MAIN_FERMENT / COOLING / MATURATION',
    sampled_at TIMESTAMP NOT NULL COMMENT '采样时间',
    temperature DECIMAL(5,2) NOT NULL COMMENT '实测温度，单位摄氏度',
    setpoint DECIMAL(5,2) NOT NULL COMMENT '采样时刻按曲线复算的温度设定值，单位摄氏度',
    deviation DECIMAL(5,2) NOT NULL COMMENT '绝对偏差 |实测-设定|，单位摄氏度；恰 0.50 判定为合格',
    out_of_tolerance TINYINT NOT NULL COMMENT '是否超差：0 否 / 1 是（偏差 > 0.5℃）',
    consecutive_out INT NOT NULL COMMENT '截至本采样点的连续超差点数',
    alarm_triggered TINYINT NOT NULL COMMENT '是否触发告警：0 否 / 1 是（连续超差点数达到 3，恰 3 点触发）',
    late TINYINT NOT NULL DEFAULT 0 COMMENT '是否迟到数据：0 否 / 1 是（批次已转出后到达，仅记录不参与判定）',
    CONSTRAINT fk_reading_batch FOREIGN KEY (batch_id) REFERENCES fermentation_batch (id)
) COMMENT '发酵温度采样记录';

CREATE TABLE IF NOT EXISTS diacetyl_test (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL COMMENT '所属批次 ID',
    tested_at TIMESTAMP NOT NULL COMMENT '检测时间',
    value_mg_per_liter DECIMAL(5,3) NOT NULL COMMENT '双乙酰含量，单位 mg/L；不超过 0.100 判定为合格',
    late TINYINT NOT NULL DEFAULT 0 COMMENT '是否迟到数据：0 否 / 1 是（批次已转出后到达，仅记录不参与判定）',
    CONSTRAINT fk_diacetyl_batch FOREIGN KEY (batch_id) REFERENCES fermentation_batch (id)
) COMMENT '双乙酰检测记录（检测链按检测时间排序可复算）';

CREATE TABLE IF NOT EXISTS yeast_unit (
    unit_code VARCHAR(64) NOT NULL COMMENT '合成酵母单元唯一编号',
    generation INT NOT NULL COMMENT '当前代次：登记初始 0，每次合格回收加 1；代次 6 不可启动新主酵，最大可用代次恰 5',
    status VARCHAR(16) NOT NULL COMMENT '单元状态：AVAILABLE 可用 / IN_USE 使用中（已绑定主酵批次）/ SCRAPPED 已报废不可再投酵',
    last_recovered_at TIMESTAMP NULL COMMENT '最近一次合格回收时刻；首次单元从未回收为 NULL，不受 72 小时时限约束',
    occupied_by_batch_id BIGINT NULL COMMENT '当前占用该单元的主酵批次 ID，未占用为 NULL；一单元同时刻仅供一个主酵批次',
    version INT NOT NULL COMMENT '乐观版本号，从 1 开始，每次成功变更更加 1',
    PRIMARY KEY (unit_code),
    CONSTRAINT fk_yeast_occupied_batch FOREIGN KEY (occupied_by_batch_id) REFERENCES fermentation_batch (id)
) COMMENT '合成酵母单元：代次限用、状态与最近回收时刻';

CREATE TABLE IF NOT EXISTS yeast_recovery (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    unit_code VARCHAR(64) NOT NULL COMMENT '回收的酵母单元编号',
    batch_id BIGINT NOT NULL COMMENT '发起回收的批次 ID；同批次（含失败批次）仅允许回收一次',
    recovered_at TIMESTAMP NOT NULL COMMENT '回收受理时刻（业务时间）',
    viability_percent DECIMAL(5,2) NOT NULL COMMENT '回收活率百分数，单位 %；恰 90.00 合格，低于 90.00 报废',
    contaminated TINYINT NOT NULL COMMENT '是否检出污染：0 无污染 / 1 污染（污染即报废）',
    qualified TINYINT NOT NULL COMMENT '回收判定：活率 ≥ 90% 且无污染为 1，否则 0',
    generation_after INT NOT NULL COMMENT '回收后代次：合格时代次加 1，报废时代次不变',
    status_after VARCHAR(16) NOT NULL COMMENT '回收后单元状态：合格 AVAILABLE / 不合格 SCRAPPED',
    created_at TIMESTAMP NOT NULL COMMENT '回收记录创建时间',
    CONSTRAINT uk_yeast_recovery_batch UNIQUE (batch_id),
    CONSTRAINT fk_recovery_yeast FOREIGN KEY (unit_code) REFERENCES yeast_unit (unit_code),
    CONSTRAINT fk_recovery_batch FOREIGN KEY (batch_id) REFERENCES fermentation_batch (id)
) COMMENT '酵母回收追溯记录（每批次最多一条，与解除批次占用同事务提交）';

CREATE TABLE IF NOT EXISTS idempotency_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(128) NOT NULL COMMENT '幂等请求键，全局唯一',
    endpoint VARCHAR(160) NOT NULL COMMENT '首次受理的接口路径',
    fingerprint VARCHAR(64) NOT NULL COMMENT '请求指纹：requestId 与全部业务字段的 SHA-256，同键异参返回 409',
    response_status INT NOT NULL COMMENT '首次受理返回的 HTTP 状态码',
    response_body CLOB NOT NULL COMMENT '首次受理返回的响应体（JSON），重放时原样返回',
    created_at TIMESTAMP NOT NULL COMMENT '首次受理时间',
    CONSTRAINT uk_idem_request UNIQUE (request_id)
) COMMENT '幂等受理记录';

CREATE TABLE IF NOT EXISTS late_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL COMMENT '所属批次 ID',
    event_type VARCHAR(32) NOT NULL COMMENT '迟到事件类型：TEMPERATURE_READING 温度采样 / DIACETYL_TEST 双乙酰检测',
    payload VARCHAR(512) NOT NULL COMMENT '迟到数据摘要（JSON）',
    created_at TIMESTAMP NOT NULL COMMENT '事件记录时间',
    CONSTRAINT fk_late_batch FOREIGN KEY (batch_id) REFERENCES fermentation_batch (id)
) COMMENT '批次终态后到达的迟到数据事件';
