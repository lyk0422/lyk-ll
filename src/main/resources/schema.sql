-- 干扰投诉处置链建表脚本（H2 MySQL 兼容模式 / MySQL 均可执行）
-- 时间字段统一为 UTC 时间戳；功率单位为瓦(W)；场强单位为 dBuV。

CREATE TABLE IF NOT EXISTS transmitter (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '发射机主键',
    code VARCHAR(64) NOT NULL COMMENT '发射机编号（合成台账）',
    full_power_w INT NOT NULL COMMENT '额定满功率，单位 W',
    CONSTRAINT uk_transmitter_code UNIQUE (code)
) COMMENT '发射机合成台账';

CREATE TABLE IF NOT EXISTS transmitter_channel (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '频道映射主键',
    transmitter_id BIGINT NOT NULL COMMENT '发射机主键',
    frequency_mhz DECIMAL(10, 3) NOT NULL COMMENT '使用频率，单位 MHz；一台发射机可承载多个频道',
    CONSTRAINT uk_channel_frequency UNIQUE (frequency_mhz),
    CONSTRAINT fk_channel_transmitter FOREIGN KEY (transmitter_id) REFERENCES transmitter (id)
) COMMENT '发射机频道映射：频率到发射机的归属关系';

CREATE TABLE IF NOT EXISTS protection_limit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '保护表主键',
    frequency_mhz DECIMAL(10, 3) NOT NULL COMMENT '保护频率，单位 MHz',
    limit_w INT NOT NULL COMMENT '合成保护表给出的限功率值，单位 W',
    CONSTRAINT uk_protection_frequency UNIQUE (frequency_mhz)
) COMMENT '合成保护表：各频率允许的最大功率限值';

CREATE TABLE IF NOT EXISTS frequency_guard (
    frequency_mhz DECIMAL(10, 3) PRIMARY KEY COMMENT '保护频率，单位 MHz'
) COMMENT '频率级并发守卫：同事务内 SELECT FOR UPDATE 串行化事件创建';

CREATE TABLE IF NOT EXISTS interference_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '干扰事件主键',
    frequency_mhz DECIMAL(10, 3) NOT NULL COMMENT '受扰频率，单位 MHz',
    transmitter_id BIGINT NOT NULL COMMENT '关联发射机主键',
    status VARCHAR(16) NOT NULL COMMENT '事件状态：OPEN=待初查，CONFIRMED=已确认干扰并限功率，CLOSED=已结案',
    conclusion VARCHAR(16) NULL COMMENT '初查结论：CONFIRMED=确认干扰，CLEARED=排除干扰；NULL=尚未初查',
    confirmed_limit_w INT NULL COMMENT '初查确认时取自合成保护表的限值快照，单位 W；NULL=未确认干扰',
    trigger_complaint_id BIGINT NOT NULL COMMENT '触发本事件的第 3 条有效投诉主键',
    created_at TIMESTAMP NOT NULL COMMENT '事件创建时刻（UTC）',
    concluded_at TIMESTAMP NULL COMMENT '初查结论时刻（UTC）；NULL=未初查，满 24 小时升级督办',
    closed_at TIMESTAMP NULL COMMENT '结案时刻（UTC）；NULL=未结案',
    investigate_request_id VARCHAR(64) NULL COMMENT '初查处置幂等键；NULL=未初查',
    investigate_fingerprint VARCHAR(64) NULL COMMENT '初查处置请求指纹（SHA-256）',
    CONSTRAINT fk_event_transmitter FOREIGN KEY (transmitter_id) REFERENCES transmitter (id)
) COMMENT '干扰事件：由 30 分钟内 3 个不同监测站的有效投诉触发';

