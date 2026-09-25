-- 临床试验随机入组与应急揭盲：H2（MODE=MySQL）自动建表脚本
-- 所有时刻均以 UTC 存储；随机号、区组状态、揭盲事件全部落库，禁止内存模拟。

CREATE TABLE IF NOT EXISTS trial_site (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    trial_code      VARCHAR(64)  NOT NULL,
    center_code     VARCHAR(64)  NOT NULL,
    center_name     VARCHAR(128),
    random_seed     BIGINT       NOT NULL,
    mask_a_group    VARCHAR(16)  NOT NULL,
    created_at_utc  TIMESTAMP    NOT NULL,
    CONSTRAINT uq_site_trial_center UNIQUE (trial_code, center_code)
);

CREATE TABLE IF NOT EXISTS subject_enrollment (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    trial_code        VARCHAR(64) NOT NULL,
    center_code       VARCHAR(64) NOT NULL,
    screening_no      VARCHAR(64) NOT NULL,
    request_id        VARCHAR(64) NOT NULL,
    consent_time_utc  TIMESTAMP   NOT NULL,
    random_no         BIGINT      NOT NULL,
    block_no          BIGINT      NOT NULL,
    position_in_block INT         NOT NULL,
    group_code        VARCHAR(16) NOT NULL,
    blinded_code      VARCHAR(16) NOT NULL,
    status            VARCHAR(16) NOT NULL,
    fingerprint       VARCHAR(64) NOT NULL,
    enrolled_at_utc   TIMESTAMP   NOT NULL,
    unblinded_at_utc  TIMESTAMP,
    CONSTRAINT uq_enroll_screening UNIQUE (trial_code, screening_no),
    CONSTRAINT uq_enroll_request   UNIQUE (trial_code, request_id),
    CONSTRAINT uq_enroll_random_no UNIQUE (trial_code, center_code, random_no)
);

CREATE TABLE IF NOT EXISTS center_allocation_state (
    trial_code          VARCHAR(64) NOT NULL,
    center_code         VARCHAR(64) NOT NULL,
    current_block_no    BIGINT      NOT NULL,
    allocated_in_block  INT         NOT NULL,
    current_block_pattern VARCHAR(8),
    prev_block_pattern  VARCHAR(8),
    updated_at_utc      TIMESTAMP   NOT NULL,
    CONSTRAINT pk_allocation_state PRIMARY KEY (trial_code, center_code),
    CONSTRAINT ck_allocation_pos CHECK (allocated_in_block BETWEEN 0 AND 3)
);

CREATE TABLE IF NOT EXISTS random_number_ledger (
    trial_code     VARCHAR(64) NOT NULL,
    center_code    VARCHAR(64) NOT NULL,
    next_random_no BIGINT      NOT NULL,
    CONSTRAINT pk_random_ledger PRIMARY KEY (trial_code, center_code)
);

CREATE TABLE IF NOT EXISTS authorized_unblinder (
    user_code      VARCHAR(64) PRIMARY KEY,
    user_name      VARCHAR(128) NOT NULL,
    created_at_utc TIMESTAMP    NOT NULL
);

CREATE TABLE IF NOT EXISTS unblind_event (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    trial_code        VARCHAR(64) NOT NULL,
    screening_no      VARCHAR(64) NOT NULL,
    request_id        VARCHAR(64) NOT NULL,
    status            VARCHAR(16) NOT NULL,
    first_confirmer   VARCHAR(64) NOT NULL,
    second_confirmer  VARCHAR(64) NOT NULL,
    created_at_utc     TIMESTAMP   NOT NULL,
    effective_at_utc  TIMESTAMP   NOT NULL,
    CONSTRAINT uq_unblind_request   UNIQUE (trial_code, request_id),
    CONSTRAINT uq_unblind_screening UNIQUE (trial_code, screening_no)
);

CREATE TABLE IF NOT EXISTS unblind_audit (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    trial_code     VARCHAR(64)  NOT NULL,
    screening_no   VARCHAR(64)  NOT NULL,
    event_type     VARCHAR(48)  NOT NULL,
    event_time_utc TIMESTAMP    NOT NULL,
    request_id     VARCHAR(64),
    operator       VARCHAR(64),
    detail         VARCHAR(512)
);

