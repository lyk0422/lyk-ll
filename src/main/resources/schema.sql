-- 蜂产品采收追溯服务建表脚本（H2 MySQL 兼容模式）
-- 时间字段均为 Asia/Shanghai 本地时间；比例与抑制率字段单位为百分比，保留两位小数。
-- 内存库随 JVM 生命周期保留，重复初始化时先按外键依赖逆序重建。

DROP TABLE IF EXISTS processing_recall_mark;
DROP TABLE IF EXISTS source_recall_link;
DROP TABLE IF EXISTS active_source_recall;
DROP TABLE IF EXISTS source_recall;
DROP TABLE IF EXISTS processing_batch_ref;
DROP TABLE IF EXISTS processing_batch;
DROP TABLE IF EXISTS idempotency_record;
DROP TABLE IF EXISTS apiary_warning;
DROP TABLE IF EXISTS residue_test;
DROP TABLE IF EXISTS open_chain;
DROP TABLE IF EXISTS test_chain;
DROP TABLE IF EXISTS harvest_batch_contribution;
DROP TABLE IF EXISTS harvest_batch;
DROP TABLE IF EXISTS apiary;

CREATE TABLE IF NOT EXISTS apiary (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(32) NOT NULL COMMENT '蜂场编码，全局唯一',
    name VARCHAR(128) NOT NULL COMMENT '蜂场名称',
    CONSTRAINT uk_apiary_code UNIQUE (code)
) COMMENT '蜂场主数据';

CREATE TABLE IF NOT EXISTS harvest_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no VARCHAR(64) NOT NULL COMMENT '采收批号，全局唯一',
    flower_period VARCHAR(64) NOT NULL COMMENT '花期标识，如 2026-洋槐',
    status VARCHAR(16) NOT NULL COMMENT '状态：REGISTERED 已登记待检 / RELEASED 已放行 / FROZEN 疑似冻结',
    created_at TIMESTAMP NOT NULL COMMENT '登记时间，Asia/Shanghai',
    CONSTRAINT uk_harvest_batch_no UNIQUE (batch_no)
) COMMENT '采收批，按蜂场与花期登记';

CREATE TABLE IF NOT EXISTS harvest_batch_contribution (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL COMMENT '采收批 ID',
    apiary_id BIGINT NOT NULL COMMENT '蜂场 ID',
    ratio DECIMAL(5,2) NOT NULL COMMENT '贡献比例，百分比，两位小数；同批合计恰等于 100.00',
    CONSTRAINT uk_contribution_batch_apiary UNIQUE (batch_id, apiary_id),
    CONSTRAINT fk_contribution_batch FOREIGN KEY (batch_id) REFERENCES harvest_batch (id),
    CONSTRAINT fk_contribution_apiary FOREIGN KEY (apiary_id) REFERENCES apiary (id)
) COMMENT '采收批蜂场贡献明细';

CREATE TABLE IF NOT EXISTS test_chain (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL COMMENT '采收批 ID',
    status VARCHAR(8) NOT NULL COMMENT 'OPEN 检测链进行中 / CLOSED 已闭环',
    created_at TIMESTAMP NOT NULL COMMENT '检测链开启时间，Asia/Shanghai',
    closed_at TIMESTAMP NULL COMMENT '闭环时间；未闭环为 NULL',
    CONSTRAINT fk_chain_batch FOREIGN KEY (batch_id) REFERENCES harvest_batch (id)
) COMMENT '药残检测链：一次快检加可选一次复检';

CREATE TABLE IF NOT EXISTS open_chain (
    batch_id BIGINT PRIMARY KEY COMMENT '采收批 ID；主键保证同批同时刻仅一条未闭环检测链',
    chain_id BIGINT NOT NULL COMMENT '未闭环检测链 ID',
    CONSTRAINT fk_open_chain_chain FOREIGN KEY (chain_id) REFERENCES test_chain (id)
) COMMENT '未闭环检测链占用表，闭环时删除';

CREATE TABLE IF NOT EXISTS residue_test (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    chain_id BIGINT NOT NULL COMMENT '所属检测链 ID',
    batch_id BIGINT NOT NULL COMMENT '采收批 ID',
    test_type VARCHAR(8) NOT NULL COMMENT 'RAPID 药残快检 / RETEST 定量复检',
    sample_no VARCHAR(64) NOT NULL COMMENT '样品编号；同链内唯一，复检须换样',
    inhibition_rate DECIMAL(5,2) NOT NULL COMMENT '抑制率，百分比，两位小数；快检达到 50.00 判疑似',
    conclusion VARCHAR(16) NOT NULL COMMENT '结论：PASS 合格 / SUSPECT 疑似 / OVERTURNED 复检合格推翻快检 / CONFIRMED 复检不合格',
    created_at TIMESTAMP NOT NULL COMMENT '送检时间，Asia/Shanghai',
    CONSTRAINT uk_test_chain_sample UNIQUE (chain_id, sample_no),
    CONSTRAINT fk_test_chain FOREIGN KEY (chain_id) REFERENCES test_chain (id),
    CONSTRAINT fk_test_batch FOREIGN KEY (batch_id) REFERENCES harvest_batch (id)
) COMMENT '药残检测记录，快检被推翻后原记录保留可查';

CREATE TABLE IF NOT EXISTS apiary_warning (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    apiary_id BIGINT NOT NULL COMMENT '蜂场 ID',
    flower_period VARCHAR(64) NOT NULL COMMENT '花期标识',
    suspected_count INT NOT NULL COMMENT '触发时疑似批数，恰为 3',
    created_at TIMESTAMP NOT NULL COMMENT '预警触发时间，Asia/Shanghai',
    CONSTRAINT uk_warning_apiary_period UNIQUE (apiary_id, flower_period),
    CONSTRAINT fk_warning_apiary FOREIGN KEY (apiary_id) REFERENCES apiary (id)
) COMMENT '蜂场预警：同蜂场同花期累计恰 3 批疑似触发一次';

