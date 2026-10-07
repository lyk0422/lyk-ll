-- 索道运行管制服务建表脚本（H2 MODE=MySQL 兼容语法，时间均为 UTC，风速单位 m/s）

CREATE TABLE IF NOT EXISTS wind_line (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE wind_line IS '索道线路';
COMMENT ON COLUMN wind_line.code IS '线路编码，唯一';
COMMENT ON COLUMN wind_line.name IS '线路名称';
COMMENT ON COLUMN wind_line.status IS '线路状态：NORMAL 正常 / SLOW_STOP 缓停 / EMERGENCY_STOP 紧急停车';
COMMENT ON COLUMN wind_line.created_at IS '创建时间（UTC）';

CREATE UNIQUE INDEX IF NOT EXISTS uk_wind_line_code ON wind_line (code);

CREATE TABLE IF NOT EXISTS wind_station (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    line_id BIGINT NOT NULL,
    seq_no INT NOT NULL,
    name VARCHAR(128) NOT NULL
);
COMMENT ON TABLE wind_station IS '线路站点（合成数据）';
COMMENT ON COLUMN wind_station.line_id IS '所属线路 id';
COMMENT ON COLUMN wind_station.seq_no IS '站点顺序号，从 1 开始';
COMMENT ON COLUMN wind_station.name IS '站点名称';

CREATE UNIQUE INDEX IF NOT EXISTS uk_wind_station_line_seq ON wind_station (line_id, seq_no);

CREATE TABLE IF NOT EXISTS wind_segment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    line_id BIGINT NOT NULL,
    seq_no INT NOT NULL,
    from_station_id BIGINT NOT NULL,
    to_station_id BIGINT NOT NULL
);
COMMENT ON TABLE wind_segment IS '线路分段：相邻两站点之间的监测段';
COMMENT ON COLUMN wind_segment.line_id IS '所属线路 id';
COMMENT ON COLUMN wind_segment.seq_no IS '分段顺序号，从 1 开始';
COMMENT ON COLUMN wind_segment.from_station_id IS '起点站 id';
COMMENT ON COLUMN wind_segment.to_station_id IS '终点站 id';

CREATE UNIQUE INDEX IF NOT EXISTS uk_wind_segment_line_seq ON wind_segment (line_id, seq_no);

