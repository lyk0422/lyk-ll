-- 蜂产品采收追溯服务建表脚本（H2 MODE=MySQL 兼容语法）
-- 时间字段均为 Asia/Shanghai 本地时间（TIMESTAMP），由应用 Clock 写入。

CREATE TABLE IF NOT EXISTS apiary (
    id   BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(32)  NOT NULL COMMENT '蜂场编码，全局唯一',
    name VARCHAR(128) NOT NULL COMMENT '蜂场名称',
    CONSTRAINT uk_apiary_code UNIQUE (code)
) COMMENT '蜂场主数据';

CREATE TABLE IF NOT EXISTS harvest_batch (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no      VARCHAR(64) NOT NULL COMMENT '采收批号，全局唯一',
    floral_period VARCHAR(64) NOT NULL COMMENT '花期标识，如 2026-洋槐',
    status        VARCHAR(16) NOT NULL COMMENT '状态：REGISTERED 已登记 / RELEASED 已放行 / FROZEN 疑似冻结 / REJECTED 复检不合格禁入加工',
    created_at    TIMESTAMP   NOT NULL COMMENT '登记时间（Asia/Shanghai）',
    CONSTRAINT uk_harvest_batch_no UNIQUE (batch_no)
) COMMENT '采收批，按蜂场与花期登记';

CREATE TABLE IF NOT EXISTS harvest_contribution (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id  BIGINT        NOT NULL COMMENT '采收批 ID',
    apiary_id BIGINT        NOT NULL COMMENT '蜂场 ID',
    ratio     DECIMAL(5, 2) NOT NULL COMMENT '贡献比例（百分数，保留两位小数），同批合计须恰等于 100.00',
    CONSTRAINT uk_contribution_batch_apiary UNIQUE (batch_id, apiary_id),
    CONSTRAINT fk_contribution_batch FOREIGN KEY (batch_id) REFERENCES harvest_batch (id),
    CONSTRAINT fk_contribution_apiary FOREIGN KEY (apiary_id) REFERENCES apiary (id)
) COMMENT '采收批蜂场贡献明细';

CREATE TABLE IF NOT EXISTS test_chain (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id   BIGINT      NOT NULL COMMENT '采收批 ID，同批同时刻仅允许一条 OPEN 链',
    status     VARCHAR(8)  NOT NULL COMMENT '链状态：OPEN 进行中 / CLOSED 已闭环',
    opened_at  TIMESTAMP   NOT NULL COMMENT '开启时间（Asia/Shanghai）',
    closed_at  TIMESTAMP   NULL COMMENT '闭环时间，未闭环为 NULL',
    CONSTRAINT uk_test_chain_batch UNIQUE (batch_id),
    CONSTRAINT fk_test_chain_batch FOREIGN KEY (batch_id) REFERENCES harvest_batch (id)
) COMMENT '检测链：一批同时刻只允许一条，快检疑似开启、复检闭环';

CREATE TABLE IF NOT EXISTS rapid_test (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id        BIGINT        NOT NULL COMMENT '采收批 ID',
    chain_id        BIGINT        NOT NULL COMMENT '所属检测链 ID',
    sample_no       VARCHAR(64)   NOT NULL COMMENT '快检样品编号',
    inhibition_rate DECIMAL(5, 2) NOT NULL COMMENT '抑制率（百分数，两位小数），达到 50.00 判疑似',
    result          VARCHAR(8)    NOT NULL COMMENT '结果：PASS 合格 / SUSPECT 疑似',
    request_id      VARCHAR(64)   NOT NULL COMMENT '幂等请求 ID',
    created_at      TIMESTAMP     NOT NULL COMMENT '检测时间（Asia/Shanghai）',
    CONSTRAINT fk_rapid_batch FOREIGN KEY (batch_id) REFERENCES harvest_batch (id),
    CONSTRAINT fk_rapid_chain FOREIGN KEY (chain_id) REFERENCES test_chain (id)
) COMMENT '药残快检记录，被复检推翻后仍保留可查';

CREATE TABLE IF NOT EXISTS retest (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id   BIGINT      NOT NULL COMMENT '采收批 ID',
    chain_id   BIGINT      NOT NULL COMMENT '所属检测链 ID',
    sample_no  VARCHAR(64) NOT NULL COMMENT '复检样品编号，须与快检样品不同',
    result     VARCHAR(12) NOT NULL COMMENT '结果：QUALIFIED 合格 / UNQUALIFIED 不合格',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求 ID',
    created_at TIMESTAMP   NOT NULL COMMENT '复检时间（Asia/Shanghai）',
    CONSTRAINT fk_retest_batch FOREIGN KEY (batch_id) REFERENCES harvest_batch (id),
    CONSTRAINT fk_retest_chain FOREIGN KEY (chain_id) REFERENCES test_chain (id)
) COMMENT '定量复检记录';

CREATE TABLE IF NOT EXISTS apiary_alert (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    apiary_id     BIGINT      NOT NULL COMMENT '蜂场 ID',
    floral_period VARCHAR(64) NOT NULL COMMENT '花期标识',
    suspect_count INT         NOT NULL COMMENT '触发时累计疑似批次数（恰为 3 时触发）',
    triggered_at  TIMESTAMP   NOT NULL COMMENT '预警触发时间（Asia/Shanghai）',
    CONSTRAINT uk_alert_apiary_period UNIQUE (apiary_id, floral_period),
    CONSTRAINT fk_alert_apiary FOREIGN KEY (apiary_id) REFERENCES apiary (id)
) COMMENT '蜂场预警：同蜂场同花期累计恰 3 批疑似触发一次';

CREATE TABLE IF NOT EXISTS processing_batch (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    process_no       VARCHAR(64) NOT NULL COMMENT '加工批号，全局唯一',
    harvest_batch_id BIGINT      NOT NULL COMMENT '引用的已放行采收批 ID',
    request_id       VARCHAR(64) NOT NULL COMMENT '幂等请求 ID',
    created_at       TIMESTAMP   NOT NULL COMMENT '创建时间（Asia/Shanghai）',
    CONSTRAINT uk_processing_no UNIQUE (process_no),
    CONSTRAINT fk_processing_harvest FOREIGN KEY (harvest_batch_id) REFERENCES harvest_batch (id)
) COMMENT '加工批，仅可引用已放行采收批';

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id    VARCHAR(64) PRIMARY KEY COMMENT '幂等请求 ID',
    fingerprint   VARCHAR(128) NOT NULL COMMENT '请求指纹：requestId 与全部业务字段的哈希',
    http_status   INT          NOT NULL COMMENT '首次响应的 HTTP 状态码',
    response_body CLOB         NOT NULL COMMENT '首次响应报文快照，同键同参原样返回',
    created_at    TIMESTAMP    NOT NULL COMMENT '记录时间（Asia/Shanghai）'
) COMMENT '写操作幂等记录';
