-- 炼油调合服务建表脚本（H2 MySQL 兼容模式），数据仅保留在 JVM 生命周期内，全部为合成数据。
DROP TABLE IF EXISTS lab_sample;
DROP TABLE IF EXISTS tank_movement;
DROP TABLE IF EXISTS blend_order_component;
DROP TABLE IF EXISTS blend_batch;
DROP TABLE IF EXISTS blend_tank_lock;
DROP TABLE IF EXISTS blend_order;
DROP TABLE IF EXISTS product_spec;
DROP TABLE IF EXISTS component_tank;

-- 组分罐：存放调合用组分油，库存单位为 kg
CREATE TABLE component_tank (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(32) NOT NULL COMMENT '组分罐号，全局唯一',
    name VARCHAR(64) NOT NULL COMMENT '组分油名称（合成数据）',
    octane DECIMAL(10,3) NOT NULL COMMENT '辛烷值 RON，合成化验值',
    sulfur DECIMAL(10,4) NOT NULL COMMENT '硫含量，单位 mg/kg',
    rvp DECIMAL(10,3) NOT NULL COMMENT '雷德蒸气压，单位 kPa',
    stock_kg DECIMAL(14,3) NOT NULL COMMENT '当前罐余量，单位 kg，切罐时原子扣减',
    CONSTRAINT uk_component_tank_code UNIQUE (code)
);

-- 成品规格：各指标均为上限规格，恰等于上限判合格；安全边际用于卡边控制提示
CREATE TABLE product_spec (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_code VARCHAR(32) NOT NULL COMMENT '成品牌号，全局唯一',
    octane_max DECIMAL(10,3) NOT NULL COMMENT '辛烷值上限 RON',
    sulfur_max DECIMAL(10,4) NOT NULL COMMENT '硫含量上限，单位 mg/kg',
    rvp_max DECIMAL(10,3) NOT NULL COMMENT '蒸气压上限，单位 kPa',
    octane_margin DECIMAL(10,3) NOT NULL DEFAULT 0 COMMENT '辛烷值卡边安全边际，0 表示不做卡边控制',
    sulfur_margin DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '硫含量卡边安全边际，单位 mg/kg',
    rvp_margin DECIMAL(10,3) NOT NULL DEFAULT 0 COMMENT '蒸气压卡边安全边际，单位 kPa',
    CONSTRAINT uk_product_spec_code UNIQUE (product_code)
);

-- 调合单：一张单对应一个调合罐的一次调合作业；request_id 幂等
CREATE TABLE blend_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '客户端幂等键',
    fingerprint VARCHAR(64) NOT NULL COMMENT 'requestId 与全部业务字段的 SHA-256 指纹',
    blend_tank_code VARCHAR(32) NOT NULL COMMENT '调合罐号',
    product_code VARCHAR(32) NOT NULL COMMENT '目标成品牌号',
    target_qty_kg DECIMAL(14,3) NOT NULL COMMENT '目标调合量，单位 kg，等于各组分切罐量之和',
    status VARCHAR(16) NOT NULL COMMENT 'EXECUTING 执行中 / RELEASED 已封账 / OFF_SPEC 不合格品，均为终态语义',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，Asia/Shanghai',
    released_at TIMESTAMP NULL COMMENT '封账时间，未封账为 NULL',
    CONSTRAINT uk_blend_order_request UNIQUE (request_id)
);

-- 调合罐执行锁：同一调合罐同时刻只允许一张执行中调合单
CREATE TABLE blend_tank_lock (
    blend_tank_code VARCHAR(32) PRIMARY KEY COMMENT '调合罐号',
    order_id BIGINT NOT NULL COMMENT '占用该罐的调合单 ID',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 调合批次：首单为第 1 批，返调生成新批次并引用原批次
CREATE TABLE blend_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL COMMENT '所属调合单 ID',
    batch_no INT NOT NULL COMMENT '批次序号，从 1 开始',
    parent_batch_id BIGINT NULL COMMENT '返调时引用原批次 ID，首批为 NULL',
    request_id VARCHAR(64) NOT NULL COMMENT '产生本批次的请求幂等键（下达或返调）',
    fingerprint VARCHAR(64) NOT NULL COMMENT '该请求的业务字段指纹',
    status VARCHAR(16) NOT NULL COMMENT 'ACTIVE 采样中 / FAILED 化验不合格 / SUPERSEDED 已被返调取代 / PASSED 双采样合格 / RELEASED 已封账',
    total_qty_kg DECIMAL(14,3) NOT NULL COMMENT '本批次累计调合量，单位 kg',
    octane DECIMAL(10,3) NOT NULL COMMENT '合成调合模型计算的辛烷值快照',
    sulfur DECIMAL(10,4) NOT NULL COMMENT '合成调合模型计算的硫含量快照，单位 mg/kg',
    rvp DECIMAL(10,3) NOT NULL COMMENT '合成调合模型计算的蒸气压快照，单位 kPa',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_blend_batch_request UNIQUE (request_id),
    CONSTRAINT uk_blend_batch_no UNIQUE (order_id, batch_no)
);

-- 调合单组分明细：记录每个批次各组分罐的切罐量
CREATE TABLE blend_order_component (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL COMMENT '所属调合单 ID',
    batch_id BIGINT NOT NULL COMMENT '所属批次 ID',
    tank_code VARCHAR(32) NOT NULL COMMENT '组分罐号',
    qty_kg DECIMAL(14,3) NOT NULL COMMENT '本批次该组分切罐量，单位 kg'
);

-- 切罐流水：与库存扣减同事务写入，不得出现已切罐未扣量
CREATE TABLE tank_movement (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL COMMENT '所属调合单 ID',
    batch_id BIGINT NOT NULL COMMENT '所属批次 ID',
    tank_code VARCHAR(32) NOT NULL COMMENT '组分罐号',
    qty_kg DECIMAL(14,3) NOT NULL COMMENT '扣减量，单位 kg，正数',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 化验样本：双采样点判定；封账后到达的数据只记迟到事件不改结论
CREATE TABLE lab_sample (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '客户端幂等键',
    fingerprint VARCHAR(64) NOT NULL COMMENT 'requestId 与全部业务字段的 SHA-256 指纹',
    order_id BIGINT NOT NULL COMMENT '所属调合单 ID',
    batch_id BIGINT NOT NULL COMMENT '采样时执行批次 ID',
    sample_point INT NOT NULL COMMENT '采样点编号：1 首样 / 2 复样',
    octane DECIMAL(10,3) NOT NULL COMMENT '辛烷值化验结果（合成模型）',
    sulfur DECIMAL(10,4) NOT NULL COMMENT '硫含量化验结果，单位 mg/kg',
    rvp DECIMAL(10,3) NOT NULL COMMENT '蒸气压化验结果，单位 kPa',
    passed BOOLEAN NOT NULL COMMENT '本样本是否合格：全部指标不超过规格上限',
    late BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否封账后迟到的数据，TRUE 时仅记录不改判定',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_lab_sample_request UNIQUE (request_id),
    CONSTRAINT uk_lab_sample_point UNIQUE (batch_id, sample_point, late)
);
