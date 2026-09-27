-- 列车制动机试验与摘挂核对服务建表脚本（H2 MySQL 兼容模式，数据仅在 JVM 生命周期内保留）

CREATE TABLE IF NOT EXISTS train_job (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '作业主键',
    train_no VARCHAR(64) NOT NULL COMMENT '车次（合成数据）',
    track VARCHAR(32) NOT NULL COMMENT '股道号',
    active_track VARCHAR(32) DEFAULT NULL COMMENT '作业中占用股道；销记终态后置空，唯一约束保证同一股道同时刻仅一列作业中列车',
    status VARCHAR(32) NOT NULL COMMENT '作业状态：ACCEPTED接入/CHECKED编组核对通过/DEPARTURE_CONFIRMED发车确认/CLOSED销记终态',
    consist_version INT NOT NULL COMMENT '编组版本，自1起；摘挂变更后递增，原试验结论按版本作废',
    car_count INT NOT NULL COMMENT '当前在列辆数（不含已摘下车辆）',
    total_length DECIMAL(12,3) NOT NULL COMMENT '当前总换长',
    total_weight DECIMAL(12,3) NOT NULL COMMENT '当前总重，单位吨',
    consist_checked BOOLEAN NOT NULL DEFAULT FALSE COMMENT '当前编组版本是否已通过运统1核对：TRUE已通过',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（JVM 时区）',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间（JVM 时区）',
    CONSTRAINT uk_train_job_active_track UNIQUE (active_track)
);
COMMENT ON TABLE train_job IS '列车作业主表';

CREATE TABLE IF NOT EXISTS train_car (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '车辆主键',
    job_id BIGINT NOT NULL COMMENT '所属作业ID',
    car_no VARCHAR(32) NOT NULL COMMENT '车辆号（合成数据）',
    seq_no INT NOT NULL COMMENT '编组顺位，自1起',
    weight_tons DECIMAL(10,3) NOT NULL COMMENT '车辆重量，单位吨',
    length DECIMAL(10,3) NOT NULL COMMENT '车辆换长',
    valve_closed BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否关门车：TRUE关门车',
    detached BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已摘下：TRUE已摘下不参与辆数/换长/总重统计',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（JVM 时区）',
    CONSTRAINT uk_train_car_job_no UNIQUE (job_id, car_no)
);
COMMENT ON TABLE train_car IS '编组车辆表';

CREATE TABLE IF NOT EXISTS consist_check (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '核对主键',
    job_id BIGINT NOT NULL COMMENT '所属作业ID',
    consist_version INT NOT NULL COMMENT '核对的编组版本',
    expected_car_count INT NOT NULL COMMENT '运统1辆数（合成数据）',
    expected_total_length DECIMAL(12,3) NOT NULL COMMENT '运统1换长（合成数据）',
    expected_total_weight DECIMAL(12,3) NOT NULL COMMENT '运统1总重，单位吨（合成数据）',
    actual_car_count INT NOT NULL COMMENT '实际辆数',
    actual_total_length DECIMAL(12,3) NOT NULL COMMENT '实际换长',
    actual_total_weight DECIMAL(12,3) NOT NULL COMMENT '实际总重，单位吨',
    matched BOOLEAN NOT NULL COMMENT '核对结果：TRUE一致',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求键',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（JVM 时区）'
);
COMMENT ON TABLE consist_check IS '编组核对记录表';

CREATE TABLE IF NOT EXISTS brake_test (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '试验主键',
    job_id BIGINT NOT NULL COMMENT '所属作业ID',
    consist_version INT NOT NULL COMMENT '试验对应编组版本，编组变更后旧版本结论作废',
    test_type VARCHAR(16) NOT NULL COMMENT '试验类型：SENSITIVITY感度/STABILITY安定',
    pressure_reduction INT NOT NULL COMMENT '减压量，单位kPa',
    pipe_pressure_before INT NOT NULL COMMENT '试验前列车管风压，单位kPa；须恢复至定压500kPa方可进行下一项',
    all_cars_braked BOOLEAN DEFAULT NULL COMMENT '感度试验：全列是否均制动；安定试验为NULL',
    emergency_occurred BOOLEAN DEFAULT NULL COMMENT '安定试验：是否发生紧急制动；感度试验为NULL',
    result VARCHAR(8) NOT NULL COMMENT '试验结论：PASS合格/FAIL不合格',
    voided BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否因编组变更作废：TRUE已作废',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求键',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（JVM 时区）'
);
COMMENT ON TABLE brake_test IS '制动试验结论表';

CREATE TABLE IF NOT EXISTS pressure_reading (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '读数主键',
    job_id BIGINT NOT NULL COMMENT '所属作业ID',
    pressure INT NOT NULL COMMENT '列车管风压，单位kPa',
    recorded_at TIMESTAMP NOT NULL COMMENT '采集时间（上报方时钟，JVM 时区解释）',
    late BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否销记后迟到数据：TRUE迟到仅记事件不参与曲线',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求键',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（JVM 时区）'
);
COMMENT ON TABLE pressure_reading IS '风压读数表（试验曲线复算依据）';

