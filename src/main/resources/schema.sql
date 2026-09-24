-- 临床试验随机入组与应急揭盲服务建表脚本（H2 MODE=MySQL 兼容）。
-- 所有时间字段均以 UTC 存储（应用层按 UTC 日界换算），空值含义见列注释。

CREATE TABLE IF NOT EXISTS trial (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    treat_code CHAR(1) NOT NULL,
    control_code CHAR(1) NOT NULL,
    CONSTRAINT uk_trial_code UNIQUE (code)
);
COMMENT ON TABLE trial IS '临床试验主表';
COMMENT ON COLUMN trial.code IS '试验编号（合成数据）';
COMMENT ON COLUMN trial.name IS '试验名称';
COMMENT ON COLUMN trial.treat_code IS '试验组对应的盲态编码';
COMMENT ON COLUMN trial.control_code IS '对照组对应的盲态编码';

CREATE TABLE IF NOT EXISTS center (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    CONSTRAINT uk_center_code UNIQUE (code)
);
COMMENT ON TABLE center IS '试验中心（分层随机分层因子）';
COMMENT ON COLUMN center.code IS '中心编号（合成数据）';
COMMENT ON COLUMN center.name IS '中心名称';

CREATE TABLE IF NOT EXISTS authorized_unblinder (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trial_id BIGINT NOT NULL,
    person_id VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    CONSTRAINT uk_unblinder UNIQUE (trial_id, person_id),
    CONSTRAINT fk_unblinder_trial FOREIGN KEY (trial_id) REFERENCES trial (id)
);
COMMENT ON TABLE authorized_unblinder IS '应急揭盲授权人名单';
COMMENT ON COLUMN authorized_unblinder.person_id IS '授权人工号（合成数据）';
COMMENT ON COLUMN authorized_unblinder.display_name IS '授权人姓名';

CREATE TABLE IF NOT EXISTS random_counter (
    trial_id BIGINT NOT NULL,
    center_id BIGINT NOT NULL,
    next_no BIGINT NOT NULL,
    PRIMARY KEY (trial_id, center_id),
    CONSTRAINT fk_counter_trial FOREIGN KEY (trial_id) REFERENCES trial (id),
    CONSTRAINT fk_counter_center FOREIGN KEY (center_id) REFERENCES center (id)
);
COMMENT ON TABLE random_counter IS '中心内随机号计数器；独立事务递增，已发出号码即使外层回滚也不重用';
COMMENT ON COLUMN random_counter.next_no IS '下一个待发放的随机号（中心内从 1 开始递增）';

CREATE TABLE IF NOT EXISTS randomization_block (
    trial_id BIGINT NOT NULL,
    center_id BIGINT NOT NULL,
    block_no BIGINT NOT NULL,
    position INT NOT NULL,
    arm CHAR(1) NOT NULL,
    PRIMARY KEY (trial_id, center_id, block_no, position),
    CONSTRAINT fk_block_trial FOREIGN KEY (trial_id) REFERENCES trial (id),
    CONSTRAINT fk_block_center FOREIGN KEY (center_id) REFERENCES center (id)
);
COMMENT ON TABLE randomization_block IS '区组随机分配计划；区组长度 4，每组 2:2，arm=T 试验组 / C 对照组';
COMMENT ON COLUMN randomization_block.block_no IS '区组序号（中心内从 0 开始）';
COMMENT ON COLUMN randomization_block.position IS '区组内位置 0-3';
COMMENT ON COLUMN randomization_block.arm IS '实际组别：T=试验组，C=对照组（盲态下不对外暴露）';