CREATE TABLE IF NOT EXISTS complaint (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '投诉主键',
    request_id VARCHAR(64) NOT NULL COMMENT '投诉登记幂等键',
    station_code VARCHAR(64) NOT NULL COMMENT '监测站编号',
    frequency_mhz DECIMAL(10, 3) NOT NULL COMMENT '实测频率，单位 MHz',
    sampled_at TIMESTAMP NOT NULL COMMENT '采样时刻（UTC）',
    field_strength_dbuv DECIMAL(8, 2) NOT NULL COMMENT '实测场强，单位 dBuV',
    evidence_id VARCHAR(128) NOT NULL COMMENT '合成证据标识；缺失时不允许登记',
    fingerprint VARCHAR(64) NOT NULL COMMENT '投诉全部业务字段指纹（SHA-256）',
    triggered_event_id BIGINT NULL COMMENT '本投诉触发创建的干扰事件主键；NULL=未触发',
    created_at TIMESTAMP NOT NULL COMMENT '登记时刻（UTC）',
    CONSTRAINT uk_complaint_request UNIQUE (request_id),
    CONSTRAINT uk_complaint_natural UNIQUE (station_code, frequency_mhz, sampled_at)
) COMMENT '干扰投诉登记：同监测站+频率+采样时刻只登记一次';

CREATE TABLE IF NOT EXISTS power_directive (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '功率指令主键',
    transmitter_id BIGINT NOT NULL COMMENT '目标发射机主键；同时刻仅允许一条 EXECUTING',
    status VARCHAR(16) NOT NULL COMMENT '指令状态：EXECUTING=执行中，LIFTED=已解除',
    current_limit_w INT NOT NULL COMMENT '当前限功率值，单位 W；取全部未结事件最严格限值',
    created_at TIMESTAMP NOT NULL COMMENT '指令创建时刻（UTC）',
    lifted_at TIMESTAMP NULL COMMENT '解除时刻（UTC）；NULL=仍在执行',
    lift_ops_confirmer VARCHAR(64) NULL COMMENT '解除确认人（运行岗）',
    lift_monitor_confirmer VARCHAR(64) NULL COMMENT '解除确认人（监测岗），须与运行岗不同人',
    CONSTRAINT fk_directive_transmitter FOREIGN KEY (transmitter_id) REFERENCES transmitter (id)
) COMMENT '限功率指令：同发射机同时刻最多一条执行中';

CREATE TABLE IF NOT EXISTS power_directive_revision (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '指令轨迹主键',
    directive_id BIGINT NOT NULL COMMENT '所属功率指令主键',
    event_id BIGINT NULL COMMENT '关联干扰事件主键；解除轨迹可为 NULL',
    change_type VARCHAR(24) NOT NULL COMMENT '轨迹类型：ISSUE=下达，TIGHTEN=收紧，RELAX=放宽至剩余最严限值，LIFT=解除',
    limit_w_after INT NOT NULL COMMENT '变更后限值，单位 W',
    created_at TIMESTAMP NOT NULL COMMENT '变更时刻（UTC）',
    note VARCHAR(256) NULL COMMENT '变更说明',
    CONSTRAINT fk_revision_directive FOREIGN KEY (directive_id) REFERENCES power_directive (id)
) COMMENT '功率指令轨迹：事件与指令变更全程可复算';

CREATE TABLE IF NOT EXISTS retest_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '复测记录主键',
    transmitter_id BIGINT NOT NULL COMMENT '复测发射机主键',
    directive_id BIGINT NOT NULL COMMENT '关联功率指令主键',
    actual_power_w INT NOT NULL COMMENT '复测实测功率，单位 W；恰等限值合法，超过拒绝确认',
    limit_w INT NOT NULL COMMENT '复测时执行限值，单位 W',
    result VARCHAR(16) NOT NULL COMMENT '复测结论：CLEAR=无干扰',
    measured_at TIMESTAMP NOT NULL COMMENT '复测采样时刻（UTC）',
    recorded_by VARCHAR(64) NOT NULL COMMENT '复测记录人',
    created_at TIMESTAMP NOT NULL COMMENT '记录落库时刻（UTC）',
    CONSTRAINT fk_retest_transmitter FOREIGN KEY (transmitter_id) REFERENCES transmitter (id),
    CONSTRAINT fk_retest_directive FOREIGN KEY (directive_id) REFERENCES power_directive (id)
) COMMENT '复测记录：与限功率解除在同一事务原子提交';
