-- ILI（管道内检测）缺陷对齐与分级复核服务的建表脚本。
-- 使用 H2 内存库 MySQL 兼容模式执行；所有数据均为合成数据，仅存在于 JVM 生命周期内。

CREATE TABLE IF NOT EXISTS ili_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '批次主键',
    request_id VARCHAR(64) NOT NULL COMMENT '客户端幂等请求标识，全局唯一',
    fingerprint VARCHAR(64) NOT NULL COMMENT '请求规范化摘要（SHA-256 十六进制），覆盖 requestId、管段、两回合标识与全部缺陷读数',
    segment_id VARCHAR(64) NOT NULL COMMENT '管段标识',
    run_old_id VARCHAR(64) NOT NULL COMMENT '上一检测回合标识',
    run_new_id VARCHAR(64) NOT NULL COMMENT '本检测回合标识',
    run_old_date DATE NOT NULL COMMENT '上一回合检测日期（本地日期，不含时区）',
    run_new_date DATE NOT NULL COMMENT '本回合检测日期（本地日期，不含时区），必须晚于上一回合',
    status VARCHAR(16) NOT NULL DEFAULT 'ALIGNED' COMMENT '批次状态：ALIGNED=已对齐待复核，SEALED=已封板（终态）',
    created_at TIMESTAMP NOT NULL COMMENT '批次创建时间（UTC 时间戳）',
    sealed_at TIMESTAMP NULL COMMENT '封板时间（utc 时间戳）；NULL 表示未封板',
    CONSTRAINT uk_ili_batch_request UNIQUE (request_id)
) COMMENT='ILI 对齐批次：一次双回合缺陷导入与对齐的幂等批次';

CREATE TABLE IF NOT EXISTS ili_defect_reading (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '读数主键',
    batch_id BIGINT NOT NULL COMMENT '所属批次主键',
    run_side VARCHAR(8) NOT NULL COMMENT '读数所属回合：OLD=上一回合，NEW=本回合',
    defect_ref VARCHAR(64) NOT NULL COMMENT '检测方缺陷编号，同批次同回合内唯一',
    abs_position_m DECIMAL(12,3) NOT NULL COMMENT '缺陷绝对里程，单位米',
    depth_pct DECIMAL(6,3) NOT NULL COMMENT '缺陷深度，占壁厚百分比，取值 0~100',
    created_at TIMESTAMP NOT NULL COMMENT '读数写入时间（UTC 时间戳）',
    CONSTRAINT uk_reading_batch_side_ref UNIQUE (batch_id, run_side, defect_ref),
    CONSTRAINT fk_reading_batch FOREIGN KEY (batch_id) REFERENCES ili_batch (id)
) COMMENT='缺陷读数：某一回合在管段绝对里程处的一次缺陷深度记录';

CREATE TABLE IF NOT EXISTS ili_match_pair (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '匹配对主键',
    batch_id BIGINT NOT NULL COMMENT '所属批次主键',
    old_reading_id BIGINT NOT NULL COMMENT '上一回合读数主键，全表唯一（同一缺陷不允许出现在两个匹配对中）',
    new_reading_id BIGINT NOT NULL COMMENT '本回合读数主键，全表唯一（同一缺陷不允许出现在两个匹配对中）',
    distance_m DECIMAL(6,3) NOT NULL COMMENT '两读数里程差绝对值，单位米，匹配容差为不超过 0.5 米（含）',
    growth_rate_pct_per_year DECIMAL(10,4) NOT NULL COMMENT '深度增长率，单位 %/年；负值表示深度减小',
    grade VARCHAR(16) NOT NULL COMMENT '分级：STABLE=稳定（<5%/年），GROWING=增长（>=5%/年），SEVERE=严重（>=15%/年）',
    review_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '复核状态：PENDING=待复核，CONFIRMED=已确认，DOWNGRADED=已降级',
    final_grade VARCHAR(16) NULL COMMENT '复核后最终分级；NULL 表示尚未复核，确认时等于原分级，降级时下调一级',
    reviewer VARCHAR(64) NULL COMMENT '复核人标识；NULL 表示尚未复核',
    review_comment VARCHAR(512) NULL COMMENT '复核备注；NULL 表示无备注',
    reviewed_at TIMESTAMP NULL COMMENT '复核时间（UTC 时间戳）；NULL 表示尚未复核',
    CONSTRAINT uk_pair_old UNIQUE (old_reading_id),
    CONSTRAINT uk_pair_new UNIQUE (new_reading_id),
    CONSTRAINT fk_pair_batch FOREIGN KEY (batch_id) REFERENCES ili_batch (id),
    CONSTRAINT fk_pair_old FOREIGN KEY (old_reading_id) REFERENCES ili_defect_reading (id),
    CONSTRAINT fk_pair_new FOREIGN KEY (new_reading_id) REFERENCES ili_defect_reading (id)
) COMMENT='缺陷匹配对：按里程容差对齐的旧新读数及其分级与复核结论';
