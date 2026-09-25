-- 炼油调合服务建表脚本（H2 MySQL 兼容模式）；数据仅存在于 JVM 生命周期内，全部为合成数据。

CREATE TABLE IF NOT EXISTS component_tank (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(32) NOT NULL COMMENT '组分罐编码',
    name VARCHAR(64) NOT NULL COMMENT '组分罐名称',
    remaining_volume DECIMAL(14,3) NOT NULL COMMENT '罐余量，单位立方米(m3)',
    octane DECIMAL(10,3) NOT NULL COMMENT '组分辛烷值',
    sulfur DECIMAL(10,3) NOT NULL COMMENT '组分硫含量，单位mg/kg',
    vapor_pressure DECIMAL(10,3) NOT NULL COMMENT '组分蒸气压，单位kPa',
    CONSTRAINT uk_component_tank_code UNIQUE (code)
) COMMENT='组分罐（合成数据）';

CREATE TABLE IF NOT EXISTS blend_tank (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(32) NOT NULL COMMENT '调合罐编码',
    name VARCHAR(64) NOT NULL COMMENT '调合罐名称',
    CONSTRAINT uk_blend_tank_code UNIQUE (code)
) COMMENT='调合罐（合成数据）';

CREATE TABLE IF NOT EXISTS product_spec (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_code VARCHAR(32) NOT NULL COMMENT '成品牌号',
    octane_limit DECIMAL(10,3) NOT NULL COMMENT '辛烷值规格上限，实际值小于等于上限判合格',
    sulfur_limit DECIMAL(10,3) NOT NULL COMMENT '硫含量规格上限，单位mg/kg',
    vapor_pressure_limit DECIMAL(10,3) NOT NULL COMMENT '蒸气压规格上限，单位kPa',
    octane_margin DECIMAL(10,3) NOT NULL COMMENT '辛烷值安全边际；实际值低于上限减安全边际时给出卡边率提示',
    sulfur_margin DECIMAL(10,3) NOT NULL COMMENT '硫含量安全边际，单位mg/kg',
    vapor_pressure_margin DECIMAL(10,3) NOT NULL COMMENT '蒸气压安全边际，单位kPa',
    CONSTRAINT uk_product_spec_code UNIQUE (product_code)
) COMMENT='成品质量规格与安全边际（合成数据）';

CREATE TABLE IF NOT EXISTS blend_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(40) NOT NULL COMMENT '调合单号',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求键',
    product_code VARCHAR(32) NOT NULL COMMENT '成品牌号',
    blend_tank_id BIGINT NOT NULL COMMENT '调合罐ID',
    status VARCHAR(20) NOT NULL COMMENT '状态：EXECUTING执行中/RELEASED已放行封账/UNQUALIFIED不合格品',
    total_volume DECIMAL(14,3) NOT NULL COMMENT '调合总量，单位立方米(m3)',
    reblend_count INT NOT NULL DEFAULT 0 COMMENT '已返调次数，最多1次',
    created_at TIMESTAMP NOT NULL COMMENT '创建时间，时区Asia/Shanghai',
    released_at TIMESTAMP NULL COMMENT '放行封账时间；NULL表示未放行',
    CONSTRAINT uk_blend_order_no UNIQUE (order_no),
    CONSTRAINT uk_blend_order_request UNIQUE (request_id)
) COMMENT='调合单';

CREATE TABLE IF NOT EXISTS blend_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL COMMENT '所属调合单ID',
    seq INT NOT NULL COMMENT '批次序号，从1开始',
    origin_batch_id BIGINT NULL COMMENT '返调来源批次ID；NULL表示原始批次',
    status VARCHAR(20) NOT NULL COMMENT '状态：OPEN调和化验中/SAMPLE1_PASSED首样合格/SAMPLE1_FAILED首样不合格/PASSED双样合格/FAILED判定不合格',
    created_at TIMESTAMP NOT NULL COMMENT '创建时间，时区Asia/Shanghai',
    CONSTRAINT fk_batch_order FOREIGN KEY (order_id) REFERENCES blend_order (id)
) COMMENT='调合批次，返调生成新批次并引用原批次';