CREATE TABLE IF NOT EXISTS processing_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no VARCHAR(64) NOT NULL COMMENT '加工批号，全局唯一',
    created_at TIMESTAMP NOT NULL COMMENT '创建时间，Asia/Shanghai',
    CONSTRAINT uk_processing_batch_no UNIQUE (batch_no)
) COMMENT '加工批次';

CREATE TABLE IF NOT EXISTS processing_batch_ref (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    processing_batch_id BIGINT NOT NULL COMMENT '加工批次 ID',
    harvest_batch_id BIGINT NOT NULL COMMENT '被引用的已放行采收批 ID',
    CONSTRAINT uk_ref_processing_harvest UNIQUE (processing_batch_id, harvest_batch_id),
    CONSTRAINT fk_ref_processing FOREIGN KEY (processing_batch_id) REFERENCES processing_batch (id),
    CONSTRAINT fk_ref_harvest FOREIGN KEY (harvest_batch_id) REFERENCES harvest_batch (id)
) COMMENT '加工批次对采收批的引用，引用与放行判定同事务校验';

CREATE TABLE IF NOT EXISTS source_recall (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    apiary_id BIGINT NOT NULL COMMENT '蜂场 ID',
    flower_period VARCHAR(64) NOT NULL COMMENT '花期标识，精确匹配',
    reason VARCHAR(512) NOT NULL COMMENT '召回原因',
    credential VARCHAR(128) NOT NULL COMMENT '创建合成凭据，仅用于演示',
    inspector VARCHAR(64) NOT NULL COMMENT '创建质检员',
    status VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE 活动中 / WITHDRAWN 已撤回',
    version INT NOT NULL COMMENT '乐观锁版本，新增为 1，每次成功修改加 1',
    created_at TIMESTAMP NOT NULL COMMENT '创建时间，UTC',
    withdrawn_at TIMESTAMP NULL COMMENT '撤回时间，UTC；未撤回为 NULL',
    CONSTRAINT fk_recall_apiary FOREIGN KEY (apiary_id) REFERENCES apiary (id)
) COMMENT '来源召回单：按蜂场加精确花期召回全部相关采收批与加工批';

CREATE TABLE IF NOT EXISTS active_source_recall (
    apiary_id BIGINT NOT NULL COMMENT '蜂场 ID',
    flower_period VARCHAR(64) NOT NULL COMMENT '花期标识',
    recall_id BIGINT NOT NULL COMMENT '活动召回单 ID',
    CONSTRAINT pk_active_recall PRIMARY KEY (apiary_id, flower_period),
    CONSTRAINT fk_active_recall FOREIGN KEY (recall_id) REFERENCES source_recall (id)
) COMMENT '活动召回占用表：主键保证同蜂场同花期最多一条 ACTIVE 召回，撤回时删除';

CREATE TABLE IF NOT EXISTS source_recall_link (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    recall_id BIGINT NOT NULL COMMENT '召回单 ID',
    harvest_batch_id BIGINT NOT NULL COMMENT '被隔离的采收批 ID',
    initial_snapshot BOOLEAN NOT NULL COMMENT 'TRUE 首次冻结清单 / FALSE 后来追加关联',
    status VARCHAR(16) NOT NULL COMMENT 'ACTIVE 隔离中 / RELEASED 已随召回撤回失效',
    created_at TIMESTAMP NOT NULL COMMENT '关联建立时间，UTC',
    CONSTRAINT uk_recall_link UNIQUE (recall_id, harvest_batch_id),
    CONSTRAINT fk_link_recall FOREIGN KEY (recall_id) REFERENCES source_recall (id),
    CONSTRAINT fk_link_batch FOREIGN KEY (harvest_batch_id) REFERENCES harvest_batch (id)
) COMMENT '召回隔离关联：创建时快照与后来登记批次追加，撤回仅使本召回关联失效';

CREATE TABLE IF NOT EXISTS processing_recall_mark (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    recall_id BIGINT NOT NULL COMMENT '召回单 ID',
    processing_batch_id BIGINT NOT NULL COMMENT '被标记的加工批次 ID',
    status VARCHAR(16) NOT NULL COMMENT 'ACTIVE 隔离标记中 / RELEASED 已随召回撤回失效',
    created_at TIMESTAMP NOT NULL COMMENT '标记时间，UTC',
    CONSTRAINT uk_recall_mark UNIQUE (recall_id, processing_batch_id),
    CONSTRAINT fk_mark_recall FOREIGN KEY (recall_id) REFERENCES source_recall (id),
    CONSTRAINT fk_mark_processing FOREIGN KEY (processing_batch_id) REFERENCES processing_batch (id)
) COMMENT '加工批召回隔离标记：仅加标记，不删除原加工证据';

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(64) PRIMARY KEY COMMENT '幂等键',
    fingerprint VARCHAR(128) NOT NULL COMMENT 'requestId 与全部业务字段的 SHA-256 指纹',
    operation VARCHAR(64) NOT NULL COMMENT '操作类型，如 CREATE_HARVEST_BATCH',
    response_status INT NOT NULL COMMENT '首次成功的 HTTP 状态码',
    response_body CLOB NOT NULL COMMENT '首次成功的响应体，重放原样返回',
    created_at TIMESTAMP NOT NULL COMMENT '首次受理时间，Asia/Shanghai'
) COMMENT '写操作幂等记录：同键同参返回首次结果，同键异参 409';
