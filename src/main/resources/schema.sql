-- 媒体版权窗口与播出授权：本地 H2（MODE=MySQL）建表脚本，仅合成数据。
-- 时间列统一存储 UTC 时刻（TIMESTAMP），窗口半开 [window_start, window_end)。

-- 授权线：一次创建一行；每次更正在其下新增版本，旧版本保留。
CREATE TABLE IF NOT EXISTS rights_grant (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    asset_id     VARCHAR(64)  NOT NULL,
    region       VARCHAR(32)  NOT NULL,
    platform     VARCHAR(32)  NOT NULL,
    version_no   INT          NOT NULL DEFAULT 0,
    created_at   TIMESTAMP    NOT NULL,
    updated_at   TIMESTAMP    NOT NULL
);

CREATE TABLE IF NOT EXISTS rights_grant_version (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    grant_id          BIGINT      NOT NULL,
    version_no        INT         NOT NULL,
    asset_id          VARCHAR(64) NOT NULL,
    region            VARCHAR(32) NOT NULL,
    platform          VARCHAR(32) NOT NULL,
    window_start      TIMESTAMP   NOT NULL,
    window_end        TIMESTAMP   NOT NULL,
    license_mode      VARCHAR(16) NOT NULL,
    status            VARCHAR(16) NOT NULL,
    request_id        VARCHAR(64) NOT NULL,
    created_at        TIMESTAMP   NOT NULL,
    revoked_at        TIMESTAMP   NULL,
    revoke_request_id VARCHAR(64) NULL,
    CONSTRAINT uk_grant_version UNIQUE (grant_id, version_no),
    CONSTRAINT fk_grant_version_grant FOREIGN KEY (grant_id) REFERENCES rights_grant (id)
);

-- 写请求幂等记录：指纹覆盖授权版本、窗口、排期集合与预期版本。
CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id    VARCHAR(64) PRIMARY KEY,
    operation     VARCHAR(32) NOT NULL,
    fingerprint   VARCHAR(128) NOT NULL,
    status_code   INT         NOT NULL,
    response_body CLOB        NOT NULL,
    created_at    TIMESTAMP   NOT NULL
);

-- 授权键空间串行锁：同 内容+地区+平台 的创建/更正/撤销/排期提交按库锁排队，消除幻读与竞态。
CREATE TABLE IF NOT EXISTS rights_key_lock (
    asset_id VARCHAR(64) NOT NULL,
    region   VARCHAR(32) NOT NULL,
    platform VARCHAR(32) NOT NULL,
    CONSTRAINT pk_rights_key_lock PRIMARY KEY (asset_id, region, platform)
);

CREATE TABLE IF NOT EXISTS play_schedule (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    schedule_id VARCHAR(64) NOT NULL,
    version_no  INT         NOT NULL,
    status      VARCHAR(16) NOT NULL,
    request_id  VARCHAR(64) NOT NULL,
    created_at  TIMESTAMP   NOT NULL,
    updated_at  TIMESTAMP   NOT NULL,
    CONSTRAINT uk_play_schedule_id UNIQUE (schedule_id)
);

CREATE TABLE IF NOT EXISTS play_schedule_item (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    schedule_id         VARCHAR(64) NOT NULL,
    item_no             INT         NOT NULL,
    asset_id            VARCHAR(64) NOT NULL,
    region              VARCHAR(32) NOT NULL,
    platform            VARCHAR(32) NOT NULL,
    slot_start          TIMESTAMP   NOT NULL,
    slot_end            TIMESTAMP   NOT NULL,
    bound_version_id    BIGINT      NOT NULL,
    bound_version_no    INT         NOT NULL,
    bound_window_start  TIMESTAMP   NOT NULL COMMENT 'UTC 绑定时授权窗口起快照（含），证据冻结不改写',
    bound_window_end    TIMESTAMP   NOT NULL COMMENT 'UTC 绑定时授权窗口止快照（不含），证据冻结不改写',
    bound_mode          VARCHAR(16) NOT NULL COMMENT '绑定时授权模式快照 EXCLUSIVE/NON_EXCLUSIVE',
    status              VARCHAR(16) NOT NULL,
    request_id          VARCHAR(64) NOT NULL,
    played_at           TIMESTAMP   NULL,
    updated_at          TIMESTAMP   NOT NULL,
    CONSTRAINT uk_schedule_item UNIQUE (schedule_id, item_no)
);

CREATE INDEX IF NOT EXISTS idx_grant_lookup
    ON rights_grant (asset_id, region, platform);
CREATE INDEX IF NOT EXISTS idx_grant_version_lookup
    ON rights_grant_version (asset_id, region, platform, status);
CREATE INDEX IF NOT EXISTS idx_grant_version_grant ON rights_grant_version (grant_id);
CREATE INDEX IF NOT EXISTS idx_schedule_item_bound ON play_schedule_item (bound_version_id);

COMMENT ON COLUMN rights_grant_version.status IS 'ACTIVE 当前有效；SUPERSEDED 已被更正替代；REVOKED 已撤销';
COMMENT ON COLUMN rights_grant_version.license_mode IS 'EXCLUSIVE 独占；NON_EXCLUSIVE 非独占';
COMMENT ON COLUMN rights_grant_version.window_start IS 'UTC 授权窗口起（含）';
COMMENT ON COLUMN rights_grant_version.window_end IS 'UTC 授权窗口止（不含）';
COMMENT ON COLUMN play_schedule.status IS 'COMMITTED 整笔提交成功';
COMMENT ON COLUMN play_schedule_item.status IS 'BOUND 已绑定授权；BLOCKED 授权撤销且未开播；PLAYED 已完成播出，证据冻结';
COMMENT ON COLUMN play_schedule_item.played_at IS 'UTC 实际播出完成时刻；未播出为空';
