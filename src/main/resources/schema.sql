-- 本地媒体版权窗口服务建表脚本（H2 MySQL 兼容模式；所有时间列均为 UTC）。
CREATE TABLE IF NOT EXISTS license_version (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    license_id   VARCHAR(64)  NOT NULL,
    version_no   INT          NOT NULL,
    asset_id     VARCHAR(64)  NOT NULL,
    region       VARCHAR(32)  NOT NULL,
    platform     VARCHAR(32)  NOT NULL,
    window_start DATETIME(3)  NOT NULL,
    window_end   DATETIME(3)  NOT NULL,
    license_mode VARCHAR(16)  NOT NULL,
    status       VARCHAR(16)  NOT NULL,
    created_at   DATETIME(3)  NOT NULL,
    revoked_at   DATETIME(3)  NULL,
    request_id   VARCHAR(64)  NOT NULL,
    CONSTRAINT uq_license_version UNIQUE (license_id, version_no),
    CONSTRAINT uq_license_request UNIQUE (request_id)
);

CREATE TABLE IF NOT EXISTS schedule_submission (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    submission_id VARCHAR(64) NOT NULL,
    request_id    VARCHAR(64) NOT NULL,
    item_count    INT         NOT NULL,
    created_at    DATETIME(3) NOT NULL,
    CONSTRAINT uq_submission_request UNIQUE (request_id),
    CONSTRAINT uq_submission_biz UNIQUE (submission_id)
);

CREATE TABLE IF NOT EXISTS schedule_item (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    submission_id    VARCHAR(64) NOT NULL,
    item_index       INT         NOT NULL,
    asset_id         VARCHAR(64) NOT NULL,
    region           VARCHAR(32) NOT NULL,
    platform         VARCHAR(32) NOT NULL,
    segment_start    DATETIME(3) NOT NULL,
    segment_end      DATETIME(3) NOT NULL,
    status           VARCHAR(16) NOT NULL,
    license_id       VARCHAR(64) NOT NULL,
    license_version  INT         NOT NULL,
    bound_at         DATETIME(3) NOT NULL,
    blocked_at       DATETIME(3) NULL,
    CONSTRAINT uq_schedule_item UNIQUE (submission_id, item_index)
);

CREATE TABLE IF NOT EXISTS playback_record (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    submission_id    VARCHAR(64) NOT NULL,
    item_index       INT         NOT NULL,
    air_start        DATETIME(3) NOT NULL,
    air_end          DATETIME(3) NOT NULL,
    license_id       VARCHAR(64) NOT NULL,
    license_version  INT         NOT NULL,
    window_start     DATETIME(3) NOT NULL,
    window_end       DATETIME(3) NOT NULL,
    license_mode     VARCHAR(16) NOT NULL,
    completed_at     DATETIME(3) NOT NULL,
    request_id       VARCHAR(64) NOT NULL,
    CONSTRAINT uq_playback_item UNIQUE (submission_id, item_index),
    CONSTRAINT uq_playback_request UNIQUE (request_id)
);

CREATE TABLE IF NOT EXISTS scope_lock (
    asset_id VARCHAR(64) NOT NULL,
    region   VARCHAR(32) NOT NULL,
    platform VARCHAR(32) NOT NULL,
    PRIMARY KEY (asset_id, region, platform)
);

COMMENT ON TABLE scope_lock IS '授权作用域串行锁：同内容/地区/平台的创建与更正在此行上排队，保证独占冲突检查无并发漏判';

CREATE TABLE IF NOT EXISTS request_ledger (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id    VARCHAR(64) NOT NULL,
    request_type  VARCHAR(32) NOT NULL,
    fingerprint   CHAR(64)    NOT NULL,
    status_code   INT         NOT NULL,
    response_json TEXT        NULL,
    created_at    DATETIME(3) NOT NULL,
    CONSTRAINT uq_request_ledger_request UNIQUE (request_id)
);

COMMENT ON TABLE license_version IS '授权版本表：更正生成新版本，旧版本保留为 SUPERSEDED，撤销为 REVOKED';
COMMENT ON COLUMN license_version.license_id IS '授权逻辑标识，同一授权的各版本共享';
COMMENT ON COLUMN license_version.version_no IS '授权版本号，从 1 起单调递增';
COMMENT ON COLUMN license_version.window_start IS '授权窗口起点（UTC，含）';
COMMENT ON COLUMN license_version.window_end IS '授权窗口终点（UTC，不含），半开区间 [start,end)';
COMMENT ON COLUMN license_version.license_mode IS 'EXCLUSIVE 独占 / NON_EXCLUSIVE 非独占';
COMMENT ON COLUMN license_version.status IS 'ACTIVE 有效 / SUPERSEDED 已被更正替换 / REVOKED 已撤销';
COMMENT ON COLUMN license_version.revoked_at IS '撤销时间（UTC）；NULL 表示未撤销';

COMMENT ON TABLE schedule_submission IS '排期提交批次：整笔成功或整笔零写入';
COMMENT ON TABLE schedule_item IS '排期时段：绑定时锁定授权版本，撤销后未开始项转为 BLOCKED';
COMMENT ON COLUMN schedule_item.segment_start IS '排期时段起点（UTC，含）';
COMMENT ON COLUMN schedule_item.segment_end IS '排期时段终点（UTC，不含）';
COMMENT ON COLUMN schedule_item.status IS 'BOUND 已绑定授权 / BLOCKED 授权撤销且尚未开始';
COMMENT ON COLUMN schedule_item.blocked_at IS '转为 BLOCKED 的时间（UTC）；NULL 表示未阻断';

COMMENT ON TABLE playback_record IS '播出完成回执：授权证据快照冻结，撤销/更正不追溯改写';
COMMENT ON COLUMN playback_record.air_start IS '实际播出起点（UTC，含），须与排期时段一致';
COMMENT ON COLUMN playback_record.air_end IS '实际播出终点（UTC，不含）';
COMMENT ON COLUMN playback_record.window_start IS '冻结的授权窗口起点（UTC，含）';
COMMENT ON COLUMN playback_record.window_end IS '冻结的授权窗口终点（UTC，不含）';
COMMENT ON COLUMN playback_record.completed_at IS '回执完成时间（UTC）';

COMMENT ON TABLE request_ledger IS '写请求幂等台账：同 requestId 同指纹返回首次结果，异指纹 409';
COMMENT ON COLUMN request_ledger.fingerprint IS 'SHA-256 指纹，覆盖授权版本、窗口、排期集合与预期版本';
COMMENT ON COLUMN request_ledger.response_json IS '首次请求的响应体 JSON 快照';
