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
    inspection_required BOOLEAN NOT NULL DEFAULT FALSE COMMENT '受检标志：true须年度检验证书与大修监督检验门禁，false为旧设备行为不变',
    overhaul_version BIGINT NOT NULL DEFAULT 0 COMMENT '设备大修版本（检验周期版本），登记大修时原子递增，0表示未登记大修',
    current_certificate_id BIGINT NULL COMMENT '当前年度检验证书ID（唯一当前指针，换证原子切换），NULL表示无当前证书',
    version BIGINT NOT NULL DEFAULT 1 COMMENT '乐观锁版本号，从1开始、成功变更加1，保证同一设备同时刻只有一条状态链',
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
    event_type VARCHAR(32) NOT NULL COMMENT '事件类型：DAILY_CHECK/TRIAL_RUN/OPEN/READING/SLOW_STOP/FAULT_STOP/RECHECK/REOPEN/ARCHIVE/LATE_READING/CERT_ISSUE/CERT_REVOKE/OVERHAUL/SUPERVISION_INSPECTION/CERT_INVALID_ALARM',
    detail VARCHAR(512) NULL COMMENT '事件详情（合成描述），NULL表示无补充说明',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '事件时间（Asia/Shanghai）'
) COMMENT='设备启停链事件表，可复算';

CREATE TABLE IF NOT EXISTS annual_certificate (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '年度检验证书主键',
    ride_id BIGINT NOT NULL COMMENT '设备ID，关联ride.id（证书固定设备编号）',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求ID',
    passed_at TIMESTAMP NOT NULL COMMENT '检验合格时刻（UTC精确时刻）',
    valid_until TIMESTAMP NOT NULL COMMENT '有效截止（UTC），默认合格时刻+365×24小时；UTC下now<截止有效、恰截止失效',
    revoked BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已撤销，TRUE则不再具备开放资格',
    revoked_at TIMESTAMP NULL COMMENT '撤销时刻（UTC），NULL表示未撤销',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间（UTC）'
) COMMENT='年度检验证书表：历史证书不可覆盖，换证仅原子切换ride.current_certificate_id唯一当前指针';

CREATE TABLE IF NOT EXISTS overhaul_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '大修记录主键',
    ride_id BIGINT NOT NULL COMMENT '设备ID，关联ride.id',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求ID',
    overhaul_version BIGINT NOT NULL COMMENT '本次登记后的设备大修版本',
    registered_at TIMESTAMP NOT NULL COMMENT '大修登记时刻（UTC精确时刻）',
    note VARCHAR(256) NULL COMMENT '大修说明（合成数据），NULL表示无',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间（UTC）'
) COMMENT='大修登记记录表：登记即原子递增大修版本、暂停开放并使旧监督检验失效';

CREATE TABLE IF NOT EXISTS supervision_inspection (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '监督检验记录主键',
    ride_id BIGINT NOT NULL COMMENT '设备ID，关联ride.id',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求ID',
    overhaul_version BIGINT NOT NULL COMMENT '绑定的大修版本，须等于确认时设备当前大修版本',
    inspector_one VARCHAR(64) NOT NULL COMMENT '检验人一',
    inspector_two VARCHAR(64) NOT NULL COMMENT '检验人二，须与检验人一不同',
    all_passed BOOLEAN NOT NULL COMMENT '全清单是否逐项合格',
    confirmed_at TIMESTAMP NOT NULL COMMENT '确认时刻（UTC精确时刻）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间（UTC）'
) COMMENT='大修后监督检验表：两名不同检验人共同确认全清单合格，仅恢复开放资格不自动开放';

CREATE TABLE IF NOT EXISTS supervision_inspection_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '监督检验清单项主键',
    inspection_id BIGINT NOT NULL COMMENT '监督检验记录ID，关联supervision_inspection.id',
    item_name VARCHAR(64) NOT NULL COMMENT '检验项名称',
    passed BOOLEAN NOT NULL COMMENT '该项是否合格'
) COMMENT='监督检验清单明细表，可复算';

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(64) PRIMARY KEY COMMENT '幂等请求ID，全局唯一',
    ride_id BIGINT NOT NULL COMMENT '设备ID，关联ride.id',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：DAILY_CHECK/TRIAL_RUN/OPEN/SAFETY_FAULT/RECHECK/REOPEN/ARCHIVE/CERT_ISSUE/CERT_REVOKE/OVERHAUL/SUPERVISION_INSPECTION',
    fingerprint VARCHAR(64) NOT NULL COMMENT '请求指纹，覆盖requestId与全部业务字段的SHA-256',
    response_status INT NOT NULL COMMENT '首次执行的HTTP状态码',
    response_body CLOB NOT NULL COMMENT '首次执行的响应体JSON快照',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间（Asia/Shanghai）'
) COMMENT='幂等记录表：同键同参返回首次结果，同键异参返回409';
