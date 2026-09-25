-- 航班时刻协调服务建表脚本（H2 MODE=MySQL 兼容语法，数据仅保留在 JVM 生命周期内）

CREATE TABLE IF NOT EXISTS slot_request (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    request_id VARCHAR(64) NOT NULL COMMENT '申请幂等键，同一系列重复提交使用相同值',
    airline VARCHAR(8) NOT NULL COMMENT '航司二字码，如 CA',
    flight_no VARCHAR(16) NOT NULL COMMENT '航班号，如 CA1234',
    slot_time VARCHAR(5) NOT NULL COMMENT '申请时刻，HH:mm，5 分钟粒度（如 08:35），时区 Asia/Shanghai',
    weeks_csv VARCHAR(512) NOT NULL COMMENT '覆盖周列表的规范化表示：升序去重后逗号拼接，如 1,2,3',
    fingerprint VARCHAR(128) NOT NULL COMMENT '幂等指纹：requestId+航班号+时刻+覆盖周规范化表示的 SHA-256',
    status VARCHAR(16) NOT NULL COMMENT '系列状态：ALLOCATED=已占用，RETURNED=已退回释放',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（应用服务器时区）',
    CONSTRAINT uk_slot_request_req UNIQUE (request_id)
) COMMENT='航班时刻系列申请单';

CREATE TABLE IF NOT EXISTS slot_allocation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    request_id VARCHAR(64) NOT NULL COMMENT '所属系列申请幂等键',
    airline VARCHAR(8) NOT NULL COMMENT '航司二字码',
    flight_no VARCHAR(16) NOT NULL COMMENT '航班号',
    slot_time VARCHAR(5) NOT NULL COMMENT '占用时刻，HH:mm，5 分钟粒度，时区 Asia/Shanghai',
    week_no INT NOT NULL COMMENT '覆盖周序号，从 1 开始',
    alloc_type VARCHAR(16) NOT NULL COMMENT '占用类型：RETAINED=历史优先权保留，POOL=公共池分配',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（应用服务器时区）',
    CONSTRAINT uk_slot_allocation_slot_week UNIQUE (slot_time, week_no)
) COMMENT='时刻占用表：同一时刻同一周只能分配给一家航司，唯一约束保证并发唯一成功';

CREATE TABLE IF NOT EXISTS slot_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    airline VARCHAR(8) NOT NULL COMMENT '航司二字码',
    flight_no VARCHAR(16) NOT NULL COMMENT '航班号',
    slot_time VARCHAR(5) NOT NULL COMMENT '上一航季同系列时刻，HH:mm',
    executed INT NOT NULL COMMENT '上一航季实际执行班次',
    planned INT NOT NULL COMMENT '上一航季计划班次；执行率=executed/planned，达到 80% 即保留原时刻',
    CONSTRAINT uk_slot_history_series UNIQUE (airline, flight_no, slot_time)
) COMMENT='上一航季同系列历史执行率（合成数据）';

CREATE TABLE IF NOT EXISTS slot_publication (
    id BIGINT PRIMARY KEY COMMENT '单行控制表，固定为 1',
    published_at TIMESTAMP NULL COMMENT '发布时间（应用服务器时区）；NULL 表示本期尚未发布'
) COMMENT='分配表发布控制：发布后本期冻结，新申请一律拒绝';

CREATE TABLE IF NOT EXISTS slot_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    slot_time VARCHAR(5) NOT NULL COMMENT '时刻，HH:mm，时区 Asia/Shanghai',
    week_no INT NOT NULL COMMENT '周序号，从 1 开始',
    airline VARCHAR(8) NOT NULL COMMENT '航司二字码',
    flight_no VARCHAR(16) NOT NULL COMMENT '航班号',
    alloc_type VARCHAR(16) NOT NULL COMMENT '占用类型：RETAINED=历史优先权保留，POOL=公共池分配',
    CONSTRAINT uk_slot_snapshot_slot_week UNIQUE (slot_time, week_no)
) COMMENT='已发布的分配表快照；发布后迟到退回不改变本快照';
