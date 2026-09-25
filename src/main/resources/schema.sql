-- 航班时刻协调服务建表脚本（H2 MySQL 兼容模式；IF NOT EXISTS 保证可重复执行）

-- 航季协调元数据：frozen 0=协调中 1=已发布冻结；frozen_at 为发布时间 epoch 毫秒(UTC)
CREATE TABLE IF NOT EXISTS slot_season (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    season     VARCHAR(16)  NOT NULL,
    frozen     TINYINT      NOT NULL DEFAULT 0,
    frozen_at  BIGINT,
    version    BIGINT       NOT NULL DEFAULT 0,
    created_at BIGINT       NOT NULL,
    CONSTRAINT uq_slot_season_season UNIQUE (season)
);
COMMENT ON TABLE slot_season IS '航季协调元数据表';
COMMENT ON COLUMN slot_season.season IS '航季标识，如 S26';
COMMENT ON COLUMN slot_season.frozen IS '发布冻结标记：0 协调中，1 已发布冻结';
COMMENT ON COLUMN slot_season.frozen_at IS '发布时间，epoch 毫秒(UTC)，未发布为空';
COMMENT ON COLUMN slot_season.version IS '乐观锁版本号';

-- 上一航季同系列实际执行率（合成数据）：execution_rate 为百分数，80.0 恰好视为达标
CREATE TABLE IF NOT EXISTS historical_performance (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    prev_season    VARCHAR(16)  NOT NULL,
    airline        VARCHAR(8)   NOT NULL,
    flight_no      VARCHAR(16)  NOT NULL,
    airport        VARCHAR(8)   NOT NULL,
    slot_time      VARCHAR(5)   NOT NULL,
    execution_rate DOUBLE       NOT NULL,
    CONSTRAINT uq_historical_key UNIQUE (prev_season, airline, flight_no, airport, slot_time)
);
COMMENT ON TABLE historical_performance IS '上一航季同系列实际执行率表（合成数据）';
COMMENT ON COLUMN historical_performance.prev_season IS '上一航季标识';
COMMENT ON COLUMN historical_performance.airline IS '航司二字码';
COMMENT ON COLUMN historical_performance.flight_no IS '航班号';
COMMENT ON COLUMN historical_performance.airport IS '协调机场三字码';
COMMENT ON COLUMN historical_performance.slot_time IS '原时刻 HH:mm，5 分钟粒度';
COMMENT ON COLUMN historical_performance.execution_rate IS '实际执行率(%)，取值 0~100；80.0 达标保留，79.9 及以下进公共池';

-- 系列申请：request_id 幂等键；fingerprint 覆盖 requestId、航班号、时刻与全部覆盖周规范化表示
CREATE TABLE IF NOT EXISTS slot_series (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id  VARCHAR(64)  NOT NULL,
    season      VARCHAR(16)  NOT NULL,
    airline     VARCHAR(8)   NOT NULL,
    flight_no   VARCHAR(16)  NOT NULL,
    airport     VARCHAR(8)   NOT NULL,
    slot_time   VARCHAR(5)   NOT NULL,
    weeks_csv   VARCHAR(255) NOT NULL,
    fingerprint VARCHAR(512) NOT NULL,
    decision    VARCHAR(16)  NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  BIGINT       NOT NULL,
    CONSTRAINT uq_slot_series_request UNIQUE (request_id)
);
COMMENT ON TABLE slot_series IS '时刻系列申请表';
COMMENT ON COLUMN slot_series.request_id IS '申请幂等键';
COMMENT ON COLUMN slot_series.season IS '航季标识';
COMMENT ON COLUMN slot_series.airline IS '申请航司二字码';
COMMENT ON COLUMN slot_series.flight_no IS '航班号';
COMMENT ON COLUMN slot_series.airport IS '协调机场三字码';
COMMENT ON COLUMN slot_series.slot_time IS '申请时刻 HH:mm，5 分钟粒度';
COMMENT ON COLUMN slot_series.weeks_csv IS '覆盖周列表规范化表示：升序去重后逗号分隔';
COMMENT ON COLUMN slot_series.fingerprint IS '申请指纹：requestId|航班号|时刻|全部覆盖周';
COMMENT ON COLUMN slot_series.decision IS '裁决依据：RETAINED 历史保留 / POOL 公共池';
COMMENT ON COLUMN slot_series.status IS '系列状态：HELD 占用中 / RELEASED 已退回';
COMMENT ON COLUMN slot_series.version IS '乐观锁版本号';
COMMENT ON COLUMN slot_series.created_at IS '创建时间，epoch 毫秒(UTC)';

