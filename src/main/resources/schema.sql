-- 管道内检测（ILI）缺陷对齐与分级复核服务建表脚本
-- 目标库：H2 内存库（MODE=MySQL），列级 COMMENT 与 MySQL 语法兼容；
-- 表级注释使用 H2 支持的 COMMENT ON 语句。所有时间戳为应用写入的 UTC 即时。

CREATE TABLE IF NOT EXISTS pipeline_segment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    segment_code VARCHAR(64) NOT NULL COMMENT '管段编码，全局唯一',
    segment_name VARCHAR(128) NOT NULL COMMENT '管段名称',
    created_at TIMESTAMP NOT NULL COMMENT '创建时间（UTC）',
    CONSTRAINT uk_segment_code UNIQUE (segment_code)
);
COMMENT ON TABLE pipeline_segment IS '管道管段主数据，焊缝与缺陷均按管段内绝对里程（米）记录';

CREATE TABLE IF NOT EXISTS pipeline_weld (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    segment_id BIGINT NOT NULL COMMENT '所属管段ID',
    weld_no VARCHAR(64) NOT NULL COMMENT '焊缝编号，管段内唯一',
    abs_mileage_m DECIMAL(12,3) NOT NULL COMMENT '绝对里程，单位米',
    CONSTRAINT uk_weld_segment_no UNIQUE (segment_id, weld_no),
    CONSTRAINT fk_weld_segment FOREIGN KEY (segment_id) REFERENCES pipeline_segment (id)
);
COMMENT ON TABLE pipeline_weld IS '管段焊缝台帐，按绝对里程（米）定位';

CREATE TABLE IF NOT EXISTS inspection_run (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    segment_id BIGINT NOT NULL COMMENT '所属管段ID',
    run_code VARCHAR(64) NOT NULL COMMENT '检测回合标识，管段内唯一',
    run_date DATE NOT NULL COMMENT '检测日期（本地日期，用于计算年化增长率）',
    created_at TIMESTAMP NOT NULL COMMENT '登记时间（UTC）',
    CONSTRAINT uk_run_segment_code UNIQUE (segment_id, run_code),
    CONSTRAINT fk_run_segment FOREIGN KEY (segment_id) REFERENCES pipeline_segment (id)
);
COMMENT ON TABLE inspection_run IS '检测回合（一次内检测作业），同一管段同一回合标识仅允许一个检测日期';

CREATE TABLE IF NOT EXISTS alignment_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求标识，全局唯一',
    fingerprint CHAR(64) NOT NULL COMMENT '请求规范化摘要（SHA-256 十六进制），覆盖 requestId、管段、两回合标识与全部缺陷读数',
    segment_id BIGINT NOT NULL COMMENT '管段ID',
    run1_id BIGINT NOT NULL COMMENT '旧检测回合ID',
    run2_id BIGINT NOT NULL COMMENT '新检测回合ID',
    status VARCHAR(16) NOT NULL COMMENT '批次状态：ALIGNED=已对齐待复核，SEALED=已封板（终态）',
    pair_count INT NOT NULL COMMENT '匹配对数量',
    new_count INT NOT NULL COMMENT '新发现缺陷数量',
    disappeared_count INT NOT NULL COMMENT '已消失缺陷数量',
    created_at TIMESTAMP NOT NULL COMMENT '创建时间（UTC）',
    sealed_at TIMESTAMP NULL COMMENT '封板时间（UTC），未封板为 NULL',
    CONSTRAINT uk_batch_request UNIQUE (request_id),
    CONSTRAINT fk_batch_segment FOREIGN KEY (segment_id) REFERENCES pipeline_segment (id),
    CONSTRAINT fk_batch_run1 FOREIGN KEY (run1_id) REFERENCES inspection_run (id),
    CONSTRAINT fk_batch_run2 FOREIGN KEY (run2_id) REFERENCES inspection_run (id)
);
COMMENT ON TABLE alignment_batch IS '缺陷对齐批次：一次双回合导入与对齐结果，封板后为终态不可修改';

CREATE TABLE IF NOT EXISTS defect_reading (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    batch_id BIGINT NOT NULL COMMENT '所属对齐批次ID',
    run_id BIGINT NOT NULL COMMENT '所属检测回合ID',
    defect_ref VARCHAR(64) NOT NULL COMMENT '缺陷编号，批次+回合内唯一',
    abs_mileage_m DECIMAL(12,3) NOT NULL COMMENT '绝对里程，单位米',
    depth_pct DECIMAL(6,3) NOT NULL COMMENT '缺陷深度，占壁厚百分比（0-100）',
    match_status VARCHAR(16) NOT NULL COMMENT '对齐结果：MATCHED=已匹配，NEW_FINDING=新发现，DISAPPEARED=已消失',
    CONSTRAINT uk_reading_batch_run_ref UNIQUE (batch_id, run_id, defect_ref),
    CONSTRAINT fk_reading_batch FOREIGN KEY (batch_id) REFERENCES alignment_batch (id),
    CONSTRAINT fk_reading_run FOREIGN KEY (run_id) REFERENCES inspection_run (id)
);
COMMENT ON TABLE defect_reading IS '缺陷读数：某批次某回合的一条缺陷记录，深度为壁厚百分比';

CREATE TABLE IF NOT EXISTS match_pair (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    batch_id BIGINT NOT NULL COMMENT '所属对齐批次ID',
    old_reading_id BIGINT NOT NULL COMMENT '旧回合缺陷读数ID，唯一约束保证一个缺陷只出现在一个匹配对',
    new_reading_id BIGINT NOT NULL COMMENT '新回合缺陷读数ID，唯一约束保证一个缺陷只出现在一个匹配对',
    mileage_diff_m DECIMAL(6,3) NOT NULL COMMENT '里程差绝对值，单位米，匹配容差 ±0.5m（含边界）',
    growth_rate_pct_year DECIMAL(10,4) NOT NULL COMMENT '深度年化增长率，百分点/年，按两回合检测日期差折算',
    grade VARCHAR(16) NOT NULL COMMENT '初始分级：STABLE=稳定(<5%/年)，GROWING=增长(>=5%/年)，SEVERE=严重(>=15%/年)',
    review_status VARCHAR(16) NOT NULL COMMENT '复核状态：NOT_REQUIRED=稳定无需复核，PENDING=待复核，CONFIRMED=已确认，DOWNGRADED=已降级',
    final_grade VARCHAR(16) NULL COMMENT '复核后最终分级，未复核为 NULL；降级时比初始分级低一级',
    reviewer VARCHAR(64) NULL COMMENT '复核人，未复核为 NULL',
    reviewed_at TIMESTAMP NULL COMMENT '复核时间（UTC），未复核为 NULL',
    CONSTRAINT uk_pair_old UNIQUE (old_reading_id),
    CONSTRAINT uk_pair_new UNIQUE (new_reading_id),
    CONSTRAINT fk_pair_batch FOREIGN KEY (batch_id) REFERENCES alignment_batch (id),
    CONSTRAINT fk_pair_old FOREIGN KEY (old_reading_id) REFERENCES defect_reading (id),
    CONSTRAINT fk_pair_new FOREIGN KEY (new_reading_id) REFERENCES defect_reading (id)
);
COMMENT ON TABLE match_pair IS '缺陷匹配对及分级复核结论，同一缺陷不允许出现在两个匹配对中';