CREATE TABLE IF NOT EXISTS blend_batch_component (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL COMMENT '所属批次ID',
    tank_id BIGINT NOT NULL COMMENT '组分罐ID',
    tank_code VARCHAR(32) NOT NULL COMMENT '组分罐编码快照',
    volume DECIMAL(14,3) NOT NULL COMMENT '切罐量，单位立方米(m3)',
    octane DECIMAL(10,3) NOT NULL COMMENT '切罐时组分辛烷值快照',
    sulfur DECIMAL(10,3) NOT NULL COMMENT '切罐时组分硫含量快照，单位mg/kg',
    vapor_pressure DECIMAL(10,3) NOT NULL COMMENT '切罐时组分蒸气压快照，单位kPa',
    CONSTRAINT fk_bbc_batch FOREIGN KEY (batch_id) REFERENCES blend_batch (id)
) COMMENT='批次组分快照，指标可按此复算';

CREATE TABLE IF NOT EXISTS lab_sample (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL COMMENT '所属批次ID',
    sample_point INT NOT NULL COMMENT '采样点：1首样/2次样',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求键',
    octane DECIMAL(10,3) NOT NULL COMMENT '化验辛烷值（按线性调合模型计算）',
    sulfur DECIMAL(10,3) NOT NULL COMMENT '化验硫含量，单位mg/kg',
    vapor_pressure DECIMAL(10,3) NOT NULL COMMENT '化验蒸气压，单位kPa',
    qualified BOOLEAN NOT NULL COMMENT '本采样点是否合格',
    hints VARCHAR(512) NOT NULL DEFAULT '' COMMENT '卡边率提示，分号分隔；空串表示无提示',
    created_at TIMESTAMP NOT NULL COMMENT '化验时间，时区Asia/Shanghai',
    CONSTRAINT uk_lab_sample_request UNIQUE (request_id),
    CONSTRAINT uk_lab_sample_point UNIQUE (batch_id, sample_point)
) COMMENT='化验采样记录';

CREATE TABLE IF NOT EXISTS late_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL COMMENT '调合单ID',
    batch_id BIGINT NULL COMMENT '迟到化验针对的批次ID；NULL表示无法定位',
    sample_point INT NULL COMMENT '采样点；NULL表示未指定',
    request_id VARCHAR(64) NOT NULL COMMENT '迟到化验请求键',
    detail VARCHAR(512) NOT NULL COMMENT '迟到事件说明',
    created_at TIMESTAMP NOT NULL COMMENT '记录时间，时区Asia/Shanghai'
) COMMENT='封账后迟到化验事件，只记录不改判定';

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(64) PRIMARY KEY COMMENT '幂等请求键',
    endpoint VARCHAR(64) NOT NULL COMMENT '接口标识',
    fingerprint VARCHAR(64) NOT NULL COMMENT '请求指纹，覆盖requestId与全部业务字段',
    response_status INT NOT NULL COMMENT '首次响应HTTP状态码；-1表示处理中',
    response_body CLOB NULL COMMENT '首次响应报文JSON；NULL表示处理中',
    created_at TIMESTAMP NOT NULL COMMENT '创建时间，时区Asia/Shanghai'
) COMMENT='幂等记录，同键同参返回首次结果，同键异参返回409';

CREATE TABLE IF NOT EXISTS blend_tank_lock (
    blend_tank_id BIGINT PRIMARY KEY COMMENT '调合罐ID',
    order_id BIGINT NOT NULL COMMENT '占用中的调合单ID',
    created_at TIMESTAMP NOT NULL COMMENT '占用时间，时区Asia/Shanghai'
) COMMENT='调合罐执行中占用锁，同一调合罐同时刻只允许一张执行中调合单';
