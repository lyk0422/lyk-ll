-- 印染配方放大与缸差控制服务：H2(MODE=MySQL) 建表脚本
-- 数据仅在 JVM 生命周期内保留；克重统一 DECIMAL，时间统一 Asia/Shanghai。

CREATE TABLE IF NOT EXISTS dye (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    stock_g DECIMAL(14, 3) NOT NULL,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON COLUMN dye.code IS '染化料编码(唯一)';
COMMENT ON COLUMN dye.name IS '染化料名称(合成数据)';
COMMENT ON COLUMN dye.stock_g IS '库存克重,单位g,空值无意义';

CREATE TABLE IF NOT EXISTS recipe (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    recipe_code VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    liquor_ratio DECIMAL(8, 2) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_recipe_code_ver UNIQUE (recipe_code, version)
);
COMMENT ON COLUMN recipe.recipe_code IS '配方编码';
COMMENT ON COLUMN recipe.version IS '版本号,从1递增;发布后冻结,修改生成新版本';
COMMENT ON COLUMN recipe.liquor_ratio IS '浴比,单位L/kg,并缸须同配方同浴比';
COMMENT ON COLUMN recipe.status IS '状态:DRAFT 草稿/PUBLISHED 已发布冻结';

CREATE TABLE IF NOT EXISTS recipe_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    recipe_id BIGINT NOT NULL,
    dye_code VARCHAR(64) NOT NULL,
    dosage_gpl DECIMAL(12, 4) NOT NULL,
    CONSTRAINT uk_recipe_item UNIQUE (recipe_id, dye_code)
);
COMMENT ON COLUMN recipe_item.recipe_id IS '所属配方版本主键';
COMMENT ON COLUMN recipe_item.dye_code IS '染化料编码';
COMMENT ON COLUMN recipe_item.dosage_gpl IS '配方浓度,单位g/L,放大克重=浓度*浴比*布重';

