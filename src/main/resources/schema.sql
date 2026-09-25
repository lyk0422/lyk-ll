-- 临床试验随机入组与揭盲控制服务：H2（MODE=MySQL）建表脚本
-- 仅在嵌入式数据库（H2）下由 spring.sql.init 自动执行；外部 MySQL 不由本脚本管理。
-- 时间列一律存储 UTC 时刻；“日界”相关比较在应用层按 UTC 日历日换算。

CREATE TABLE IF NOT EXISTS authorized_user (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id       VARCHAR(64)  NOT NULL,
    display_name  VARCHAR(128) NOT NULL,
    enabled       TINYINT(1)   NOT NULL DEFAULT 1,
    created_at_utc TIMESTAMP   NOT NULL,
    CONSTRAINT uq_authorized_user_user_id UNIQUE (user_id)
);

-- 中心随机种子（合成数据，每个“试验+中心”首次入组时生成一次，用于区组置换）
CREATE TABLE IF NOT EXISTS site_random_seed (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    trial_id      VARCHAR(64) NOT NULL,
    site_code     VARCHAR(64) NOT NULL,
    seed_value    BIGINT      NOT NULL,
    created_at_utc TIMESTAMP   NOT NULL,
    CONSTRAINT uq_site_seed UNIQUE (trial_id, site_code)
);

-- 中心随机号发放计数器；独立事务提交，失败/回滚不重用已发随机号
CREATE TABLE IF NOT EXISTS site_random_sequence (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    trial_id      VARCHAR(64) NOT NULL,
    site_code     VARCHAR(64) NOT NULL,
    current_no    BIGINT      NOT NULL DEFAULT 0,
    created_at_utc TIMESTAMP   NOT NULL,
    updated_at_utc TIMESTAMP   NOT NULL,
    CONSTRAINT uq_site_sequence UNIQUE (trial_id, site_code)
);

-- 中心入组串行锁：与随机号序列分离，业务事务持锁期间仍可在独立事务中发号而不死锁
CREATE TABLE IF NOT EXISTS site_enrollment_lock (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    trial_id      VARCHAR(64) NOT NULL,
    site_code     VARCHAR(64) NOT NULL,
    created_at_utc TIMESTAMP   NOT NULL,
    CONSTRAINT uq_site_enrollment_lock UNIQUE (trial_id, site_code)
);

-- 受试者：同一试验内筛选号唯一；随机号在中心内唯一且递增
CREATE TABLE IF NOT EXISTS trial_subject (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    trial_id            VARCHAR(64) NOT NULL,
    site_code           VARCHAR(64) NOT NULL,
    subject_screen_no   VARCHAR(64) NOT NULL,
    randomization_no    BIGINT      NOT NULL,
    blinded_group_code  VARCHAR(8)  NOT NULL,
    assigned_group      VARCHAR(16) NOT NULL,
    consent_at_utc      TIMESTAMP   NOT NULL,
    enrolled_at_utc     TIMESTAMP   NOT NULL,
    unblinded_at_utc    TIMESTAMP,
    status              VARCHAR(16) NOT NULL DEFAULT 'ENROLLED',
    CONSTRAINT uq_subject_trial_screen UNIQUE (trial_id, subject_screen_no),
    CONSTRAINT uq_subject_trial_site_randno UNIQUE (trial_id, site_code, randomization_no)
);

-- 入组申请幂等记录：requestId 唯一；指纹不一致即冲突
CREATE TABLE IF NOT EXISTS enrollment_request (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id          VARCHAR(64) NOT NULL,
    trial_id            VARCHAR(64) NOT NULL,
    subject_screen_no   VARCHAR(64) NOT NULL,
    site_code           VARCHAR(64) NOT NULL,
    consent_at_utc      TIMESTAMP   NOT NULL,
    fingerprint         VARCHAR(128) NOT NULL,
    randomization_no    BIGINT      NOT NULL,
    blinded_group_code  VARCHAR(8)  NOT NULL,
    created_at_utc      TIMESTAMP   NOT NULL,
    CONSTRAINT uq_enrollment_request_id UNIQUE (request_id)
);

-- 访视记录：excluded_from_main_analysis=1 表示排除出主要分析集（揭盲后访视）
CREATE TABLE IF NOT EXISTS visit_record (
    id                          BIGINT AUTO_INCREMENT PRIMARY KEY,
    trial_id                    VARCHAR(64) NOT NULL,
    subject_screen_no           VARCHAR(64) NOT NULL,
    visit_at_utc                TIMESTAMP   NOT NULL,
    visit_type                  VARCHAR(32) NOT NULL,
    measure_value               DOUBLE,
    excluded_from_main_analysis TINYINT(1)  NOT NULL DEFAULT 0,
    exclusion_reason            VARCHAR(128),
    created_at_utc              TIMESTAMP   NOT NULL,
    CONSTRAINT uq_visit_subject_time UNIQUE (trial_id, subject_screen_no, visit_at_utc)
);

-- 揭盲申请：PENDING（仅第一授权人确认）→ COMPLETED（两名不同授权人确认后终态）
CREATE TABLE IF NOT EXISTS unblinding_request (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id      VARCHAR(64) NOT NULL,
    trial_id        VARCHAR(64) NOT NULL,
    subject_screen_no VARCHAR(64) NOT NULL,
    status          VARCHAR(16) NOT NULL,
    confirmer_a     VARCHAR(64),
    confirmed_a_at_utc TIMESTAMP,
    confirmer_b     VARCHAR(64),
    confirmed_b_at_utc TIMESTAMP,
    completed_at_utc TIMESTAMP,
    created_at_utc  TIMESTAMP   NOT NULL,
    CONSTRAINT uq_unblinding_request_id UNIQUE (request_id)
);

-- 揭盲审计事件：按自增 id 即发生顺序
CREATE TABLE IF NOT EXISTS unblinding_audit_event (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    trial_id            VARCHAR(64) NOT NULL,
    subject_screen_no   VARCHAR(64) NOT NULL,
    request_id          VARCHAR(64) NOT NULL,
    event_type          VARCHAR(32) NOT NULL,
    actor_user_id       VARCHAR(64) NOT NULL,
    detail              VARCHAR(512),
    created_at_utc      TIMESTAMP   NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_subject_trial_screen ON trial_subject (trial_id, subject_screen_no);
CREATE INDEX IF NOT EXISTS idx_visit_lookup ON visit_record (trial_id, subject_screen_no);
CREATE INDEX IF NOT EXISTS idx_audit_subject ON unblinding_audit_event (trial_id, subject_screen_no, id);
CREATE INDEX IF NOT EXISTS idx_unblinding_subject ON unblinding_request (trial_id, subject_screen_no, status);

COMMENT ON COLUMN trial_subject.blinded_group_code IS '盲态组别编码 A/B，不直接暴露试验/对照身份';
COMMENT ON COLUMN trial_subject.assigned_group IS '真实分组 TREATMENT/CONTROL，仅揭盲后可返回';
COMMENT ON COLUMN trial_subject.status IS '受试者状态 ENROLLED-在组盲态 / UNBLINDED-已揭盲终态';
COMMENT ON COLUMN visit_record.excluded_from_main_analysis IS '1=排除出主要分析集；受试者揭盲后（按 UTC 日界）的访视置 1';
COMMENT ON COLUMN unblinding_request.status IS 'PENDING-仅单人确认未生效 / COMPLETED-双人确认完成终态';
