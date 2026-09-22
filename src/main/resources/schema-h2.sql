-- 感知轨迹状态机：H2（MODE=MySQL）建表脚本，仅用于本地/测试内存库。
-- 所有时间列以 UTC 存储（TIMESTAMP(6)，微秒精度），写入前由应用层把 Instant 转为 UTC LocalDateTime。

-- 轨迹业务ID序列：确定性ID（TRK-00000001 ...）保证同一检测集合重复处理的结果可逐字节比较。
CREATE SEQUENCE IF NOT EXISTS seq_track_no START WITH 1;

CREATE TABLE IF NOT EXISTS track (
    track_id         VARCHAR(64)  NOT NULL,
    category         VARCHAR(64)  NOT NULL,
    state            VARCHAR(32)  NOT NULL,
    confidence       DOUBLE       NOT NULL,
    pos_x            DOUBLE       NOT NULL,
    pos_y            DOUBLE       NOT NULL,
    last_observed_at TIMESTAMP(6) NOT NULL,
    evidence         CLOB,
    version          BIGINT       NOT NULL,
    created_at       TIMESTAMP(6) NOT NULL,
    updated_at       TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (track_id)
);

CREATE TABLE IF NOT EXISTS track_event (
    id           BIGINT       AUTO_INCREMENT,
    event_id     VARCHAR(64)  NOT NULL,
    track_id     VARCHAR(64)  NOT NULL,
    event_type   VARCHAR(32)  NOT NULL,
    from_state   VARCHAR(32),
    to_state     VARCHAR(32),
    version      BIGINT       NOT NULL,
    detection_id VARCHAR(128),
    payload      CLOB         NOT NULL,
    event_time   TIMESTAMP(6) NOT NULL,
    created_at   TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_track_event_event_id UNIQUE (event_id)
);

CREATE INDEX IF NOT EXISTS idx_track_event_track_time ON track_event (track_id, event_time, id);
CREATE INDEX IF NOT EXISTS idx_track_event_time ON track_event (event_time, id);

CREATE TABLE IF NOT EXISTS processed_detection (
    detection_id VARCHAR(128) NOT NULL,
    track_id     VARCHAR(64),
    accepted     BOOLEAN      NOT NULL,
    result       CLOB         NOT NULL,
    event_time   TIMESTAMP(6) NOT NULL,
    processed_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (detection_id)
);

COMMENT ON COLUMN track.track_id IS '轨迹业务ID，应用层生成';
COMMENT ON COLUMN track.category IS '目标类别（如 pedestrian/vehicle），仅同类目标参与关联';
COMMENT ON COLUMN track.state IS '轨迹状态机当前状态：CREATED/TRACKING/OCCLUDED/LOST/REIDENTIFIED/RECYCLED';
COMMENT ON COLUMN track.confidence IS '最近一次有效关联检测的置信度，无量纲，范围[0,1]';
COMMENT ON COLUMN track.pos_x IS '最近观测位置X，单位米，局部平面坐标系';
COMMENT ON COLUMN track.pos_y IS '最近观测位置Y，单位米，局部平面坐标系';
COMMENT ON COLUMN track.last_observed_at IS '最近一次有效观测时间(UTC)；乱序检测不得使其回退';
COMMENT ON COLUMN track.evidence IS '最近一次关联证据JSON：候选轨迹、距离、门限、拒绝原因、ID切换说明';
COMMENT ON COLUMN track.version IS '乐观锁版本号，每次有效变化严格加1；关联请求按版本原子更新';
COMMENT ON COLUMN track.created_at IS '轨迹首次创建时间(UTC)';
COMMENT ON COLUMN track.updated_at IS '记录最近更新时间(UTC)，由应用时钟写入';

COMMENT ON COLUMN track_event.event_id IS '事件唯一ID（幂等用）';
COMMENT ON COLUMN track_event.track_id IS '事件所属轨迹业务ID';
COMMENT ON COLUMN track_event.event_type IS '事件类型：TRACK_CREATED/ASSOCIATED/STATE_CHANGED/REIDENTIFIED/RECYCLED';
COMMENT ON COLUMN track_event.from_state IS '迁移前状态，首条事件为空';
COMMENT ON COLUMN track_event.to_state IS '迁移后状态';
COMMENT ON COLUMN track_event.version IS '事件应用后的轨迹版本号';
COMMENT ON COLUMN track_event.detection_id IS '触发事件的检测ID，生命周期事件为空';
COMMENT ON COLUMN track_event.payload IS '事件完整载荷JSON（位置、置信度、关联证据等），快照重放的事实来源';
COMMENT ON COLUMN track_event.event_time IS '事件业务时间(UTC)，取检测时戳，重放按此时刻裁剪';
COMMENT ON COLUMN track_event.created_at IS '事件落库时间(UTC)';

COMMENT ON COLUMN processed_detection.detection_id IS '检测唯一ID，重复提交按幂等处理';
COMMENT ON COLUMN processed_detection.track_id IS '该检测关联到的轨迹ID；被拒绝时为空';
COMMENT ON COLUMN processed_detection.accepted IS '是否产生有效变化（true=已关联/建轨，false=低置信度或乱序拒绝）';
COMMENT ON COLUMN processed_detection.result IS '首次处理结果JSON，重复检测原样返回';
COMMENT ON COLUMN processed_detection.event_time IS '检测携带的时戳(UTC)';
COMMENT ON COLUMN processed_detection.processed_at IS '首次处理落库时间(UTC)';
