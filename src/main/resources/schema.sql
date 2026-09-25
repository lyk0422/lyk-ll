-- 游乐设施日检与载客联锁服务建表脚本（H2 MySQL 兼容模式，数据仅保留在 JVM 生命周期内）

CREATE TABLE IF NOT EXISTS ride (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '设备主键',
    name VARCHAR(128) NOT NULL COMMENT '设备名称（合成数据）',
    status VARCHAR(32) NOT NULL COMMENT '设备状态：PENDING_CHECK待日检/CHECKED日检合格/READY试运行合格可开放/OPEN开放载客/SUSPENDED超阈值缓停/STOPPED故障停运/RECHECKED复检合格/ARCHIVED闭园归档终态',
    max_load INT NOT NULL COMMENT '载荷阈值（人），恰等阈值合格，超过触发缓停',
    max_speed INT NOT NULL COMMENT '速度阈值（rpm），恰等阈值合格，超过触发缓停',
    trial_count INT NOT NULL DEFAULT 0 COMMENT '当日已连续合格试运行次数，恰3次方可载客',
    reopen_count INT NOT NULL DEFAULT 0 COMMENT '当日复开次数，恰2次合法，超出当日禁开',
    check_date DATE NULL COMMENT '最近一次日检合格日期（Asia/Shanghai），NULL表示未日检',
    reopen_date DATE NULL COMMENT '最近一次复开日期，用于跨日重置复开计数，NULL表示从未复开',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号，保证同一设备同时刻只有一条状态链',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（Asia/Shanghai）'
) COMMENT='游乐设施主表';

CREATE TABLE IF NOT EXISTS daily_check (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '日检记录主键',
    ride_id BIGINT NOT NULL COMMENT '设备ID，关联ride.id',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求ID',
    check_date DATE NOT NULL COMMENT '日检日期（Asia/Shanghai）',
    all_passed BOOLEAN NOT NULL COMMENT '清单是否逐项全部合格',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间（Asia/Shanghai）'
) COMMENT='日检记录表，清单可复算';

CREATE TABLE IF NOT EXISTS daily_check_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '日检清单项主键',
    check_id BIGINT NOT NULL COMMENT '日检记录ID，关联daily_check.id',
    item_name VARCHAR(64) NOT NULL COMMENT '检查项名称，如压杠/安全带/联锁装置',
    passed BOOLEAN NOT NULL COMMENT '该项是否合格'
) COMMENT='日检清单明细表';

CREATE TABLE IF NOT EXISTS trial_run (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '试运行记录主键',
    ride_id BIGINT NOT NULL COMMENT '设备ID，关联ride.id',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求ID',
    seq INT NOT NULL COMMENT '本次试运行序号（当日第几次）',
    normal BOOLEAN NOT NULL COMMENT '空载试运行是否无异常',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间（Asia/Shanghai）'
) COMMENT='试运行记录表，可复算';

CREATE TABLE IF NOT EXISTS runtime_reading (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '运行读数主键',
    ride_id BIGINT NOT NULL COMMENT '设备ID，关联ride.id',
    load_value INT NOT NULL COMMENT '载荷读数（人）',
    speed_value INT NOT NULL COMMENT '速度读数（rpm）',
    exceeded BOOLEAN NOT NULL COMMENT '是否超过阈值（恰等阈值为合格）',
    late BOOLEAN NOT NULL COMMENT '是否归档后迟到的数据（仅记录迟到事件，不改变状态）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间（Asia/Shanghai）'
) COMMENT='运行监测读数表（合成数据）';

CREATE TABLE IF NOT EXISTS ride_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '事件主键',
    ride_id BIGINT NOT NULL COMMENT '设备ID，关联ride.id',
    event_type VARCHAR(32) NOT NULL COMMENT '事件类型：DAILY_CHECK/TRIAL_RUN/OPEN/READING/SLOW_STOP/FAULT_STOP/RECHECK/REOPEN/ARCHIVE/LATE_READING',
    detail VARCHAR(512) NULL COMMENT '事件详情（合成描述），NULL表示无补充说明',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '事件时间（Asia/Shanghai）'
) COMMENT='设备启停链事件表，可复算';

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(64) PRIMARY KEY COMMENT '幂等请求ID，全局唯一',
    ride_id BIGINT NOT NULL COMMENT '设备ID，关联ride.id',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：DAILY_CHECK/TRIAL_RUN/OPEN/SAFETY_FAULT/RECHECK/REOPEN/ARCHIVE',
    fingerprint VARCHAR(64) NOT NULL COMMENT '请求指纹，覆盖requestId与全部业务字段的SHA-256',
    response_status INT NOT NULL COMMENT '首次执行的HTTP状态码',
    response_body CLOB NOT NULL COMMENT '首次执行的响应体JSON快照',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间（Asia/Shanghai）'
) COMMENT='幂等记录表：同键同参返回首次结果，同键异参返回409';
