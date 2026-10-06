-- 引航计划服务数据库结构（H2 MODE=MySQL 兼容语法）
-- 时间字段均为本地时间（Asia/Shanghai），空值含义见各字段注释。

-- 引航员
CREATE TABLE IF NOT EXISTS pilot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    code VARCHAR(32) NOT NULL COMMENT '引航员工号，唯一',
    name VARCHAR(64) NOT NULL COMMENT '引航员姓名',
    UNIQUE KEY uk_pilot_code (code)
) COMMENT='引航员';

-- 拖轮
CREATE TABLE IF NOT EXISTS tugboat (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    code VARCHAR(32) NOT NULL COMMENT '拖轮编号，唯一',
    name VARCHAR(64) NOT NULL COMMENT '拖轮名称',
    UNIQUE KEY uk_tugboat_code (code)
) COMMENT='拖轮';

-- 引航计划，状态机：APPLIED(申请)->ARRANGED(编排)->CONFIRMED(确认)->EXECUTING(执行)->SETTLED(核销)，可流转 CANCELLED(已取消)
CREATE TABLE IF NOT EXISTS pilot_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    plan_no VARCHAR(64) NOT NULL COMMENT '计划编号，业务唯一',
    ship_name VARCHAR(128) NOT NULL COMMENT '船舶名称',
    voyage_no VARCHAR(64) NOT NULL COMMENT '航次号',
    status VARCHAR(16) NOT NULL COMMENT '计划状态：APPLIED/ARRANGED/CONFIRMED/EXECUTING/SETTLED/CANCELLED',
    pilot_id BIGINT NULL COMMENT '引航员ID，编排前为空',
    tide_start TIMESTAMP NULL COMMENT '潮水窗口开始（含），半开区间下界，编排前为空',
    tide_end TIMESTAMP NULL COMMENT '潮水窗口结束（不含），半开区间上界，编排前为空',
    applied_at TIMESTAMP NOT NULL COMMENT '申请时间',
    confirmed_at TIMESTAMP NULL COMMENT '确认时间，未确认为空',
    started_at TIMESTAMP NULL COMMENT '开始执行时间，未执行为空',
    settled_at TIMESTAMP NULL COMMENT '核销时间，未核销为空',
    cancelled_at TIMESTAMP NULL COMMENT '取消时间，未取消为空',
    cancel_reason VARCHAR(255) NULL COMMENT '取消原因，未取消为空',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    UNIQUE KEY uk_plan_no (plan_no)
) COMMENT='引航计划';

-- 计划-拖轮组合关联
CREATE TABLE IF NOT EXISTS pilot_plan_tug (
    plan_id BIGINT NOT NULL COMMENT '计划ID',
    tugboat_id BIGINT NOT NULL COMMENT '拖轮ID',
    PRIMARY KEY (plan_id, tugboat_id)
) COMMENT='引航计划绑定的拖轮组合';

-- 资源锁定（引航员/拖轮），确认时与状态变更同事务写入；窗口为半开区间 [window_start, window_end)
CREATE TABLE IF NOT EXISTS resource_lock (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    resource_type VARCHAR(8) NOT NULL COMMENT '资源类型：PILOT=引航员，TUG=拖轮',
    resource_id BIGINT NOT NULL COMMENT '资源ID（pilot.id 或 tugboat.id）',
    plan_id BIGINT NOT NULL COMMENT '占用该资源的计划ID',
    window_start TIMESTAMP NOT NULL COMMENT '锁定窗口开始（含）',
    window_end TIMESTAMP NOT NULL COMMENT '锁定窗口结束（不含）',
    status VARCHAR(8) NOT NULL COMMENT '锁定状态：ACTIVE=生效中，RELEASED=已释放（核销/取消/改期后）',
    KEY idx_lock_resource (resource_type, resource_id, status)
) COMMENT='引航员与拖轮资源锁定';

-- 执行中计划唯一约束：同一引航员同一时刻仅允许一条执行中计划
CREATE TABLE IF NOT EXISTS executing_lock (
    pilot_id BIGINT PRIMARY KEY COMMENT '引航员ID，主键保证同一引航员仅一条执行中计划',
    plan_id BIGINT NOT NULL COMMENT '执行中的计划ID'
) COMMENT='引航员执行中计划唯一锁';

-- 迟到取消违约记录（合成记录，不涉及真实结算）
CREATE TABLE IF NOT EXISTS breach_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    plan_id BIGINT NOT NULL COMMENT '违约计划ID',
    reason VARCHAR(255) NOT NULL COMMENT '违约原因',
    created_at TIMESTAMP NOT NULL COMMENT '记录生成时间'
) COMMENT='迟到取消合成违约记录';

-- 写操作幂等记录：request_id 唯一，指纹覆盖 requestId 与全部业务字段
CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(128) PRIMARY KEY COMMENT '幂等键，客户端提供',
    fingerprint VARCHAR(128) NOT NULL COMMENT '请求指纹：requestId 与全部业务字段的 SHA-256',
    action VARCHAR(32) NOT NULL COMMENT '写操作类型：APPLY/ARRANGE/CONFIRM/START/SETTLE/CANCEL/RESCHEDULE',
    plan_id BIGINT NULL COMMENT '关联计划ID',
    response_body CLOB NULL COMMENT '首次成功响应快照（JSON），同键同参原样返回',
    created_at TIMESTAMP NOT NULL COMMENT '记录创建时间'
) COMMENT='写操作幂等记录';