CREATE TABLE IF NOT EXISTS enrollment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trial_id BIGINT NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    screening_no VARCHAR(64) NOT NULL,
    center_id BIGINT NOT NULL,
    consent_signed_at TIMESTAMP NOT NULL,
    random_no BIGINT NOT NULL,
    blinded_arm CHAR(1) NOT NULL,
    actual_arm CHAR(1) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_enroll_request UNIQUE (trial_id, request_id),
    CONSTRAINT uk_enroll_screening UNIQUE (trial_id, screening_no),
    CONSTRAINT uk_enroll_random UNIQUE (trial_id, center_id, random_no),
    CONSTRAINT fk_enroll_trial FOREIGN KEY (trial_id) REFERENCES trial (id),
    CONSTRAINT fk_enroll_center FOREIGN KEY (center_id) REFERENCES center (id)
);
COMMENT ON TABLE enrollment IS '受试者随机入组记录；同一筛选号在同一试验中仅可入组一次';
COMMENT ON COLUMN enrollment.request_id IS '入组请求幂等键';
COMMENT ON COLUMN enrollment.fingerprint IS '幂等指纹：requestId+筛选号+中心+同意时刻（UTC）的散列';
COMMENT ON COLUMN enrollment.screening_no IS '受试者筛选号';
COMMENT ON COLUMN enrollment.consent_signed_at IS '知情同意签署时刻（UTC）';
COMMENT ON COLUMN enrollment.random_no IS '中心内递增随机号，发出后不因失败或回滚重用';
COMMENT ON COLUMN enrollment.blinded_arm IS '盲态组别编码（对外展示）';
COMMENT ON COLUMN enrollment.actual_arm IS '实际组别：T=试验组，C=对照组（仅揭盲后可见）';
COMMENT ON COLUMN enrollment.status IS '状态：ENROLLED=在组，UNBLINDED=已揭盲（终态）';
COMMENT ON COLUMN enrollment.created_at IS '入组时间（UTC）';

CREATE TABLE IF NOT EXISTS unblinding_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trial_id BIGINT NOT NULL,
    screening_no VARCHAR(64) NOT NULL,
    approver1 VARCHAR(64) NOT NULL,
    approver2 VARCHAR(64) NOT NULL,
    reason VARCHAR(512) NOT NULL,
    revealed_arm CHAR(1) NOT NULL,
    effective_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_unblind_screening UNIQUE (trial_id, screening_no),
    CONSTRAINT fk_unblind_trial FOREIGN KEY (trial_id) REFERENCES trial (id)
);
COMMENT ON TABLE unblinding_record IS '应急揭盲记录（终态，不可回退）；需两名不同授权人在同一事务内先后确认';
COMMENT ON COLUMN unblinding_record.approver1 IS '第一位确认授权人工号';
COMMENT ON COLUMN unblinding_record.approver2 IS '第二位确认授权人工号（须与第一位不同）';
COMMENT ON COLUMN unblinding_record.reason IS '揭盲原因';
COMMENT ON COLUMN unblinding_record.revealed_arm IS '揭盲后公开的实际组别：T=试验组，C=对照组';
COMMENT ON COLUMN unblinding_record.effective_at IS '揭盲生效时刻（UTC）';

CREATE TABLE IF NOT EXISTS visit_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trial_id BIGINT NOT NULL,
    screening_no VARCHAR(64) NOT NULL,
    visit_time TIMESTAMP NOT NULL,
    note VARCHAR(512),
    excluded_from_pas BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_visit_trial FOREIGN KEY (trial_id) REFERENCES trial (id)
);
COMMENT ON TABLE visit_record IS '受试者访视数据（合成数据）';
COMMENT ON COLUMN visit_record.visit_time IS '访视发生时刻（UTC）';
COMMENT ON COLUMN visit_record.note IS '访视备注，NULL 表示无备注';
COMMENT ON COLUMN visit_record.excluded_from_pas IS '是否排除出主要分析集：受试者揭盲后其后续访视数据置为 TRUE';
COMMENT ON COLUMN visit_record.created_at IS '记录创建时刻（UTC）';

CREATE TABLE IF NOT EXISTS audit_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trial_id BIGINT NOT NULL,
    screening_no VARCHAR(64) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    actor VARCHAR(64),
    detail VARCHAR(512),
    occurred_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_audit_trial FOREIGN KEY (trial_id) REFERENCES trial (id)
);
COMMENT ON TABLE audit_event IS '审计事件表，按 id 顺序即发生顺序可查询';
COMMENT ON COLUMN audit_event.event_type IS '事件类型：ENROLLED / UNBLIND_CONFIRM_1 / UNBLIND_CONFIRM_2 / UNBLINDED';
COMMENT ON COLUMN audit_event.actor IS '操作人工号，系统动作时为 NULL';
COMMENT ON COLUMN audit_event.detail IS '事件明细，NULL 表示无补充信息';
COMMENT ON COLUMN audit_event.occurred_at IS '事件发生时刻（UTC）';