CREATE TABLE IF NOT EXISTS late_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '事件主键',
    job_id BIGINT NOT NULL COMMENT '所属作业ID',
    event_type VARCHAR(32) NOT NULL COMMENT '事件类型，如LATE_PRESSURE迟到风压',
    detail VARCHAR(512) NOT NULL COMMENT '事件说明',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（JVM 时区）'
);
COMMENT ON TABLE late_event IS '销记后迟到事件表';

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(64) PRIMARY KEY COMMENT '幂等请求键',
    operation VARCHAR(32) NOT NULL COMMENT '操作类型：ACCEPT/CONSIST_CHANGE/CONSIST_CHECK/BRAKE_TEST/DEPARTURE/CLOSURE/PRESSURE/EOT_DEVICE_REGISTER/EOT_BIND/EOT_CONFIRM/EOT_UNBIND',
    fingerprint VARCHAR(64) NOT NULL COMMENT '请求指纹SHA-256，覆盖requestId与全部业务字段（JSON结构化序列化，含分隔符文本亦无歧义）',
    response_body CLOB NOT NULL COMMENT '首次成功响应JSON快照，同键同参重放时原样返回',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（JVM 时区）'
);
COMMENT ON TABLE idempotency_record IS '幂等记录表';

CREATE TABLE IF NOT EXISTS eot_device (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '列尾装置主键',
    device_no VARCHAR(64) NOT NULL COMMENT '列尾装置编号（合成数据，唯一建档）',
    self_check_valid_until TIMESTAMP NOT NULL COMMENT '自检合格有效截止时刻（UTC无时区基准存储）；UTC下now<该时刻方可绑定/发车，恰到该时刻即失效',
    bound_job_id BIGINT DEFAULT NULL COMMENT '当前绑定作业ID；NULL表示空闲；销记或提前解绑在事务内原子置空',
    version INT NOT NULL DEFAULT 1 COMMENT '装置状态递增版本，自1起；绑定/共同确认/解绑/销记释放每次成功变更加1',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC存储）',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间（UTC存储）',
    CONSTRAINT uk_eot_device_no UNIQUE (device_no)
);
COMMENT ON TABLE eot_device IS '列尾装置档案表（自检有效期、当前占用与乐观版本）';

CREATE TABLE IF NOT EXISTS eot_binding (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '绑定主键',
    device_id BIGINT NOT NULL COMMENT '列尾装置ID',
    device_no VARCHAR(64) NOT NULL COMMENT '列尾装置编号（冗余留存证据）',
    job_id BIGINT NOT NULL COMMENT '受控作业ID',
    tail_car_no VARCHAR(32) NOT NULL COMMENT '绑定时冻结的当前编组尾车车辆号；摘挂后不更新，仅作冻结证据',
    consist_version INT NOT NULL COMMENT '绑定时冻结的编组版本；摘挂后不更新，仅作冻结证据',
    status VARCHAR(16) NOT NULL COMMENT '绑定状态：ACTIVE绑定中/RELEASED已解除（提前解绑或销记）',
    active_device_id BIGINT DEFAULT NULL COMMENT 'ACTIVE时等于装置ID，RELEASED置NULL；唯一约束保证一台装置同时仅一条生效绑定',
    active_job_id BIGINT DEFAULT NULL COMMENT 'ACTIVE时等于作业ID，RELEASED置NULL；唯一约束保证一个作业同时仅一条生效绑定',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求键',
    released_at TIMESTAMP DEFAULT NULL COMMENT '解除时刻（UTC存储）；NULL表示仍绑定中',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC存储）',
    CONSTRAINT uk_eot_binding_active_device UNIQUE (active_device_id),
    CONSTRAINT uk_eot_binding_active_job UNIQUE (active_job_id)
);
COMMENT ON TABLE eot_binding IS '列尾装置绑定历史表（解除后保留全部历史）';

CREATE TABLE IF NOT EXISTS eot_consistency_confirmation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '一致性确认主键',
    binding_id BIGINT NOT NULL COMMENT '所属绑定ID',
    device_id BIGINT NOT NULL COMMENT '列尾装置ID（冗余证据）',
    device_no VARCHAR(64) NOT NULL COMMENT '列尾装置编号（冗余证据）',
    job_id BIGINT NOT NULL COMMENT '受控作业ID',
    tail_car_no VARCHAR(32) NOT NULL COMMENT '两名人员共同确认时的尾车车辆号',
    consist_version INT NOT NULL COMMENT '两名人员共同确认时的编组版本',
    confirmer1 VARCHAR(64) NOT NULL COMMENT '共同确认人1（不得为空，不得与确认人2相同）',
    confirmer2 VARCHAR(64) NOT NULL COMMENT '共同确认人2（不得为空，不得与确认人1相同）',
    valid BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否仍有效：TRUE有效；摘挂改变尾车/版本、提前解绑、销记时同事务置FALSE',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求键',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC存储）'
);
COMMENT ON TABLE eot_consistency_confirmation IS '列尾与编组尾车一致性双人确认记录表';
