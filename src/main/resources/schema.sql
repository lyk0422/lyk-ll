-- 管道内检测（ILI）缺陷对齐与分级复核服务建表脚本。
-- 兼容 H2（MODE=MySQL）与 MySQL；所有时间均为数据库会话时区，精确到秒。

-- 对齐批次：一次双回合缺陷清单导入、对齐与分级的整体单元，按 request_id 幂等。
CREATE TABLE IF NOT EXISTS alignment_batch (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '批次主键',
    request_id    VARCHAR(64)  NOT NULL COMMENT '客户端幂等请求标识，全局唯一',
    fingerprint   VARCHAR(64)  NOT NULL COMMENT '请求规范化摘要（SHA-256 十六进制），同键异参判冲突',
    segment_id    VARCHAR(64)  NOT NULL COMMENT '管段标识',
    old_run_id    VARCHAR(64)  NOT NULL COMMENT '旧检测回合标识',
    new_run_id    VARCHAR(64)  NOT NULL COMMENT '新检测回合标识',
    old_run_date  DATE         NOT NULL COMMENT '旧检测回合日期',
    new_run_date  DATE         NOT NULL COMMENT '新检测回合日期，须晚于旧回合',
    status        VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT '批次状态：OPEN=可复核，SEALED=已封板（终态）',
    created_at    TIMESTAMP    NOT NULL COMMENT '批次创建时间',
    sealed_at     TIMESTAMP    NULL COMMENT '封板时间；未封板为 NULL',
    CONSTRAINT uk_alignment_batch_request UNIQUE (request_id)
) COMMENT='ILI 缺陷对齐批次';

-- 缺陷读数：某回合在管段绝对里程处的一次缺陷记录。
CREATE TABLE IF NOT EXISTS defect_reading (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '读数主键',
    batch_id      BIGINT       NOT NULL COMMENT '所属对齐批次 id',
    run_side      VARCHAR(8)   NOT NULL COMMENT '回合侧：OLD=旧回合，NEW=新回合',
    defect_ref    VARCHAR(64)  NOT NULL COMMENT '客户端缺陷编号（批次内单侧唯一）',
    mileage_m     DECIMAL(12,3) NOT NULL COMMENT '绝对里程，单位米',
    depth_pct     DECIMAL(6,2) NOT NULL COMMENT '缺陷深度，占壁厚百分比（0-100）',
    match_outcome VARCHAR(16)  NOT NULL DEFAULT 'MATCHED' COMMENT '对齐结果：MATCHED=已配对，NEW_FOUND=新发现，DISAPPEARED=已消失',
    CONSTRAINT fk_reading_batch FOREIGN KEY (batch_id) REFERENCES alignment_batch (id),
    CONSTRAINT uk_reading_ref UNIQUE (batch_id, run_side, defect_ref)
) COMMENT='单回合缺陷读数';

-- 缺陷匹配对：旧新读数一对一配对及分级、复核结论。
CREATE TABLE IF NOT EXISTS defect_match (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '匹配对主键',
    batch_id         BIGINT       NOT NULL COMMENT '所属对齐批次 id',
    old_reading_id   BIGINT       NOT NULL COMMENT '旧回合读数 id，批次内唯一',
    new_reading_id   BIGINT       NOT NULL COMMENT '新回合读数 id，批次内唯一',
    mileage_diff_m   DECIMAL(8,3) NOT NULL COMMENT '配对里程差绝对值，单位米，不超过 0.5',
    growth_rate      DECIMAL(9,3) NOT NULL COMMENT '深度增长率，单位 壁厚百分比/年，可为负',
    grade            VARCHAR(16)  NOT NULL COMMENT '系统分级：STABLE=稳定(<5%/年)，GROWING=增长(>=5%)，SEVERE=严重(>=15%)',
    review_status    VARCHAR(16)  NOT NULL DEFAULT 'NOT_REQUIRED' COMMENT '复核状态：NOT_REQUIRED=无需复核，PENDING=待复核，CONFIRMED=已确认，DOWNGRADED=已降级',
    final_grade      VARCHAR(16)  NULL COMMENT '复核后最终分级；未复核或确认时与 grade 一致，降级后为较低等级',
    reviewed_by      VARCHAR(64)  NULL COMMENT '复核人标识；未复核为 NULL',
    reviewed_at      TIMESTAMP    NULL COMMENT '复核时间；未复核为 NULL',
    CONSTRAINT fk_match_batch FOREIGN KEY (batch_id) REFERENCES alignment_batch (id),
    CONSTRAINT fk_match_old FOREIGN KEY (old_reading_id) REFERENCES defect_reading (id),
    CONSTRAINT fk_match_new FOREIGN KEY (new_reading_id) REFERENCES defect_reading (id),
    CONSTRAINT uk_match_old UNIQUE (batch_id, old_reading_id),
    CONSTRAINT uk_match_new UNIQUE (batch_id, new_reading_id)
) COMMENT='缺陷匹配对与分级复核结论';
