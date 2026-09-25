-- 饲料合规服务 H2（MODE=MySQL）建表脚本，随应用启动自动执行。
-- 所有时间字段以 UTC 存储；休药期按 UTC 日界计算。

-- 原料/药物添加剂合成目录
CREATE TABLE IF NOT EXISTS ingredient (
    code               VARCHAR(64)  PRIMARY KEY COMMENT '原料/药物添加剂编码',
    name               VARCHAR(128) NOT NULL COMMENT '名称',
    medicated          TINYINT      NOT NULL COMMENT '是否药物添加剂：1 是 0 否',
    max_ratio          DECIMAL(10,6) COMMENT '最大添加比例（0~1，恰等于上限合法；非药物为空表示不限）',
    withdrawal_days    INT          COMMENT '休药天数（整数日，UTC 日界；仅药物添加剂有值）'
);

-- 配伍禁忌组合（无向对，目录初始化时按 code 字典序去重写入）
CREATE TABLE IF NOT EXISTS contraindication (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    ingredient_a  VARCHAR(64) NOT NULL COMMENT '禁忌药物编码 A（字典序较小者）',
    ingredient_b  VARCHAR(64) NOT NULL COMMENT '禁忌药物编码 B（字典序较大者）',
    CONSTRAINT uk_contraindication UNIQUE (ingredient_a, ingredient_b)
);

-- 养殖批次
CREATE TABLE IF NOT EXISTS farm_batch (
    batch_no        VARCHAR(64) PRIMARY KEY COMMENT '养殖批次号',
    species         VARCHAR(64) NOT NULL COMMENT '动物种类（如 POULTRY 家禽、SWINE 猪）',
    status          VARCHAR(16) NOT NULL COMMENT '批次状态：ACTIVE 在栏 / SLAUGHTERED 已出栏（终态）',
    created_at      TIMESTAMP    NOT NULL COMMENT '建档时刻（UTC）',
    slaughtered_at  TIMESTAMP    COMMENT '出栏时刻（UTC）；未出栏为空'
);

-- 配方主表（一个配方编码一行，版本在子表递增）
CREATE TABLE IF NOT EXISTS formula (
    code           VARCHAR(64) PRIMARY KEY COMMENT '配方编码',
    latest_version INT NOT NULL DEFAULT 0 COMMENT '当前最大版本号（0 表示尚无版本）',
    created_at     TIMESTAMP NOT NULL COMMENT '首次提交时刻（UTC）'
);

-- 配方版本（批准后冻结，禁止修改）
CREATE TABLE IF NOT EXISTS formula_version (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    formula_code    VARCHAR(64)  NOT NULL COMMENT '所属配方编码',
    version_no      INT          NOT NULL COMMENT '版本号（同配方内自增）',
    status          VARCHAR(16)  NOT NULL COMMENT 'SUBMITTED 待批准 / REJECTED 合规拒绝 / APPROVED 已批准冻结',
    target_species  VARCHAR(64) COMMENT '目标动物种类；含药配方必填',
    medicated       TINYINT      NOT NULL COMMENT '是否含药物添加剂：1 是 0 否',
    fingerprint     VARCHAR(128) NOT NULL COMMENT '提交指纹（SHA-256，覆盖 requestId 与全部业务字段）',
    reject_reasons  VARCHAR(1024) COMMENT '合规拒绝原因，多个以 || 分隔；通过为空',
    created_at      TIMESTAMP    NOT NULL COMMENT '提交时刻（UTC）',
    approved_at     TIMESTAMP    COMMENT '批准时刻（UTC）；未批准为空',
    CONSTRAINT uk_formula_version UNIQUE (formula_code, version_no)
);

-- 配方版本组成项（版本快照，不可变）
CREATE TABLE IF NOT EXISTS formula_item (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    formula_code    VARCHAR(64)   NOT NULL COMMENT '所属配方编码',
    version_no      INT           NOT NULL COMMENT '所属版本号',
    ingredient_code VARCHAR(64)   NOT NULL COMMENT '原料/药物编码',
    ratio           DECIMAL(10,6) NOT NULL COMMENT '添加比例（0~1）',
    CONSTRAINT uk_formula_item UNIQUE (formula_code, version_no, ingredient_code)
);

-- 批次投产/饲喂跟踪：每次投产一个含药配方版本产生一行义务（按投产时刻的已批准版本固化）
CREATE TABLE IF NOT EXISTS batch_medication (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no         VARCHAR(64) NOT NULL COMMENT '养殖批次号',
    formula_code     VARCHAR(64) NOT NULL COMMENT '投产配方编码',
    version_no       INT         NOT NULL COMMENT '投产时固化的已批准版本号',
    last_feeding_time TIMESTAMP  NOT NULL COMMENT '末次饲喂该含药饲料时刻（UTC）',
    max_withdrawal_days INT      NOT NULL COMMENT '该版本各药物中的最大休药天数（天）',
    created_at       TIMESTAMP   NOT NULL COMMENT '投产登记时刻（UTC）',
    late             TINYINT     NOT NULL DEFAULT 0 COMMENT '是否为出栏后迟到记录：1 是 0 否'
);

-- 幂等请求台账：requestId → 指纹 + 响应快照，保证同键同参返回首次结果、同键异参 409
CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id    VARCHAR(128) PRIMARY KEY COMMENT '幂等键 requestId',
    operation     VARCHAR(32)  NOT NULL COMMENT '操作类型：SUBMIT/APPROVE/PRODUCTION/SLAUGHTER',
    fingerprint   VARCHAR(128) NOT NULL COMMENT '请求指纹（SHA-256，覆盖 requestId 与全部业务字段）',
    response_json TEXT         NOT NULL COMMENT '首次响应快照（JSON）',
    created_at    TIMESTAMP    NOT NULL COMMENT '首次请求时刻（UTC）'
);

-- 合规告警
CREATE TABLE IF NOT EXISTS compliance_alert (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no    VARCHAR(64)  NOT NULL COMMENT '相关养殖批次号',
    alert_type  VARCHAR(32)  NOT NULL COMMENT '告警类型：LATE_FEEDING 出栏后迟到含药饲喂',
    detail      VARCHAR(1024) NOT NULL COMMENT '告警详情',
    created_at  TIMESTAMP    NOT NULL COMMENT '告警产生时刻（UTC）'
);
