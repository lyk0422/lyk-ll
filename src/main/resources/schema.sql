-- 游乐设施日检与载客联锁服务建表脚本（H2 MySQL 兼容模式，数据仅保留在 JVM 生命周期内）

CREATE TABLE IF NOT EXISTS ride (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '设备主键',
    name VARCHAR(128) NOT NULL COMMENT '设备名称（合成数据）',
    status VARCHAR(32) NOT NULL COMMENT '设备状态：PENDING_CHECK待日检/CHECKED日检合格/READY试运行合格可开放/OPEN开放载客/SUSPENDED超阈值缓停/STOPPED故障停运/RECHECKED复检或大修监督检验合格待复开/OVERHAUL大修中暂停开放/ARCHIVED闭园归档终态',
    max_load INT NOT NULL COMMENT '载荷阈值（人），恰等阈值合格，超过触发缓停',
    max_speed INT NOT NULL COMMENT '速度阈值（rpm），恰等阈值合格，超过触发缓停',
    trial_count INT NOT NULL DEFAULT 0 COMMENT '当日已连续合格试运行次数，恰3次方可载客',
    reopen_count INT NOT NULL DEFAULT 0 COMMENT '当日复开次数，恰2次合法，超出当日禁开；大修监督检验合格不重置该计数',
    check_date DATE NULL COMMENT '最近一次日检合格日期（Asia/Shanghai），NULL表示未日检',
    reopen_date DATE NULL COMMENT '最近一次复开日期，用于跨日重置复开计数，NULL表示从未复开',
    inspection_required BOOLEAN NOT NULL DEFAULT FALSE COMMENT '合成受检标志：TRUE为受检设备，开放/复开须持当前有效年度证书；FALSE旧设备行为不变',
    inspection_cycle_version INT NOT NULL DEFAULT 1 COMMENT '合成检验周期版本，登记时为1，不引用真实法定周期；发证时快照到证书',
    overhaul_version INT NOT NULL DEFAULT 0 COMMENT '设备大修版本，0表示从未大修，登记大修原子加1，监督检验须绑定当前版本',
    current_certificate_id BIGINT NULL COMMENT '当前年度证书唯一指针，关联annual_certificate.id；NULL表示无当前证书（未发/已撤销）',
    version BIGINT NOT NULL DEFAULT 1 COMMENT '乐观锁版本号，从1开始，每次成功变更加1，保证同一设备同时刻只有一条状态链',
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
    warning BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否仅记录并告警告警读数：TRUE表示年度证书已失效（过期/撤销/大修），读数不改变资格',
    late BOOLEAN NOT NULL COMMENT '是否归档后迟到的数据（仅记录迟到事件，不改变状态）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间（Asia/Shanghai）'
) COMMENT='运行监测读数表（合成数据）';

CREATE TABLE IF NOT EXISTS ride_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '事件主键',
    ride_id BIGINT NOT NULL COMMENT '设备ID，关联ride.id',
    event_type VARCHAR(40) NOT NULL COMMENT '事件类型：DAILY_CHECK/TRIAL_RUN/OPEN/READING/SLOW_STOP/FAULT_STOP/RECHECK/REOPEN/ARCHIVE/LATE_READING/ANNUAL_CERT_ISSUED/CERT_RENEWED/CERT_REVOKED/OVERHAUL_REGISTERED/SUPERVISORY_INSPECTION/CERT_WARNING',
    detail VARCHAR(512) NULL COMMENT '事件详情（合成描述），NULL表示无补充说明',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '事件时间（Asia/Shanghai）'
) COMMENT='设备启停链事件表，可复算';

