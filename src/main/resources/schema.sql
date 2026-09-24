-- 管道内检测（ILI）缺陷对齐服务建表脚本，兼容 H2（MODE=MySQL）与 MySQL 语法。
-- 时间字段均为数据库会话时区（本地运行默认 Asia/Shanghai）的墙钟时间；NULL 含义见各列注释。

CREATE TABLE IF NOT EXISTS alignment_batch (
    id               BIGINT       AUTO_INCREMENT PRIMARY KEY,
    request_id       VARCHAR(64)  NOT NULL,
    segment_id       VARCHAR(64)  NOT NULL,
    old_run_id       VARCHAR(64)  NOT NULL,
    new_run_id       VARCHAR(64)  NOT NULL,
    old_inspected_at DATE         NOT NULL,
    new_inspected_at DATE         NOT NULL,
    fingerprint      CHAR(64)     NOT NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sealed_at       TIMESTAMP    NULL,
    CONSTRAINT uk_alignment_batch_request UNIQUE (request_id),
    CONSTRAINT ck_alignment_batch_status CHECK (status IN ('OPEN', 'SEALED'))
);

COMMENT ON TABLE alignment_batch IS 'ILI 缺陷对齐批次：一次双回合导入及其对齐结果，按 request_id 幂等';
COMMENT ON COLUMN alignment_batch.id IS '批次主键，自增';
COMMENT ON COLUMN alignment_batch.request_id IS '客户端幂等键，全局唯一；同键同参重放返回首次结果，同键异参返回 409';
COMMENT ON COLUMN alignment_batch.segment_id IS '管段标识，缺陷按该管段绝对里程（米）记录';
COMMENT ON COLUMN alignment_batch.old_run_id IS '旧检测回合标识';
COMMENT ON COLUMN alignment_batch.new_run_id IS '新检测回合标识';
COMMENT ON COLUMN alignment_batch.old_inspected_at IS '旧回合检测日期（日历日，无时分秒）';
COMMENT ON COLUMN alignment_batch.new_inspected_at IS '新回合检测日期（日历日，无时分秒），必须晚于旧回合';
COMMENT ON COLUMN alignment_batch.fingerprint IS '规范化请求摘要（SHA-256 十六进制），覆盖 requestId、管段、两回合标识与全部缺陷读数';
COMMENT ON COLUMN alignment_batch.status IS '批次状态：OPEN=可复核，SEALED=已封板（终态）';
COMMENT ON COLUMN alignment_batch.version IS '乐观锁版本号，封板等状态迁移时递增';
COMMENT ON COLUMN alignment_batch.created_at IS '批次创建时间（数据库会话时区）';
COMMENT ON COLUMN alignment_batch.sealed_at IS '封板时间（数据库会话时区）；NULL 表示未封板';

CREATE TABLE IF NOT EXISTS defect_reading (
    id          BIGINT        AUTO_INCREMENT PRIMARY KEY,
    batch_id    BIGINT        NOT NULL,
    run_side    VARCHAR(8)    NOT NULL,
    reading_key VARCHAR(64)   NOT NULL,
    station_m   DECIMAL(12,3) NOT NULL,
    depth_pct   DECIMAL(6,3)  NOT NULL,
    matched     BOOLEAN       NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_reading_batch FOREIGN KEY (batch_id) REFERENCES alignment_batch (id),
    CONSTRAINT uk_reading_batch_key UNIQUE (batch_id, run_side, reading_key),
    CONSTRAINT ck_reading_side CHECK (run_side IN ('OLD', 'NEW'))
);

COMMENT ON TABLE defect_reading IS '单回合缺陷读数：某批次内一次检测回合上报的一条缺陷记录（合成数据）';
COMMENT ON COLUMN defect_reading.id IS '读数主键，自增';
COMMENT ON COLUMN defect_reading.batch_id IS '所属对齐批次';
COMMENT ON COLUMN defect_reading.run_side IS '读数来源回合：OLD=旧回合，NEW=新回合';
COMMENT ON COLUMN defect_reading.reading_key IS '客户端提供的读数键，批次+回合内唯一';
COMMENT ON COLUMN defect_reading.station_m IS '缺陷绝对里程，单位米，精度 0.001 m';
COMMENT ON COLUMN defect_reading.depth_pct IS '缺陷深度，占壁厚百分比，单位 %';
COMMENT ON COLUMN defect_reading.matched IS '是否已进入匹配对；FALSE 时按回合侧归为“新发现”（NEW）或“已消失”（OLD）';

CREATE TABLE IF NOT EXISTS match_pair (
    id                  BIGINT       AUTO_INCREMENT PRIMARY KEY,
    batch_id            BIGINT       NOT NULL,
    old_reading_id      BIGINT       NOT NULL,
    new_reading_id      BIGINT       NOT NULL,
    distance_m          DECIMAL(6,3) NOT NULL,
    growth_rate_pct     DECIMAL(8,4) NOT NULL,
    grade               VARCHAR(16)  NOT NULL,
    review_status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    review_grade        VARCHAR(16)  NULL,
    reviewer            VARCHAR(64)  NULL,
    reviewed_at         TIMESTAMP    NULL,
    CONSTRAINT fk_match_batch FOREIGN KEY (batch_id) REFERENCES alignment_batch (id),
    CONSTRAINT fk_match_old FOREIGN KEY (old_reading_id) REFERENCES defect_reading (id),
    CONSTRAINT fk_match_new FOREIGN KEY (new_reading_id) REFERENCES defect_reading (id),
    CONSTRAINT uk_match_old UNIQUE (old_reading_id),
    CONSTRAINT uk_match_new UNIQUE (new_reading_id),
    CONSTRAINT ck_match_grade CHECK (grade IN ('STABLE', 'GROWING', 'SEVERE')),
    CONSTRAINT ck_match_review_status CHECK (review_status IN ('PENDING', 'CONFIRMED', 'DOWNGRADED')),
    CONSTRAINT ck_match_review_grade CHECK (review_grade IS NULL OR review_grade IN ('STABLE', 'GROWING', 'SEVERE'))
);

COMMENT ON TABLE match_pair IS '缺陷匹配对：里程容差 ±0.5 m 内的一对旧新读数及其深度增长分级';
COMMENT ON COLUMN match_pair.id IS '匹配对主键，自增';
COMMENT ON COLUMN match_pair.batch_id IS '所属对齐批次';
COMMENT ON COLUMN match_pair.old_reading_id IS '旧回合读数，全表唯一（同一读数不允许出现在两个匹配对中）';
COMMENT ON COLUMN match_pair.new_reading_id IS '新回合读数，全表唯一';
COMMENT ON COLUMN match_pair.distance_m IS '新旧读数里程差绝对值，单位米，最大 0.5 m（含边界）';
COMMENT ON COLUMN match_pair.growth_rate_pct IS '深度年增长率，单位 %/年，按两回合检测日期间隔（365.25 天/年）折算，可为负';
COMMENT ON COLUMN match_pair.grade IS '系统分级：STABLE=增长率<5%/年，GROWING=[5%,15%)/年，SEVERE=≥15%/年';
COMMENT ON COLUMN match_pair.review_status IS '复核状态：PENDING=待复核，CONFIRMED=已确认，DOWNGRADED=已降级；仅 GROWING/SEVERE 需要复核';
COMMENT ON COLUMN match_pair.review_grade IS '复核降级后的分级；NULL 表示未降级或尚未复核';
COMMENT ON COLUMN match_pair.reviewer IS '复核人标识；NULL 表示未复核';
COMMENT ON COLUMN match_pair.reviewed_at IS '复核时间（数据库会话时区）；NULL 表示未复核';