CREATE TABLE IF NOT EXISTS color_check (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL UNIQUE,
    sample_id VARCHAR(64) NOT NULL,
    delta_e DECIMAL(8, 4) NOT NULL,
    passed BOOLEAN NOT NULL,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON COLUMN color_check.request_id IS '对色幂等键';
COMMENT ON COLUMN color_check.sample_id IS '小样编号';
COMMENT ON COLUMN color_check.delta_e IS '对色色差ΔE,<=0.8放行(恰0.8合格)';
COMMENT ON COLUMN color_check.passed IS '是否放行';

CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id VARCHAR(64) PRIMARY KEY,
    operation VARCHAR(32) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    response_status INT NOT NULL,
    response_body CLOB,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON COLUMN idempotent_request.request_id IS '业务幂等键,指纹覆盖requestId与全部业务字段';
COMMENT ON COLUMN idempotent_request.fingerprint IS '业务参数SHA-256指纹';
COMMENT ON COLUMN idempotent_request.response_body IS '首次响应JSON,同键同参原样重放';

CREATE TABLE IF NOT EXISTS vat_active (
    vat_no VARCHAR(64) PRIMARY KEY,
    lot_id BIGINT NOT NULL,
    since TIMESTAMP NOT NULL
);
COMMENT ON COLUMN vat_active.vat_no IS '染缸编号;行存在即该缸有执行中缸次,并发排缸唯一受理';
COMMENT ON COLUMN vat_active.lot_id IS '占用染缸的缸次主键';

CREATE TABLE IF NOT EXISTS lot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    lot_no VARCHAR(64) NOT NULL UNIQUE,
    vat_no VARCHAR(64) NOT NULL,
    recipe_code VARCHAR(64) NOT NULL,
    recipe_version INT NOT NULL,
    liquor_ratio DECIMAL(8, 2) NOT NULL,
    fabric_weight_kg DECIMAL(12, 3) NOT NULL,
    merged_into_lot_id BIGINT,
    status VARCHAR(24) NOT NULL,
    repair_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
COMMENT ON COLUMN lot.lot_no IS '缸次号(唯一)';
COMMENT ON COLUMN lot.vat_no IS '染缸编号';
COMMENT ON COLUMN lot.recipe_code IS '生产所用配方编码';
COMMENT ON COLUMN lot.recipe_version IS '生产所用配方版本,冻结后不可变';
COMMENT ON COLUMN lot.liquor_ratio IS '浴比L/kg,并缸须同配方同浴比';
COMMENT ON COLUMN lot.fabric_weight_kg IS '本缸织物重量,单位kg';
COMMENT ON COLUMN lot.merged_into_lot_id IS '并缸目标缸次;非空表示该缸已并入他缸,空为独立缸';
COMMENT ON COLUMN lot.status IS '状态:CREATED待投料/FED已投料/REPAIRING回修中/PASSED合格/DOWNGRADED降级/MERGED已并缸/ARCHIVED已归档终态';
COMMENT ON COLUMN lot.repair_count IS '已回修次数,只允许回修一次';

CREATE TABLE IF NOT EXISTS feeding (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL UNIQUE,
    lot_id BIGINT NOT NULL,
    approval_id BIGINT,
    fed_at TIMESTAMP NOT NULL
);
COMMENT ON COLUMN feeding.request_id IS '投料幂等键';
COMMENT ON COLUMN feeding.lot_id IS '所属缸次';
COMMENT ON COLUMN feeding.approval_id IS '受控投料引用的补偿批准主键;空为旧非受控投料';

CREATE TABLE IF NOT EXISTS weighing (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    feeding_id BIGINT NOT NULL,
    dye_code VARCHAR(64) NOT NULL,
    batch_no VARCHAR(64),
    target_g DECIMAL(14, 4) NOT NULL,
    actual_g DECIMAL(14, 4) NOT NULL,
    deviation_pct DECIMAL(9, 6)
);
COMMENT ON COLUMN weighing.feeding_id IS '所属投料单';
COMMENT ON COLUMN weighing.dye_code IS '染化料编码';
COMMENT ON COLUMN weighing.batch_no IS '受控投料称量快照的染料批次号;空为旧非受控称量';
COMMENT ON COLUMN weighing.target_g IS '目标克重,单位g;受控投料为补偿目标(原目标/系数,三位小数)';
COMMENT ON COLUMN weighing.actual_g IS '实际称量克重,单位g';
COMMENT ON COLUMN weighing.deviation_pct IS '称量偏差百分比=(实际-目标)/目标*100,绝对值>0.5拒绝投料(恰0.5合法)';

CREATE TABLE IF NOT EXISTS dye_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no VARCHAR(64) NOT NULL UNIQUE,
    dye_code VARCHAR(64) NOT NULL,
    factor DECIMAL(14, 6) NOT NULL,
    available_g DECIMAL(14, 3) NOT NULL,
    valid_until TIMESTAMP NOT NULL,
    version INT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
COMMENT ON COLUMN dye_batch.batch_no IS '染料批次号(唯一)';
COMMENT ON COLUMN dye_batch.dye_code IS '所属染化料编码,错配整单拒绝';
COMMENT ON COLUMN dye_batch.factor IS '效价系数,正数;补偿目标=原目标/系数,仅按线性模型使用';
COMMENT ON COLUMN dye_batch.available_g IS '批次可用克重,单位g,受控投料原子扣减';
COMMENT ON COLUMN dye_batch.valid_until IS '有效截止(Asia/Shanghai);now<截止有效,恰在截止失效';
COMMENT ON COLUMN dye_batch.version IS '系数版本,从1开始,成功修订加1;修订使未投料批准失效';

CREATE TABLE IF NOT EXISTS compensation_approval (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL UNIQUE,
    lot_no VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
COMMENT ON COLUMN compensation_approval.request_id IS '补偿审批幂等键';
COMMENT ON COLUMN compensation_approval.lot_no IS '受控缸次号';
COMMENT ON COLUMN compensation_approval.status IS '状态:VALID 未投料有效/INVALID 系数修订失效/FED 已投料冻结';

CREATE TABLE IF NOT EXISTS compensation_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    approval_id BIGINT NOT NULL,
    dye_code VARCHAR(64) NOT NULL,
    batch_no VARCHAR(64) NOT NULL,
    factor_version INT NOT NULL,
    factor DECIMAL(14, 6) NOT NULL,
    base_target_g DECIMAL(14, 4) NOT NULL,
    compensated_target_g DECIMAL(14, 3) NOT NULL
);
COMMENT ON COLUMN compensation_item.approval_id IS '所属补偿批准';
COMMENT ON COLUMN compensation_item.dye_code IS '染化料编码';
COMMENT ON COLUMN compensation_item.batch_no IS '批准冻结的染料批次号';
COMMENT ON COLUMN compensation_item.factor_version IS '批准冻结的批次系数版本,投料须与批次当前版本一致';
COMMENT ON COLUMN compensation_item.factor IS '批准冻结的效价系数快照,已投料历史不追改';
COMMENT ON COLUMN compensation_item.base_target_g IS '放大配方原目标克重,单位g';
COMMENT ON COLUMN compensation_item.compensated_target_g IS '补偿目标克重=原目标/系数,四舍五入三位小数,单位g';

CREATE TABLE IF NOT EXISTS lot_judgment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL UNIQUE,
    lot_id BIGINT NOT NULL,
    round INT NOT NULL,
    delta_e DECIMAL(8, 4) NOT NULL,
    reference_lot_id BIGINT,
    reference_delta_e DECIMAL(8, 4),
    diff DECIMAL(9, 4),
    verdict VARCHAR(16) NOT NULL,
    judged_at TIMESTAMP NOT NULL
);
COMMENT ON COLUMN lot_judgment.request_id IS '判定幂等键';
COMMENT ON COLUMN lot_judgment.lot_id IS '被判定缸次';
COMMENT ON COLUMN lot_judgment.round IS '判定轮次:0首次出缸,1回修后复测';
COMMENT ON COLUMN lot_judgment.delta_e IS '本缸出缸ΔE';
COMMENT ON COLUMN lot_judgment.reference_lot_id IS '同配方对比缸次(缸差台账)';
COMMENT ON COLUMN lot_judgment.reference_delta_e IS '对比缸最近一次判定ΔE';
COMMENT ON COLUMN lot_judgment.diff IS '缸差=两缸ΔE之差的绝对值,>0.5须回修';
COMMENT ON COLUMN lot_judgment.verdict IS '判定:PASS合格/REPAIR回修/DOWNGRADE降级';

