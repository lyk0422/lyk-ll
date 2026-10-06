-- 引航计划服务建表脚本（H2 MODE=MySQL 兼容语法，亦可在 MySQL 8 执行）
-- 时间字段均为本地时间（Asia/Shanghai），语义为计划/潮汐窗口的起止时刻。

CREATE TABLE IF NOT EXISTS pilot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(64) NOT NULL COMMENT '引航员姓名',
    license_no VARCHAR(64) NOT NULL COMMENT '引航员证书编号',
    UNIQUE KEY uk_pilot_license (license_no)
) COMMENT '引航员';

CREATE TABLE IF NOT EXISTS tug (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(64) NOT NULL COMMENT '拖轮名称',
    bollard_pull INT NOT NULL COMMENT '系柱拖力，单位吨',
    UNIQUE KEY uk_tug_name (name)
) COMMENT '拖轮';

CREATE TABLE IF NOT EXISTS pilot_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求号，全局唯一',
    applicant VARCHAR(64) NOT NULL COMMENT '申请方（船代/船公司）',
    ship_name VARCHAR(64) NOT NULL COMMENT '船舶名称',
    pilot_id BIGINT NULL COMMENT '编排的引航员，未编排为 NULL',
    tide_start DATETIME NULL COMMENT '潮水窗口起点（含），半开区间下界',
    tide_end DATETIME NULL COMMENT '潮水窗口终点（不含），半开区间上界',
    plan_start DATETIME NOT NULL COMMENT '计划引航开始时刻',
    plan_end DATETIME NOT NULL COMMENT '计划引航结束时刻',
    status VARCHAR(16) NOT NULL COMMENT '状态：APPLIED/SCHEDULED/CONFIRMED/EXECUTING/SETTLED/CANCELLED',
    created_at DATETIME NOT NULL COMMENT '创建时间',
    updated_at DATETIME NOT NULL COMMENT '最近更新时间',
    UNIQUE KEY uk_plan_request (request_id)
) COMMENT '引航计划';

CREATE TABLE IF NOT EXISTS plan_tug (
    plan_id BIGINT NOT NULL COMMENT '引航计划ID',
    tug_id BIGINT NOT NULL COMMENT '拖轮ID',
    PRIMARY KEY (plan_id, tug_id)
) COMMENT '计划绑定的拖轮组合';

CREATE TABLE IF NOT EXISTS resource_lock (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id BIGINT NOT NULL COMMENT '引航计划ID',
    resource_type VARCHAR(8) NOT NULL COMMENT '资源类型：PILOT 引航员 / TUG 拖轮',
    resource_id BIGINT NOT NULL COMMENT '资源ID（引航员或拖轮主键）',
    lock_start DATETIME NOT NULL COMMENT '锁占用起点（含），半开区间下界',
    lock_end DATETIME NOT NULL COMMENT '锁占用终点（不含），半开区间上界',
    KEY idx_lock_resource (resource_type, resource_id, lock_start, lock_end)
) COMMENT '资源锁定：确认后与同事务写入，占用区间为半开区间';

CREATE TABLE IF NOT EXISTS violation_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id BIGINT NOT NULL COMMENT '引航计划ID',
    reason VARCHAR(128) NOT NULL COMMENT '违约原因',
    lead_minutes BIGINT NOT NULL COMMENT '取消申请距计划执行的分钟数，负数表示已过时',
    created_at DATETIME NOT NULL COMMENT '违约记录生成时间'
) COMMENT '合成违约记录（如不足 2 小时的迟到取消）';

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(64) PRIMARY KEY COMMENT '幂等请求号',
    operation VARCHAR(32) NOT NULL COMMENT '写操作类型',
    fingerprint VARCHAR(128) NOT NULL COMMENT '请求指纹：requestId 与全部业务字段的散列',
    response_body TEXT NOT NULL COMMENT '首次成功响应报文，同键同参直接返回',
    created_at DATETIME NOT NULL COMMENT '记录创建时间'
) COMMENT '写操作幂等记录';
