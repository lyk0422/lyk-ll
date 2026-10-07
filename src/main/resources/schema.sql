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
    inspection_required BOOLEAN NOT NULL DEFAULT FALSE COMMENT '受检标志：TRUE表示开放/复开须同时满足有效年度证书与当前大修版本监督检验；FALSE为旧设备，行为不变',
    overhaul_version BIGINT NOT NULL DEFAULT 0 COMMENT '检验周期版本（大修版本），登记大修时原子递增，旧监督检验随之失效',
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
    event_type VARCHAR(32) NOT NULL COMMENT '事件类型：DAILY_CHECK/TRIAL_RUN/OPEN/READING/SLOW_STOP/FAULT_STOP/RECHECK/REOPEN/ARCHIVE/LATE_READING/CERT_ISSUE/CERT_REVOKE/OVERHAUL/SUPERVISION/CERT_EXPIRED_ALARM/MAINT_CREATE/MAINT_START/MAINT_UNLOCK/MAINT_FINISH',
    detail VARCHAR(512) NULL COMMENT '事件详情（合成描述），NULL表示无补充说明',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '事件时间（Asia/Shanghai）'
) COMMENT='设备启停链事件表，可复算';

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(64) PRIMARY KEY COMMENT '幂等请求ID，全局唯一',
    ride_id BIGINT NOT NULL COMMENT '设备ID，关联ride.id',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：DAILY_CHECK/TRIAL_RUN/OPEN/SAFETY_FAULT/RECHECK/REOPEN/ARCHIVE/CERT_ISSUE/CERT_REVOKE/OVERHAUL/SUPERVISION/MAINT_CREATE/MAINT_START/MAINT_UNLOCK/MAINT_FINISH',
    fingerprint VARCHAR(64) NOT NULL COMMENT '请求指纹，覆盖requestId与全部业务字段的SHA-256',
    response_status INT NOT NULL COMMENT '首次执行的HTTP状态码',
    response_body CLOB NOT NULL COMMENT '首次执行的响应体JSON快照',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间（Asia/Shanghai）'
) COMMENT='幂等记录表：同键同参返回首次结果，同键异参返回409';

CREATE TABLE IF NOT EXISTS annual_certificate (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '年度证书主键',
    ride_id BIGINT NOT NULL COMMENT '设备ID，关联ride.id，证书固定设备编号不可变更',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求ID',
    passed_at TIMESTAMP NOT NULL COMMENT '检验合格时刻（UTC），合成规则不引用真实法定周期',
    valid_until TIMESTAMP NOT NULL COMMENT '有效截止（UTC），默认合格时刻+365×24小时；now<截止有效，恰截止失效',
    revoked BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已撤销，撤销后不再作为当前证书',
    revoked_at TIMESTAMP NULL COMMENT '撤销时刻（UTC），NULL表示未撤销',
    revoke_reason VARCHAR(256) NULL COMMENT '撤销原因（合成描述），NULL表示未撤销',
    version BIGINT NOT NULL DEFAULT 1 COMMENT '证书版本号，从1开始，每次成功变更（如撤销）加1，修改须携带expectedVersion',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间（Asia/Shanghai）'
) COMMENT='年度检验证书表：历史证书不可覆盖，仅可追加与指针切换';

CREATE TABLE IF NOT EXISTS current_certificate (
    ride_id BIGINT PRIMARY KEY COMMENT '设备ID，关联ride.id，每设备最多一条当前指针',
    certificate_id BIGINT NOT NULL COMMENT '当前年度证书ID，关联annual_certificate.id，换证时原子切换',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '指针最近切换时间（Asia/Shanghai）'
) COMMENT='当前证书唯一指针表：换证原子切换，撤销后删除指针';

CREATE TABLE IF NOT EXISTS overhaul_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '大修记录主键',
    ride_id BIGINT NOT NULL COMMENT '设备ID，关联ride.id',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求ID',
    overhaul_version BIGINT NOT NULL COMMENT '本次大修递增后的检验周期版本（大修版本）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登记时间（Asia/Shanghai）'
) COMMENT='大修登记记录表：登记即原子递增设备大修版本并暂停开放';

