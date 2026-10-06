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
