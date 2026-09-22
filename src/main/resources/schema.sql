-- 感知轨迹状态机：本地嵌入式 H2（MODE=MySQL）自动建表脚本。
-- 时间字段统一使用 BIGINT 存储 epoch 毫秒（UTC 毫秒数，时区无关）。
-- 仅在嵌入式数据源下由 Spring SQL 初始化自动执行（spring.sql.init.mode=embedded）。

-- 轨迹当前态表：乐观锁 version 保证关联请求按版本原子更新
CREATE TABLE IF NOT EXISTS track (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    track_uid        VARCHAR(64)  NOT NULL,
    target_category  VARCHAR(32)  NOT NULL,
    status           VARCHAR(20)  NOT NULL,
    confidence       DOUBLE       NOT NULL,
    last_detection_id VARCHAR(128),
    last_x           DOUBLE,
    last_y           DOUBLE,
    last_z           DOUBLE,
    last_observed_at BIGINT       NOT NULL,
    version          BIGINT       NOT NULL,
    created_at       BIGINT       NOT NULL,
    updated_at      BIGINT       NOT NULL,
    CONSTRAINT uk_track_uid UNIQUE (track_uid)
);

-- 已处理检测去重表：detectionId 全局唯一，重复提交幂等返回首次结果
CREATE TABLE IF NOT EXISTS processed_detection (
    detection_id VARCHAR(128) NOT NULL,
    track_uid    VARCHAR(64),
    source       VARCHAR(64)  NOT NULL,
    observed_at  BIGINT       NOT NULL,
    category     VARCHAR(32)  NOT NULL,
    confidence   DOUBLE       NOT NULL,
    result       VARCHAR(32)  NOT NULL,
    reject_reason VARCHAR(64),
    created_at   BIGINT       NOT NULL,
    CONSTRAINT pk_processed_detection PRIMARY KEY (detection_id)
);

-- 轨迹事件流：所有有效变化追加写入，快照重建只依赖本表
CREATE TABLE IF NOT EXISTS track_event (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_time       BIGINT       NOT NULL,
    track_uid        VARCHAR(64)  NOT NULL,
    target_category  VARCHAR(32)  NOT NULL,
    event_type       VARCHAR(32)  NOT NULL,
    from_status      VARCHAR(20),
    to_status        VARCHAR(20),
    version          BIGINT       NOT NULL,
    detection_id     VARCHAR(128),
    source           VARCHAR(64),
    confidence       DOUBLE,
    position_x       DOUBLE,
    position_y       DOUBLE,
    position_z       DOUBLE,
    evidence_type    VARCHAR(32),
    linked_track_uid VARCHAR(64),
    distance         DOUBLE,
    gate_threshold   DOUBLE,
    detail           VARCHAR(512),
    payload          CLOB,
    recorded_at      BIGINT       NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_track_event_time ON track_event (event_time, id);
CREATE INDEX IF NOT EXISTS idx_track_event_uid ON track_event (track_uid, id);

-- 关联证据表：目标交叉、ID 切换、重识别等可解释证据（与事件流 payload 一致）
CREATE TABLE IF NOT EXISTS track_evidence (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    track_uid        VARCHAR(64)  NOT NULL,
    evidence_type    VARCHAR(32)  NOT NULL,
    linked_track_uid VARCHAR(64),
    detection_id     VARCHAR(128),
    event_time       BIGINT       NOT NULL,
    version          BIGINT       NOT NULL,
    distance         DOUBLE,
    gate_threshold   DOUBLE,
    detail           VARCHAR(512),
    created_at       BIGINT       NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_track_evidence_uid ON track_evidence (track_uid, id);

-- 确定性轨迹号分配表（T-000001 起按创建顺序分配，保证同序输入重放一致）
CREATE TABLE IF NOT EXISTS track_seq (
    id       INT PRIMARY KEY,
    next_val BIGINT NOT NULL
);
INSERT INTO track_seq (id, next_val)
SELECT 1, 1
WHERE NOT EXISTS (SELECT 1 FROM track_seq WHERE id = 1);
COMMENT ON TABLE track IS '轨迹当前态：status 为 CREATED/TRACKING/OCCLUDED/LOST/REIDENTIFIED/RECYCLED；version 为乐观锁版本号，每次有效变化加一';
COMMENT ON COLUMN track.confidence IS '最近一次关联检测置信度，[0,1]，无观测时取创建检测置信度';
COMMENT ON COLUMN track.last_observed_at IS '最近观测事件时间，epoch 毫秒（UTC），乱序检测不得使其回退';
COMMENT ON COLUMN track.last_x IS '最近观测位置 X，单位米，可空表示尚无观测位置';
COMMENT ON COLUMN track.version IS '轨迹版本号，初始为 1，每次有效状态/观测变化原子加一';

COMMENT ON COLUMN processed_detection.result IS '处理结果：ASSOCIATED/CREATED/REJECTED';
COMMENT ON COLUMN processed_detection.reject_reason IS '拒绝原因：LOW_CONFIDENCE/OUT_OF_ORDER/OUT_OF_GATE/ILLEGAL_STATE 等';

COMMENT ON TABLE track_event IS '轨迹事件流，仅追加；按 (event_time,id) 排序重放可重建任意时刻快照';
COMMENT ON COLUMN track_event.event_time IS '业务事件时间（检测时戳），epoch 毫秒（UTC）';
COMMENT ON COLUMN track_event.recorded_at IS '系统落库时间，epoch 毫秒（UTC）';
COMMENT ON COLUMN track_event.payload IS '事件附加 JSON（证据详情、快照重放所需的位置等）';

COMMENT ON TABLE track_evidence IS '关联证据：目标交叉 CROSSING、ID 切换 ID_SWITCH、重识别 REIDENTIFICATION 等，含关联轨迹与距离依据';