CREATE TABLE IF NOT EXISTS supervision_inspection (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '监督检验主键',
    ride_id BIGINT NOT NULL COMMENT '设备ID，关联ride.id',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求ID',
    overhaul_version BIGINT NOT NULL COMMENT '绑定的检验周期版本（大修版本），须等于登记时设备当前大修版本',
    inspector_one VARCHAR(64) NOT NULL COMMENT '检验人一，两名检验人必须不同',
    inspector_two VARCHAR(64) NOT NULL COMMENT '检验人二，两名检验人必须不同',
    all_passed BOOLEAN NOT NULL COMMENT '全清单是否合格，仅全合格才确认',
    version BIGINT NOT NULL DEFAULT 1 COMMENT '记录版本号，从1开始',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '确认时间（Asia/Shanghai）'
) COMMENT='监督检验确认表：两名不同检验人共同确认全清单合格，仅恢复开放资格不自动开放';

CREATE TABLE IF NOT EXISTS supervision_inspection_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '监督检验清单项主键',
    inspection_id BIGINT NOT NULL COMMENT '监督检验ID，关联supervision_inspection.id',
    item_name VARCHAR(64) NOT NULL COMMENT '检验项名称（合成数据）',
    passed BOOLEAN NOT NULL COMMENT '该项是否合格'
) COMMENT='监督检验清单明细表，可复算';

CREATE TABLE IF NOT EXISTS maintenance_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '检修单主键',
    ride_id BIGINT NOT NULL COMMENT '设备ID，关联ride.id',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求ID',
    status VARCHAR(16) NOT NULL COMMENT '检修单状态：PREPARED已建档/IN_PROGRESS进行中/FINISHED已结束',
    version BIGINT NOT NULL DEFAULT 1 COMMENT '单据版本号，从1开始，每次成功修改加1，修改须携带expectedVersion，旧版本409',
    active_ride_id BIGINT NULL COMMENT '进行中占用标记：进行中等于ride.id，其余为NULL；唯一索引保证同设备仅一张进行中检修单',
    started_at TIMESTAMP NULL COMMENT '启动检修时刻（UTC），NULL表示未启动',
    finished_at TIMESTAMP NULL COMMENT '结束检修时刻（UTC），NULL表示未结束',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '建档时间（Asia/Shanghai）'
) COMMENT='检修挂牌单：绑定设备与1至20名不同人员，初始PREPARED，启动后清单冻结';

CREATE UNIQUE INDEX IF NOT EXISTS uk_maintenance_active_ride
    ON maintenance_order (active_ride_id);

CREATE TABLE IF NOT EXISTS maintenance_person (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '挂牌人员主键',
    maintenance_id BIGINT NOT NULL COMMENT '检修单ID，关联maintenance_order.id',
    person_name VARCHAR(64) NOT NULL COMMENT '挂牌人员姓名（合成检修人员）',
    seq INT NOT NULL COMMENT '清单序号，从1开始，启动后清单冻结',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间（Asia/Shanghai）'
) COMMENT='检修挂牌人员清单表：历史清单不可覆盖';

CREATE UNIQUE INDEX IF NOT EXISTS uk_maintenance_person
    ON maintenance_person (maintenance_id, person_name);

CREATE TABLE IF NOT EXISTS maintenance_lock (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '挂牌锁主键',
    maintenance_id BIGINT NOT NULL COMMENT '检修单ID，关联maintenance_order.id',
    person_name VARCHAR(64) NOT NULL COMMENT '锁归属人员姓名，与挂牌清单一一对应',
    removed BOOLEAN NOT NULL DEFAULT FALSE COMMENT '锁是否已撤除，TRUE表示已归还撤锁',
    removed_at TIMESTAMP NULL COMMENT '撤锁时刻（UTC），NULL表示未撤锁',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '建锁时间（Asia/Shanghai）'
) COMMENT='检修挂牌锁表：启动检修时为每人建立唯一未撤锁';

CREATE UNIQUE INDEX IF NOT EXISTS uk_maintenance_lock_person
    ON maintenance_lock (maintenance_id, person_name);

CREATE TABLE IF NOT EXISTS maintenance_lock_removal (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '撤锁签认主键',
    lock_id BIGINT NOT NULL COMMENT '挂牌锁ID，关联maintenance_lock.id，每锁最多一条签认',
    person_name VARCHAR(64) NOT NULL COMMENT '挂牌本人确认，须等于锁归属人员，不能代签',
    supervisor_name VARCHAR(64) NOT NULL COMMENT '监护员确认，须与本人不同',
    removed_at TIMESTAMP NOT NULL COMMENT '撤锁时刻（UTC）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间（Asia/Shanghai）'
) COMMENT='撤锁双人签认表：本人与一名不同监护员共同确认，签认不可覆盖';

CREATE UNIQUE INDEX IF NOT EXISTS uk_maintenance_lock_removal
    ON maintenance_lock_removal (lock_id);