-- 年度证书（合成规则，不引用真实法定周期）：只增不改事实字段，状态仅沿 ACTIVE→SUPERSEDED/REVOKED 流转
CREATE TABLE IF NOT EXISTS annual_certificate (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '年度证书主键',
    ride_id BIGINT NOT NULL COMMENT '固定设备编号，关联ride.id，证书不可跨设备使用',
    certificate_no VARCHAR(64) NOT NULL COMMENT '合成证书编号（含设备编号与本设备内序号），同设备唯一',
    cycle_version INT NOT NULL COMMENT '发证时设备的合成检验周期版本快照',
    overhaul_version INT NOT NULL DEFAULT 0 COMMENT '发证时设备大修版本快照：登记大修后旧证快照版本落后，旧证可查但不足以复开，须换证后与当前大修版本一致才恢复门禁',
    issued_at TIMESTAMP NOT NULL COMMENT '检验合格时刻（UTC精确时刻）',
    expires_at TIMESTAMP NOT NULL COMMENT '有效截止（UTC精确时刻），默认合格时刻加365×24小时；UTC下now<截止有效，恰截止失效',
    status VARCHAR(16) NOT NULL COMMENT '证书状态：ACTIVE当前有效/SUPERSEDED换证后历史/REVOKED已撤销',
    revoked_at TIMESTAMP NULL COMMENT '撤销时刻（UTC），NULL表示未撤销',
    request_id VARCHAR(64) NOT NULL COMMENT '发证/换证幂等请求ID',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登记时间（Asia/Shanghai）',
    CONSTRAINT uq_annual_certificate_no UNIQUE (ride_id, certificate_no)
) COMMENT='年度证书表：历史证书不可覆盖，当前唯一指针存于ride.current_certificate_id';

-- 大修后的监督检验：绑定登记时的大修版本，两名不同检验人共同确认全清单合格
CREATE TABLE IF NOT EXISTS supervisory_inspection (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '监督检验记录主键',
    ride_id BIGINT NOT NULL COMMENT '设备ID，关联ride.id',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求ID',
    overhaul_version INT NOT NULL COMMENT '绑定的设备大修版本，仅与ride.overhaul_version相等时有效，新大修使其自然失效',
    inspector_a VARCHAR(64) NOT NULL COMMENT '检验人A（合成姓名），不得与检验人B相同',
    inspector_b VARCHAR(64) NOT NULL COMMENT '检验人B（合成姓名），不得与检验人A相同',
    all_passed BOOLEAN NOT NULL COMMENT '全清单是否逐项合格，FALSE不恢复开放资格',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间（Asia/Shanghai）'
) COMMENT='大修监督检验记录表，按大修版本判定有效性';

CREATE TABLE IF NOT EXISTS supervisory_check_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '监督检验清单项主键',
    inspection_id BIGINT NOT NULL COMMENT '监督检验记录ID，关联supervisory_inspection.id',
    item_name VARCHAR(64) NOT NULL COMMENT '检查项名称（合成）',
    passed BOOLEAN NOT NULL COMMENT '该项是否合格'
) COMMENT='监督检验清单明细表';

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(64) PRIMARY KEY COMMENT '幂等请求ID，全局唯一',
    ride_id BIGINT NOT NULL COMMENT '设备ID，关联ride.id',
    operation VARCHAR(40) NOT NULL COMMENT '操作类型：DAILY_CHECK/TRIAL_RUN/OPEN/SAFETY_FAULT/RECHECK/REOPEN/ARCHIVE/ANNUAL_CERT_ISSUE/CERT_RENEW/CERT_REVOKE/OVERHAUL/SUPERVISORY_INSPECTION',
    fingerprint VARCHAR(64) NOT NULL COMMENT '请求指纹：对requestId与全部业务字段（含版本、集合、精确时刻）做长度前缀结构化SHA-256，文本含分隔符也不产生歧义',
    response_status INT NOT NULL COMMENT '首次执行的HTTP状态码',
    response_body CLOB NOT NULL COMMENT '首次执行的响应体JSON快照',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间（Asia/Shanghai）'
) COMMENT='幂等记录表：同键同参返回首次结果，同键异参返回409，失败不占键';