-- 时刻占用：active 1=占用中 0=已退回；active_key 为存活占用唯一键，退回后为 NULL 不参与唯一约束
CREATE TABLE IF NOT EXISTS slot_allocation (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    season     VARCHAR(16)  NOT NULL,
    airport    VARCHAR(8)   NOT NULL,
    slot_time  VARCHAR(5)   NOT NULL,
    week       INT          NOT NULL,
    airline    VARCHAR(8)   NOT NULL,
    flight_no  VARCHAR(16)  NOT NULL,
    request_id VARCHAR(64)  NOT NULL,
    basis      VARCHAR(16)  NOT NULL,
    active     TINYINT      NOT NULL DEFAULT 1,
    active_key VARCHAR(128) AS (CASE WHEN active = 1
        THEN CONCAT(season, '|', airport, '|', slot_time, '|', week) END)
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_slot_allocation_active ON slot_allocation (active_key);
CREATE INDEX IF NOT EXISTS idx_slot_allocation_season ON slot_allocation (season, airport, slot_time, week, active);
COMMENT ON TABLE slot_allocation IS '时刻占用表：同一机场同一时刻同一周全表至多一条存活占用';
COMMENT ON COLUMN slot_allocation.season IS '航季标识';
COMMENT ON COLUMN slot_allocation.airport IS '机场三字码';
COMMENT ON COLUMN slot_allocation.slot_time IS '时刻 HH:mm，5 分钟粒度';
COMMENT ON COLUMN slot_allocation.week IS '航季内周序号';
COMMENT ON COLUMN slot_allocation.airline IS '占用航司二字码';
COMMENT ON COLUMN slot_allocation.flight_no IS '占用航班号';
COMMENT ON COLUMN slot_allocation.request_id IS '占用所属系列申请幂等键';
COMMENT ON COLUMN slot_allocation.basis IS '占用依据：RETAINED 历史保留 / POOL 公共池';
COMMENT ON COLUMN slot_allocation.active IS '存活标记：1 占用中，0 已退回释放';
COMMENT ON COLUMN slot_allocation.active_key IS '存活占用唯一键（season|airport|slotTime|week），退回后为 NULL';

-- 发布快照：发布时刻拷贝全部存活占用，此后不再随实时占用变化
CREATE TABLE IF NOT EXISTS slot_snapshot (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    season     VARCHAR(16)  NOT NULL,
    airport    VARCHAR(8)   NOT NULL,
    slot_time  VARCHAR(5)   NOT NULL,
    week       INT          NOT NULL,
    airline    VARCHAR(8)   NOT NULL,
    flight_no  VARCHAR(16)  NOT NULL,
    request_id VARCHAR(64)  NOT NULL,
    basis      VARCHAR(16)  NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_slot_snapshot_season ON slot_snapshot (season);
COMMENT ON TABLE slot_snapshot IS '已发布分配表快照条目';
COMMENT ON COLUMN slot_snapshot.season IS '航季标识';
COMMENT ON COLUMN slot_snapshot.airport IS '机场三字码';
COMMENT ON COLUMN slot_snapshot.slot_time IS '时刻 HH:mm';
COMMENT ON COLUMN slot_snapshot.week IS '航季内周序号';
COMMENT ON COLUMN slot_snapshot.airline IS '快照占用航司';
COMMENT ON COLUMN slot_snapshot.flight_no IS '快照占用航班号';
COMMENT ON COLUMN slot_snapshot.request_id IS '快照所属系列申请幂等键';
COMMENT ON COLUMN slot_snapshot.basis IS '快照占用依据：RETAINED / POOL';
