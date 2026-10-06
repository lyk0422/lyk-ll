-- 索道运行管制服务数据库结构（H2 MySQL 兼容模式）
-- 所有时间字段均为 TIMESTAMP（无时区，按应用时钟 Asia/Shanghai 解释）。

CREATE TABLE IF NOT EXISTS ski_line (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE ski_line IS '索道线路';
COMMENT ON COLUMN ski_line.id IS '线路主键';
COMMENT ON COLUMN ski_line.code IS '线路业务编码，唯一';
COMMENT ON COLUMN ski_line.name IS '线路名称';
COMMENT ON COLUMN ski_line.status IS '线路状态：NORMAL 正常 / SOFT_HOLD 缓停 / EMERGENCY_STOP 紧急停车';
COMMENT ON COLUMN ski_line.created_at IS '创建时间（应用时钟）';

CREATE UNIQUE INDEX IF NOT EXISTS uk_ski_line_code ON ski_line (code);

CREATE TABLE IF NOT EXISTS station (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    line_id BIGINT NOT NULL,
    seq INT NOT NULL,
    name VARCHAR(128) NOT NULL
);
COMMENT ON TABLE station IS '线路站点（合成数据）';
COMMENT ON COLUMN station.id IS '站点主键';
COMMENT ON COLUMN station.line_id IS '所属线路 id';
COMMENT ON COLUMN station.seq IS '站点顺序号，从 1 开始递增';
COMMENT ON COLUMN station.name IS '站点名称';

CREATE UNIQUE INDEX IF NOT EXISTS uk_station_line_seq ON station (line_id, seq);

CREATE TABLE IF NOT EXISTS segment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    line_id BIGINT NOT NULL,
    seq INT NOT NULL,
    from_station_seq INT NOT NULL,
    to_station_seq INT NOT NULL,
    name VARCHAR(128) NOT NULL
);
COMMENT ON TABLE segment IS '线路分段（相邻站点之间），风速按分段监测';
COMMENT ON COLUMN segment.id IS '分段主键';
COMMENT ON COLUMN segment.line_id IS '所属线路 id';
COMMENT ON COLUMN segment.seq IS '分段顺序号，从 1 开始递增，与起始站点序号一致';
COMMENT ON COLUMN segment.from_station_seq IS '起始站点序号';
COMMENT ON COLUMN segment.to_station_seq IS '结束站点序号';
COMMENT ON COLUMN segment.name IS '分段名称';

CREATE UNIQUE INDEX IF NOT EXISTS uk_segment_line_seq ON segment (line_id, seq);

CREATE TABLE IF NOT EXISTS wind_reading (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    line_id BIGINT NOT NULL,
    segment_id BIGINT NOT NULL,
    measured_at TIMESTAMP NOT NULL,
    minute_bucket TIMESTAMP NOT NULL,
    speed DECIMAL(6,2) NOT NULL,
    received_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE wind_reading IS '分段风速实测数据，按分段（站点）与时刻去重';
COMMENT ON COLUMN wind_reading.id IS '风速记录主键';
COMMENT ON COLUMN wind_reading.line_id IS '所属线路 id';
COMMENT ON COLUMN wind_reading.segment_id IS '所属分段 id';
COMMENT ON COLUMN wind_reading.measured_at IS '测量时刻（去重键之一）';
COMMENT ON COLUMN wind_reading.minute_bucket IS '测量时刻所属分钟桶（截断到分钟），用于 10/15 分钟窗口统计';
COMMENT ON COLUMN wind_reading.speed IS '风速，单位 m/s';
COMMENT ON COLUMN wind_reading.received_at IS '服务端接收时间（应用时钟）';

CREATE UNIQUE INDEX IF NOT EXISTS uk_wind_reading_seg_ts ON wind_reading (segment_id, measured_at);
CREATE INDEX IF NOT EXISTS idx_wind_reading_seg_bucket ON wind_reading (segment_id, minute_bucket);

CREATE TABLE IF NOT EXISTS control_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    line_id BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    trigger_segment_id BIGINT NOT NULL,
    trigger_avg_speed DECIMAL(6,2) NOT NULL,
    window_start TIMESTAMP NOT NULL,
    window_end TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    closed_at TIMESTAMP NULL
);
COMMENT ON TABLE control_event IS '线路管制事件；同一线路同一时刻仅允许一个进行中事件，归档后为终态';
COMMENT ON COLUMN control_event.id IS '管制事件主键';
COMMENT ON COLUMN control_event.line_id IS '所属线路 id';
COMMENT ON COLUMN control_event.event_type IS '事件类型：SOFT_HOLD 缓停 / EMERGENCY_STOP 紧急停车';
COMMENT ON COLUMN control_event.status IS '事件状态：ACTIVE 进行中 / REOPEN_REQUESTED 已申请复开 / CLOSED 已归档（终态）';
COMMENT ON COLUMN control_event.trigger_segment_id IS '触发分段 id';
COMMENT ON COLUMN control_event.trigger_avg_speed IS '触发时 10 分钟平均风速，单位 m/s';
COMMENT ON COLUMN control_event.window_start IS '判定窗口起始分钟（含）';
COMMENT ON COLUMN control_event.window_end IS '判定窗口结束分钟（含）';
COMMENT ON COLUMN control_event.created_at IS '事件创建时间（应用时钟）';
COMMENT ON COLUMN control_event.closed_at IS '归档时间（应用时钟），未归档为 NULL';