CREATE TABLE IF NOT EXISTS subject_visit (
    id                          BIGINT AUTO_INCREMENT PRIMARY KEY,
    trial_code                  VARCHAR(64)  NOT NULL,
    screening_no                VARCHAR(64)  NOT NULL,
    visit_code                  VARCHAR(64)  NOT NULL,
    visit_time_utc              TIMESTAMP    NOT NULL,
    excluded_from_main_analysis BOOLEAN      NOT NULL,
    recorded_at_utc             TIMESTAMP    NOT NULL,
    CONSTRAINT uq_visit UNIQUE (trial_code, screening_no, visit_code)
);

COMMENT ON TABLE trial_site IS '试验中心表：按中心分层，保存合成区组随机种子与盲态编码映射';
COMMENT ON COLUMN trial_site.random_seed IS '区组随机化合成种子（非外部随机系统），与中心共同决定各区组排列';
COMMENT ON COLUMN trial_site.mask_a_group IS '盲态编码 BLIND-A 对应的内部真实组别（TREATMENT/CONTROL），揭盲前对调用方保密';

COMMENT ON TABLE subject_enrollment IS '受试者随机入组记录（盲态底表）';
COMMENT ON COLUMN subject_enrollment.consent_time_utc IS '知情同意签署时刻，UTC 存储';
COMMENT ON COLUMN subject_enrollment.random_no IS '中心内递增发放的随机号，任何失败/回滚均不重用，允许因作废产生空号';
COMMENT ON COLUMN subject_enrollment.block_no IS '所属区组号（中心内从 1 开始，区组长度 4）';
COMMENT ON COLUMN subject_enrollment.position_in_block IS '区组内位置（0-3）';
COMMENT ON COLUMN subject_enrollment.group_code IS '内部真实组别 TREATMENT=试验组 / CONTROL=对照组，非盲字段';
COMMENT ON COLUMN subject_enrollment.blinded_code IS '对外盲态编码 BLIND-A/BLIND-B';
COMMENT ON COLUMN subject_enrollment.status IS '状态：ENROLLED=已入组未揭盲，UNBLINDED=已揭盲终态';
COMMENT ON COLUMN subject_enrollment.fingerprint IS '幂等指纹，覆盖 requestId/筛选号/中心/同意时刻';
COMMENT ON COLUMN subject_enrollment.unblinded_at_utc IS '揭盲生效时刻（UTC），未揭盲为空';

COMMENT ON TABLE center_allocation_state IS '中心区组分配状态：行锁串行化同中心并发入组，仅随提交推进';
COMMENT ON COLUMN center_allocation_state.current_block_no IS '当前进行中的区组号，从 1 开始';
COMMENT ON COLUMN center_allocation_state.allocated_in_block IS '当前区组已提交落组例数（0-3），满 4 后进入下一區组';
COMMENT ON COLUMN center_allocation_state.prev_block_pattern IS '上一完成区组的两组排列（T/C 序列，如 TCTC），空表示尚无完成区组；用于跨区组边界杜绝连续 4 例同组';
COMMENT ON COLUMN center_allocation_state.current_block_pattern IS '当前进行中区组的两组排列（T/C 长度 4），区组起始时确定，区内受试者按位置依次落组；进入新区组时清空重选';

COMMENT ON TABLE random_number_ledger IS '随机号独立发放台账：独立事务提前提交，外层回滚不回滚号段';
COMMENT ON COLUMN random_number_ledger.next_random_no IS '下一个待发放随机号（已发放最大值 + 1）';

COMMENT ON TABLE authorized_unblinder IS '应急揭盲授权人名单（合成数据）';
COMMENT ON TABLE unblind_event IS '应急揭盲事件，每个受试者至多一条生效事件，终态不可回退';
COMMENT ON COLUMN unblind_event.status IS '固定 EFFECTIVE：只有双人在同一事务内先后确认完成才会落表';

COMMENT ON TABLE unblind_audit IS '揭盲审计事件流，按 id（发生顺序）可查询';
COMMENT ON COLUMN unblind_audit.event_type IS '事件类型：UNBLIND_FIRST_CONFIRMED / UNBLIND_SECOND_CONFIRMED / UNBLIND_EFFECTIVE';

COMMENT ON TABLE subject_visit IS '受试者访视记录（合成）';
COMMENT ON COLUMN subject_visit.visit_time_utc IS '访视发生时刻，按 UTC 日界判定是否属于揭盲后访视';
COMMENT ON COLUMN subject_visit.excluded_from_main_analysis IS '是否排除出主要分析集：揭盲生效后访视置真，不可回退';