CREATE TABLE IF NOT EXISTS late_color_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    lot_id BIGINT NOT NULL,
    delta_e DECIMAL(8, 4) NOT NULL,
    note VARCHAR(255),
    received_at TIMESTAMP NOT NULL
);
COMMENT ON COLUMN late_color_event.lot_id IS '归档缸次;归档后迟到测色只登记事件不改判';
COMMENT ON COLUMN late_color_event.delta_e IS '迟到测色ΔE';
COMMENT ON COLUMN late_color_event.note IS '迟到说明';

-- 合成基础染化料库存（克）
INSERT INTO dye (code, name, stock_g, created_at) VALUES
    ('D-RED',    '活性红 RED-3BS',    5000.000, CURRENT_TIMESTAMP),
    ('D-YELLOW', '活性黄 YELLOW-4G',  5000.000, CURRENT_TIMESTAMP),
    ('D-BLUE',   '活性蓝 BLUE-RR',    5000.000, CURRENT_TIMESTAMP);

-- 合成已发布配方 R-BASE v1（浴比 10L/kg），随服务启动自动可用
INSERT INTO recipe (recipe_code, version, liquor_ratio, status, created_at)
VALUES ('R-BASE', 1, 10.00, 'PUBLISHED', CURRENT_TIMESTAMP);
INSERT INTO recipe_item (recipe_id, dye_code, dosage_gpl)
SELECT id, 'D-RED',    2.0000 FROM recipe WHERE recipe_code = 'R-BASE' AND version = 1;
INSERT INTO recipe_item (recipe_id, dye_code, dosage_gpl)
SELECT id, 'D-YELLOW', 1.5000 FROM recipe WHERE recipe_code = 'R-BASE' AND version = 1;
INSERT INTO recipe_item (recipe_id, dye_code, dosage_gpl)
SELECT id, 'D-BLUE',   0.5000 FROM recipe WHERE recipe_code = 'R-BASE' AND version = 1;

-- 合成染料批次（效价系数 + 可用克重 + 有效截止），随服务启动自动可用
INSERT INTO dye_batch (batch_no, dye_code, factor, available_g, valid_until, version, created_at, updated_at)
VALUES
    ('B-RED-1',    'D-RED',    1.250000, 3000.000, '2099-01-01 00:00:00', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('B-YELLOW-1', 'D-YELLOW', 1.000000, 3000.000, '2099-01-01 00:00:00', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('B-BLUE-1',   'D-BLUE',   0.800000, 3000.000, '2099-01-01 00:00:00', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