CREATE TABLE IF NOT EXISTS wind_reading (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    line_id BIGINT NOT NULL,
    station_id BIGINT NOT NULL,
    measured_at TIMESTAMP(6) NOT NULL,
    speed_ms DOUBLE NOT NULL,
    late BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE wind_reading IS '风速监测数据，按站点与时刻去重';
COMMENT ON COLUMN wind_reading.line_id IS '所属线路 id';
COMMENT ON COLUMN wind_reading.station_id IS '采集站点 id';
COMMENT ON COLUMN wind_reading.measured_at IS '测量时刻（UTC），与站点联合唯一';
COMMENT ON COLUMN wind_reading.speed_ms IS '风速，单位 m/s';
COMMENT ON COLUMN wind_reading.late IS '是否迟到数据（管制事件归档后到达）：TRUE 只记迟到事件不参与判定';
COMMENT ON COLUMN wind_reading.created_at IS '入库时间（UTC）';

CREATE UNIQUE INDEX IF NOT EXISTS uk_wind_reading_station_time ON wind_reading (station_id, measured_at);
CREATE INDEX IF NOT EXISTS idx_wind_reading_station ON wind_reading (station_id, measured_at);

CREATE TABLE IF NOT EXISTS control_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    line_id BIGINT NOT NULL,
    type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    trigger_segment_id BIGINT,
    avg_speed DOUBLE,
    triggered_at TIMESTAMP(6) NOT NULL,
    archived_at TIMESTAMP(6),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE control_event IS '管制事件，同线路同时刻只允许一个进行中事件，归档为终态';
COMMENT ON COLUMN control_event.line_id IS '所属线路 id';
COMMENT ON COLUMN control_event.type IS '事件类型：SLOW_STOP 缓停 / EMERGENCY_STOP 紧急停车';
COMMENT ON COLUMN control_event.status IS '事件状态：ACTIVE 进行中 / REOPEN_REQUESTED 已申请复开 / RESUMED 已复开（归档终态）';
COMMENT ON COLUMN control_event.trigger_segment_id IS '触发分段 id';
COMMENT ON COLUMN control_event.avg_speed IS '触发时 10 分钟平均风速，单位 m/s';
COMMENT ON COLUMN control_event.triggered_at IS '触发判定对应的测量时刻（UTC）';
COMMENT ON COLUMN control_event.archived_at IS '归档时间（UTC），NULL 表示未归档';

CREATE INDEX IF NOT EXISTS idx_control_event_line ON control_event (line_id, status);

CREATE TABLE IF NOT EXISTS cabin_release (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id BIGINT NOT NULL,
    segment_id BIGINT NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE cabin_release IS '缓停期间在途吊厢按站点逐段放行记录';
COMMENT ON COLUMN cabin_release.event_id IS '管制事件 id';
COMMENT ON COLUMN cabin_release.segment_id IS '放行分段 id，同一事件内唯一';
COMMENT ON COLUMN cabin_release.operator_id IS '操作人标识';
COMMENT ON COLUMN cabin_release.created_at IS '放行时间（UTC）';

CREATE UNIQUE INDEX IF NOT EXISTS uk_cabin_release_event_segment ON cabin_release (event_id, segment_id);

CREATE TABLE IF NOT EXISTS segment_clearance (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id BIGINT NOT NULL,
    segment_id BIGINT NOT NULL,
    confirmer_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE segment_clearance IS '紧急停车救援分段清空确认，须双人确认方可生效';
COMMENT ON COLUMN segment_clearance.event_id IS '管制事件 id';
COMMENT ON COLUMN segment_clearance.segment_id IS '分段 id';
COMMENT ON COLUMN segment_clearance.confirmer_id IS '确认人标识，同一分段同一确认人仅记一次';
COMMENT ON COLUMN segment_clearance.created_at IS '确认时间（UTC）';

CREATE UNIQUE INDEX IF NOT EXISTS uk_segment_clearance ON segment_clearance (event_id, segment_id, confirmer_id);

CREATE TABLE IF NOT EXISTS reopen_confirmation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id BIGINT NOT NULL,
    role VARCHAR(32) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE reopen_confirmation IS '复开双岗确认记录，运营与机务各确认一次方可复开';
COMMENT ON COLUMN reopen_confirmation.event_id IS '管制事件 id';
COMMENT ON COLUMN reopen_confirmation.role IS '确认岗位：OPERATIONS 运营 / MAINTENANCE 机务，同一事件每岗位仅一次';
COMMENT ON COLUMN reopen_confirmation.operator_id IS '确认人标识';
COMMENT ON COLUMN reopen_confirmation.created_at IS '确认时间（UTC）';

CREATE UNIQUE INDEX IF NOT EXISTS uk_reopen_confirmation_event_role ON reopen_confirmation (event_id, role);

CREATE TABLE IF NOT EXISTS boarding (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    line_id BIGINT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    passenger_count INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE boarding IS '上客记录，管制期间禁止上客';
COMMENT ON COLUMN boarding.line_id IS '线路 id';
COMMENT ON COLUMN boarding.request_id IS '幂等请求标识';
COMMENT ON COLUMN boarding.passenger_count IS '上客人数';
COMMENT ON COLUMN boarding.created_at IS '记录时间（UTC）';

CREATE UNIQUE INDEX IF NOT EXISTS uk_boarding_request ON boarding (request_id);

CREATE TABLE IF NOT EXISTS late_data_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    line_id BIGINT NOT NULL,
    station_id BIGINT NOT NULL,
    measured_at TIMESTAMP(6) NOT NULL,
    speed_ms DOUBLE NOT NULL,
    reason VARCHAR(256) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE late_data_event IS '迟到风速数据事件，管制事件归档后到达的数据只记录不判定';
COMMENT ON COLUMN late_data_event.line_id IS '线路 id';
COMMENT ON COLUMN late_data_event.station_id IS '站点 id';
COMMENT ON COLUMN late_data_event.measured_at IS '测量时刻（UTC）';
COMMENT ON COLUMN late_data_event.speed_ms IS '风速，单位 m/s';
COMMENT ON COLUMN late_data_event.reason IS '迟到原因说明';
COMMENT ON COLUMN late_data_event.created_at IS '记录时间（UTC）';

CREATE TABLE IF NOT EXISTS maintenance_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    line_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'SCHEDULED',
    version INT NOT NULL DEFAULT 1,
    start_at TIMESTAMP(6) NOT NULL,
    end_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    cancelled_at TIMESTAMP(6),
    completed_at TIMESTAMP(6)
);
COMMENT ON TABLE maintenance_order IS '检修封锁窗口：生效期间（到达 start 起至验收完成）阻断整条线路上客与复开';
COMMENT ON COLUMN maintenance_order.line_id IS '所属线路 id';
COMMENT ON COLUMN maintenance_order.status IS '工单状态：SCHEDULED 已登记 / CANCELLED 已取消 / COMPLETED 已完成（验收通过）';
COMMENT ON COLUMN maintenance_order.version IS '乐观版本号，从 1 起，每次成功修改加 1';
COMMENT ON COLUMN maintenance_order.start_at IS '封锁窗口起点（UTC，含），到达即生效阻断';
COMMENT ON COLUMN maintenance_order.end_at IS '封锁窗口终点（UTC，不含）；验收不得早于该时刻，到达不自动放行';
COMMENT ON COLUMN maintenance_order.created_at IS '登记时间（UTC）';
COMMENT ON COLUMN maintenance_order.cancelled_at IS '取消时间（UTC），NULL 表示未取消；仅未生效单可取消';
COMMENT ON COLUMN maintenance_order.completed_at IS '完成时间（UTC），NULL 表示未完成；双岗验收齐全时写入';

CREATE INDEX IF NOT EXISTS idx_maintenance_order_line ON maintenance_order (line_id, status);

CREATE TABLE IF NOT EXISTS maintenance_order_segment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    segment_id BIGINT NOT NULL
);
COMMENT ON TABLE maintenance_order_segment IS '检修单涉及分段清单，不能为空且必须同属工单线路';
COMMENT ON COLUMN maintenance_order_segment.order_id IS '检修单 id';
COMMENT ON COLUMN maintenance_order_segment.segment_id IS '涉及分段 id，同一工单内唯一';

CREATE UNIQUE INDEX IF NOT EXISTS uk_mos_order_segment ON maintenance_order_segment (order_id, segment_id);

CREATE TABLE IF NOT EXISTS maintenance_segment_clearance (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    segment_id BIGINT NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    note VARCHAR(256),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE maintenance_segment_clearance IS '检修分段清空证据，生效后逐段登记，每段仅一条';
COMMENT ON COLUMN maintenance_segment_clearance.order_id IS '检修单 id';
COMMENT ON COLUMN maintenance_segment_clearance.segment_id IS '已清空分段 id，同一工单内唯一';
COMMENT ON COLUMN maintenance_segment_clearance.operator_id IS '清空操作人标识（合成数据）';
COMMENT ON COLUMN maintenance_segment_clearance.note IS '清空说明，可为 NULL';
COMMENT ON COLUMN maintenance_segment_clearance.created_at IS '清空登记时间（UTC）';

CREATE UNIQUE INDEX IF NOT EXISTS uk_msc_order_segment ON maintenance_segment_clearance (order_id, segment_id);

CREATE TABLE IF NOT EXISTS maintenance_acceptance (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    role VARCHAR(32) NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    opinion VARCHAR(256),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE maintenance_acceptance IS '检修验收记录：机务与检修两岗各一次，须两个不同人员';
COMMENT ON COLUMN maintenance_acceptance.order_id IS '检修单 id';
COMMENT ON COLUMN maintenance_acceptance.role IS '验收岗位：MECHANICAL 机务 / INSPECTION 检修，同一工单每岗位仅一次';
COMMENT ON COLUMN maintenance_acceptance.operator_id IS '验收人标识（合成数据），两岗验收人不得相同';
COMMENT ON COLUMN maintenance_acceptance.opinion IS '验收意见，可为 NULL';
COMMENT ON COLUMN maintenance_acceptance.created_at IS '验收时间（UTC），不得早于窗口终点';

CREATE UNIQUE INDEX IF NOT EXISTS uk_ma_order_role ON maintenance_acceptance (order_id, role);

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(64) PRIMARY KEY,
    endpoint VARCHAR(128) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    response_status INT,
    response_body CLOB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP
);
COMMENT ON TABLE idempotency_record IS '写操作幂等记录，指纹覆盖 requestId 与全部业务字段';
COMMENT ON COLUMN idempotency_record.request_id IS '幂等请求标识，主键';
COMMENT ON COLUMN idempotency_record.endpoint IS '接口标识';
COMMENT ON COLUMN idempotency_record.fingerprint IS '请求指纹：requestId 与全部业务字段的 SHA-256';
COMMENT ON COLUMN idempotency_record.response_status IS '首次响应 HTTP 状态码，NULL 表示处理中';
COMMENT ON COLUMN idempotency_record.response_body IS '首次响应报文，同键同参原样返回';
COMMENT ON COLUMN idempotency_record.created_at IS '记录创建时间（UTC）';
COMMENT ON COLUMN idempotency_record.completed_at IS '记录完成时间（UTC），NULL 表示处理中';
