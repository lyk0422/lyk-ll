-- 数据中心割接窗口服务建表脚本（H2 MODE=MySQL，同时兼容 MySQL 语法子集）
-- 时间均以 TIMESTAMP 存储，业务口径为 Asia/Shanghai。

-- 合成数据：业务系统
CREATE TABLE IF NOT EXISTS biz_system (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    system_code  VARCHAR(64)  NOT NULL COMMENT '业务系统编码',
    system_name  VARCHAR(128) NOT NULL COMMENT '业务系统名称',
    UNIQUE (system_code)
);

-- 合成数据：设备
CREATE TABLE IF NOT EXISTS device (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    device_code VARCHAR(64)  NOT NULL COMMENT '设备编码',
    device_name VARCHAR(128) NOT NULL COMMENT '设备名称',
    UNIQUE (device_code)
);

-- 合成数据：设备-业务系统关联（影响分析依据）
CREATE TABLE IF NOT EXISTS device_biz (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    device_code VARCHAR(64) NOT NULL COMMENT '设备编码',
    system_code VARCHAR(64) NOT NULL COMMENT '关联业务系统编码',
    UNIQUE (device_code, system_code)
);

-- 割接申请主表
CREATE TABLE IF NOT EXISTS cutover_request (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id       VARCHAR(64)  NOT NULL COMMENT '幂等键：申请/回报请求ID',
    title            VARCHAR(200) NOT NULL COMMENT '割接标题',
    device_code      VARCHAR(64)  NOT NULL COMMENT '割接设备编码',
    priority         VARCHAR(16)  NOT NULL COMMENT '优先级：HIGH/MEDIUM/LOW',
    window_start     TIMESTAMP    NOT NULL COMMENT '窗口开始时间(含),Asia/Shanghai',
    window_end       TIMESTAMP    NOT NULL COMMENT '窗口结束时间(不含),Asia/Shanghai',
    status           VARCHAR(24)  NOT NULL COMMENT '状态:SUBMITTED/IMPACT_ANALYZED/APPROVED/EXECUTING/VERIFIED/SEALED/REJECTED/ROLLING_BACK/ROLLED_BACK/WINDOW_EXPIRED',
    impact_systems   VARCHAR(1000) COMMENT '影响分析命中的业务系统编码,逗号分隔;NULL表示尚未分析',
    fingerprint      VARCHAR(128) NOT NULL COMMENT 'requestId+全部业务字段的SHA-256指纹',
    reject_reason    VARCHAR(500) COMMENT '拒绝原因;NULL表示未拒绝',
    exec_started_at  TIMESTAMP    COMMENT '窗口执行开始时间;NULL表示尚未进入执行',
    rollback_cause   VARCHAR(32)  COMMENT '回退判据:HARDCHECK_FAILED硬性检查失败/CRITICAL_TIMEOUT验证点超时/CRITICAL_FAILED验证点失败/STEP_FAILED普通步骤失败/WINDOW_EXPIRED窗口超期;NULL表示未回退',
    version          INT          NOT NULL DEFAULT 1 COMMENT '乐观锁版本,从1开始,每次成功变更加1;批次发布按expectedVersion校验',
    created_at       TIMESTAMP    NOT NULL COMMENT '创建时间',
    updated_at       TIMESTAMP    NOT NULL COMMENT '最近更新时间',
    UNIQUE (request_id)
);

-- 割接步骤表
CREATE TABLE IF NOT EXISTS cutover_step (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id      VARCHAR(64)  NOT NULL COMMENT '所属割接申请ID',
    seq_no          INT          NOT NULL COMMENT '步骤序号,从1开始按序执行',
    step_name       VARCHAR(200) NOT NULL COMMENT '步骤名称',
    step_type       VARCHAR(24)  NOT NULL COMMENT '类型:NORMAL普通步骤/CRITICAL关键验证点/HARDCHECK硬性检查/ROLLBACK回退步骤',
    status          VARCHAR(24)  NOT NULL COMMENT '状态:PENDING待执行/RUNNING执行中/SUCCESS成功/FAILED失败/FROZEN已冻结/SKIPPED跳过',
    started_at      TIMESTAMP    COMMENT '步骤开始执行时间(关键验证点超时起算点);NULL表示未开始',
    reported_at     TIMESTAMP    COMMENT '步骤回报时间;NULL表示未回报',
    created_at      TIMESTAMP    NOT NULL COMMENT '创建时间',
    updated_at      TIMESTAMP    NOT NULL COMMENT '最近更新时间',
    UNIQUE (request_id, seq_no)
);