CREATE INDEX IF NOT EXISTS idx_control_event_line_status ON control_event (line_id, status);

CREATE TABLE IF NOT EXISTS segment_release (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id BIGINT NOT NULL,
    segment_id BIGINT NOT NULL,
    segment_seq INT NOT NULL,
    released_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE segment_release IS '缓停期间在途吊厢按站点逐段放行记录';
COMMENT ON COLUMN segment_release.id IS '放行记录主键';
COMMENT ON COLUMN segment_release.event_id IS '所属管制事件 id';
COMMENT ON COLUMN segment_release.segment_id IS '放行分段 id';
COMMENT ON COLUMN segment_release.segment_seq IS '放行分段顺序号，须按序放行';
COMMENT ON COLUMN segment_release.released_at IS '放行时间（应用时钟）';

CREATE UNIQUE INDEX IF NOT EXISTS uk_segment_release_event_seg ON segment_release (event_id, segment_id);

CREATE TABLE IF NOT EXISTS rescue_confirmation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id BIGINT NOT NULL,
    segment_id BIGINT NOT NULL,
    operator VARCHAR(64) NOT NULL,
    confirmed_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE rescue_confirmation IS '紧急停车救援双人确认记录；同一分段须两名不同人员确认方生效';
COMMENT ON COLUMN rescue_confirmation.id IS '确认记录主键';
COMMENT ON COLUMN rescue_confirmation.event_id IS '所属管制事件 id';
COMMENT ON COLUMN rescue_confirmation.segment_id IS '确认清空的分段 id';
COMMENT ON COLUMN rescue_confirmation.operator IS '确认人员标识';
COMMENT ON COLUMN rescue_confirmation.confirmed_at IS '确认时间（应用时钟）';

CREATE UNIQUE INDEX IF NOT EXISTS uk_rescue_conf ON rescue_confirmation (event_id, segment_id, operator);

CREATE TABLE IF NOT EXISTS reopen_request (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    requested_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE reopen_request IS '复开申请；每个管制事件仅允许一条申请';
COMMENT ON COLUMN reopen_request.id IS '复开申请主键';
COMMENT ON COLUMN reopen_request.event_id IS '所属管制事件 id';
COMMENT ON COLUMN reopen_request.status IS '申请状态：PENDING 待双岗确认 / APPROVED 已复开';
COMMENT ON COLUMN reopen_request.requested_at IS '申请时间（应用时钟）';

CREATE UNIQUE INDEX IF NOT EXISTS uk_reopen_request_event ON reopen_request (event_id);

CREATE TABLE IF NOT EXISTS reopen_confirmation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    reopen_request_id BIGINT NOT NULL,
    role VARCHAR(32) NOT NULL,
    confirmed_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE reopen_confirmation IS '复开双岗确认记录；运营与机务各确认一次方可复开';
COMMENT ON COLUMN reopen_confirmation.id IS '确认记录主键';
COMMENT ON COLUMN reopen_confirmation.reopen_request_id IS '所属复开申请 id';
COMMENT ON COLUMN reopen_confirmation.role IS '确认岗位：OPERATIONS 运营 / MAINTENANCE 机务';
COMMENT ON COLUMN reopen_confirmation.confirmed_at IS '确认时间（应用时钟）';

CREATE UNIQUE INDEX IF NOT EXISTS uk_reopen_conf_role ON reopen_confirmation (reopen_request_id, role);

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(128) PRIMARY KEY,
    endpoint VARCHAR(128) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    response_status INT NOT NULL,
    response_body CLOB NOT NULL,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE idempotency_record IS '写操作幂等记录；指纹覆盖 requestId 与全部业务字段';
COMMENT ON COLUMN idempotency_record.request_id IS '幂等键（客户端请求编号）';
COMMENT ON COLUMN idempotency_record.endpoint IS '接口标识';
COMMENT ON COLUMN idempotency_record.fingerprint IS '请求指纹：requestId 与全部业务字段的 SHA-256';
COMMENT ON COLUMN idempotency_record.response_status IS '首次执行的 HTTP 状态码';
COMMENT ON COLUMN idempotency_record.response_body IS '首次执行的响应报文快照';
COMMENT ON COLUMN idempotency_record.created_at IS '记录创建时间（应用时钟）';

CREATE TABLE IF NOT EXISTS late_wind_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    line_id BIGINT NOT NULL,
    segment_id BIGINT NOT NULL,
    measured_at TIMESTAMP NOT NULL,
    speed DECIMAL(6,2) NOT NULL,
    received_at TIMESTAMP NOT NULL,
    reason VARCHAR(256) NOT NULL
);
COMMENT ON TABLE late_wind_event IS '迟到风速数据事件；管制事件归档后到达的历史数据只记录不参与判定';
COMMENT ON COLUMN late_wind_event.id IS '迟到事件主键';
COMMENT ON COLUMN late_wind_event.line_id IS '所属线路 id';
COMMENT ON COLUMN late_wind_event.segment_id IS '所属分段 id';
COMMENT ON COLUMN late_wind_event.measured_at IS '测量时刻';
COMMENT ON COLUMN late_wind_event.speed IS '风速，单位 m/s';
COMMENT ON COLUMN late_wind_event.received_at IS '服务端接收时间（应用时钟）';
COMMENT ON COLUMN late_wind_event.reason IS '迟到原因说明';
