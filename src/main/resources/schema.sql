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
    status           VARCHAR(24)  NOT NULL COMMENT '状态:SUBMITTED/IMPACT_ANALYZED/APPROVED/EXECUTING/VERIFIED/SEALED/REJECTED/ROLLING_BACK/ROLLED_BACK/WINDOW_EXPIRED/DEPENDENCY_BLOCKED',
    impact_systems   VARCHAR(1000) COMMENT '影响分析命中的业务系统编码,逗号分隔;NULL表示尚未分析',
    fingerprint      VARCHAR(128) NOT NULL COMMENT 'requestId+全部业务字段的SHA-256指纹',
    reject_reason    VARCHAR(500) COMMENT '拒绝原因;NULL表示未拒绝',
    exec_started_at  TIMESTAMP    COMMENT '窗口执行开始时间;NULL表示尚未进入执行',
    rollback_cause   VARCHAR(32)  COMMENT '回退判据:HARDCHECK_FAILED硬性检查失败/CRITICAL_TIMEOUT验证点超时/CRITICAL_FAILED验证点失败/STEP_FAILED普通步骤失败/WINDOW_EXPIRED窗口超期;NULL表示未回退',
    version_no       BIGINT       NOT NULL DEFAULT 1 COMMENT '业务版本号:从1开始,每次成功变更+1,发布批次时按expectedVersion校验',
    open_batch_id    VARCHAR(64)  COMMENT '当前绑定的未结束批次ID;NULL表示不属于任何未结束批次,批次关闭后置空以便重新组批',
    blocked_flag     BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '依赖阻断标记:TRUE表示已被失败前置阻断(状态DEPENDENCY_BLOCKED)',
    blocked_sources  VARCHAR(1000) COMMENT '首个阻断来源集合:直接失败前置申请编号,逗号分隔按编号排序;NULL表示未阻断',
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

-- 批次相关写操作的全局互斥行：批次发布与成员启动/回报/巡检/封板均先锁此行，
-- 再按 批次行 -> 成员申请行(按编号排序) -> 步骤行 的顺序加锁，保证并发只形成一致顺序。
CREATE TABLE IF NOT EXISTS cutover_mutex (
    id   INT PRIMARY KEY,
    name VARCHAR(32) NOT NULL COMMENT '互斥锁名称,固定 global'
);
MERGE INTO cutover_mutex (id, name) KEY(id) VALUES (1, 'global');

-- 割接批次主表：发布时原子冻结成员、依赖与窗口快照
CREATE TABLE IF NOT EXISTS cutover_batch (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id         VARCHAR(64)  NOT NULL COMMENT '批次ID(发布请求幂等键)',
    frozen_at        TIMESTAMP    NOT NULL COMMENT '批次发布冻结时刻(Asia/Shanghai)',
    members_json     VARCHAR(4000) NOT NULL COMMENT '冻结的成员快照:JSON数组,元素{requestId,expectedVersion,windowStart,windowEnd},按requestId排序',
    edges_json       VARCHAR(4000) NOT NULL COMMENT '冻结的有向依赖快照:JSON数组,元素{from,to},按(from,to)排序',
    status           VARCHAR(16)  NOT NULL COMMENT '批次状态:OPEN未结束(仍有成员未到终态)/CLOSED已结束(全部成员终态)',
    fingerprint      VARCHAR(128) NOT NULL COMMENT 'requestId+版本+成员集合+依赖集合+发布时刻的结构化SHA-256指纹',
    result_snapshot  VARCHAR(2000) COMMENT '首次发布结果快照(JSON),供同键同参重放',
    created_at       TIMESTAMP    NOT NULL COMMENT '创建时间',
    updated_at       TIMESTAMP    NOT NULL COMMENT '最近更新时间',
    UNIQUE (batch_id)
);

-- 批次成员冻结表：一项申请最多属于一个未结束批次，由 request_id 上的部分唯一索引兜底
CREATE TABLE IF NOT EXISTS cutover_batch_member (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id         VARCHAR(64)  NOT NULL COMMENT '所属批次ID',
    request_id       VARCHAR(64)  NOT NULL COMMENT '成员申请ID',
    expected_version BIGINT       NOT NULL COMMENT '发布时冻结的申请版本',
    window_start     TIMESTAMP    NOT NULL COMMENT '冻结的窗口开始时间(含),Asia/Shanghai',
    window_end       TIMESTAMP    NOT NULL COMMENT '冻结的窗口结束时间(不含),Asia/Shanghai',
    ord              INT          NOT NULL COMMENT '成员在批次内按requestId排序的序号',
    created_at       TIMESTAMP    NOT NULL COMMENT '创建时间',
    UNIQUE (batch_id, request_id)
);

-- 批次有向依赖边冻结表：边两端必须都是批内成员
CREATE TABLE IF NOT EXISTS cutover_batch_edge (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id         VARCHAR(64)  NOT NULL COMMENT '所属批次ID',
    from_request     VARCHAR(64)  NOT NULL COMMENT '前置申请编号(边的起点)',
    to_request       VARCHAR(64)  NOT NULL COMMENT '后继申请编号(边的终点)',
    ord              INT          NOT NULL COMMENT '边在批次内按(from,to)排序的序号',
    created_at       TIMESTAMP    NOT NULL COMMENT '创建时间',
    UNIQUE (batch_id, from_request, to_request)
);

-- 依赖阻断明细表：仅记录“首个阻断来源集合”（失败前置的直接后继），传播不重复记源
CREATE TABLE IF NOT EXISTS cutover_block (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id         VARCHAR(64)  NOT NULL COMMENT '所属批次ID',
    request_id       VARCHAR(64)  NOT NULL COMMENT '被阻断的申请ID',
    source_request   VARCHAR(64)  NOT NULL COMMENT '阻断来源申请ID(进入回退/超期的失败前置)',
    created_at       TIMESTAMP    NOT NULL COMMENT '阻断生成时间(与失败前置状态提交同事务)',
    UNIQUE (batch_id, request_id, source_request)
);

CREATE INDEX IF NOT EXISTS idx_member_request ON cutover_batch_member(request_id);
CREATE INDEX IF NOT EXISTS idx_edge_batch ON cutover_batch_edge(batch_id);
CREATE INDEX IF NOT EXISTS idx_block_batch ON cutover_block(batch_id);
CREATE INDEX IF NOT EXISTS idx_block_request ON cutover_block(request_id);

-- “一项申请最多属于一个未结束批次”由 cutover_request.open_batch_id 在
-- 发布事务内对申请行 FOR UPDATE 后判定并写回；批次关闭时置空 open_batch_id。