-- 步骤回报/事件表（幂等记录、迟到事件、回退判据命中记录）
CREATE TABLE IF NOT EXISTS cutover_event (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id       VARCHAR(64)  NOT NULL COMMENT '所属割接申请ID',
    request_uid      VARCHAR(96)  NOT NULL COMMENT '回报请求方幂等键',
    event_type       VARCHAR(32)  NOT NULL COMMENT '事件类型:STEP_REPORT/ROLLBACK_TRIGGER/LATE_REPORT/SEAL等',
    seq_no           INT          COMMENT '关联步骤序号;NULL表示申请级事件',
    payload_fingerprint VARCHAR(128) NOT NULL COMMENT 'requestId+业务字段指纹,用于异参409',
    result_snapshot  VARCHAR(2000) COMMENT '首次处理结果快照(JSON)',
    late_flag        BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '是否为封板后迟到事件:TRUE迟到/FALSE正常',
    created_at       TIMESTAMP    NOT NULL COMMENT '落库时间',
    UNIQUE (request_uid)
);

CREATE INDEX IF NOT EXISTS idx_step_request ON cutover_step(request_id);
CREATE INDEX IF NOT EXISTS idx_event_request ON cutover_event(request_id);

-- 割接批次主表：发布时原子冻结成员、依赖与窗口快照
CREATE TABLE IF NOT EXISTS cutover_batch (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id     VARCHAR(64)  NOT NULL COMMENT '批次编号,兼作发布幂等键requestId',
    batch_name   VARCHAR(200) NOT NULL COMMENT '批次名称',
    status       VARCHAR(24)  NOT NULL COMMENT '状态:PUBLISHED已发布(未结束)/CLOSED已结束',
    version      INT          NOT NULL DEFAULT 1 COMMENT '乐观锁版本,从1开始,成功变更(如结束批次)加1',
    fingerprint  VARCHAR(128) NOT NULL COMMENT 'requestId+成员版本集合+依赖集合+精确时刻的SHA-256指纹',
    created_at   TIMESTAMP    NOT NULL COMMENT '创建时间,Asia/Shanghai',
    updated_at   TIMESTAMP    NOT NULL COMMENT '最近更新时间,Asia/Shanghai',
    UNIQUE (batch_id)
);

-- 批次成员表：发布时冻结的申请版本与窗口快照；一项申请最多属于一个未结束批次
CREATE TABLE IF NOT EXISTS cutover_batch_member (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id        VARCHAR(64)  NOT NULL COMMENT '所属批次编号',
    request_id      VARCHAR(64)  NOT NULL COMMENT '成员割接申请ID',
    request_version INT          NOT NULL COMMENT '发布时冻结的成员申请版本快照',
    window_start    TIMESTAMP    NOT NULL COMMENT '发布时冻结的窗口开始快照(含),Asia/Shanghai',
    window_end      TIMESTAMP    NOT NULL COMMENT '发布时冻结的窗口结束快照(不含),Asia/Shanghai',
    member_status   VARCHAR(24)  NOT NULL COMMENT '成员状态:READY待执行/STARTED已启动/BLOCKED依赖阻断',
    block_sources   VARCHAR(1000) COMMENT '首个阻断来源集合(直接前置中已回退/超期/被阻断者),逗号分隔,升序;NULL表示未阻断,一旦写入不再覆盖',
    created_at      TIMESTAMP    NOT NULL COMMENT '创建时间,Asia/Shanghai',
    updated_at      TIMESTAMP    NOT NULL COMMENT '最近更新时间,Asia/Shanghai',
    UNIQUE (batch_id, request_id)
);

-- 批次有向前置依赖边：两端必须都在批内，禁止自环与环路
CREATE TABLE IF NOT EXISTS cutover_batch_edge (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id    VARCHAR(64) NOT NULL COMMENT '所属批次编号',
    predecessor VARCHAR(64) NOT NULL COMMENT '前置申请ID,须先验证通过并封板',
    successor   VARCHAR(64) NOT NULL COMMENT '后继申请ID,前置未封板前不得开始执行',
    created_at  TIMESTAMP   NOT NULL COMMENT '创建时间,Asia/Shanghai',
    UNIQUE (batch_id, predecessor, successor)
);

CREATE INDEX IF NOT EXISTS idx_batch_member_request ON cutover_batch_member(request_id);
CREATE INDEX IF NOT EXISTS idx_batch_edge_successor ON cutover_batch_edge(batch_id, successor);
CREATE INDEX IF NOT EXISTS idx_batch_edge_predecessor ON cutover_batch_edge(batch_id, predecessor);
