-- 航空装配力矩紧固与见证点控制服务：H2（MODE=MySQL）建表脚本
-- 所有时间戳除特别说明外均为 UTC；力矩单位 N·m。

-- 工序卡主表：状态机 DISPATCHED(派工) -> TIGHTENED(力矩紧固完成)
-- -> INSPECTED(自检互检完成) -> WITNESSED(H 点见证完成) -> ARCHIVED(放行归档, 终态)；
-- QUARANTINED 表示整批隔离评审（终态前的冻结状态，工位仍被占用）。
CREATE TABLE IF NOT EXISTS operation_card (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    card_no         VARCHAR(64)  NOT NULL COMMENT '工序卡编号',
    station         VARCHAR(64)  NOT NULL COMMENT '工位编号',
    product_sn      VARCHAR(64)  NOT NULL COMMENT '产品序列号（合成数据）',
    witness_point   CHAR(1)      NOT NULL COMMENT '见证点类型：H=H点强制见证，N=普通点',
    status          VARCHAR(32)  NOT NULL COMMENT '工序状态，见 CardStatus',
    operator        VARCHAR(64)  NOT NULL COMMENT '派工操作者',
    dispatched_at   TIMESTAMP(6) NOT NULL COMMENT '派工时间(UTC)',
    quarantined_at  TIMESTAMP(6) NULL     COMMENT '整批隔离评审时间(UTC)，NULL=未隔离',
    archived_at     TIMESTAMP(6) NULL     COMMENT '放行归档时间(UTC)，NULL=未归档',
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_operation_card_no UNIQUE (card_no)
);

-- 工位执行守卫：同一工位同时刻只允许一张执行中工序卡，由主键唯一约束保证并发唯一受理。
-- 派工时插入，放行归档后删除；隔离评审中的工序卡仍占用工位。
CREATE TABLE IF NOT EXISTS station_guard (
    station    VARCHAR(64) NOT NULL COMMENT '工位编号（主键，全库唯一执行中卡）',
    card_no    VARCHAR(64) NOT NULL COMMENT '当前占用工位的工序卡编号',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_station_guard PRIMARY KEY (station)
);

-- 紧固件（紧固点）工艺定义，力矩标称值与允差（默认 ±5%，边界合格）。
CREATE TABLE IF NOT EXISTS fastener (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    card_id         BIGINT       NOT NULL COMMENT '所属工序卡',
    point_code      VARCHAR(64)  NOT NULL COMMENT '紧固点编号',
    nominal_torque  DECIMAL(12,3) NOT NULL COMMENT '工艺卡标称力矩(N·m)',
    tolerance_pct   DECIMAL(5,2) NOT NULL DEFAULT 5.00 COMMENT '允差百分比，5.00 表示 ±5%',
    CONSTRAINT uk_fastener_point UNIQUE (card_id, point_code)
);

-- 力矩工具台账：日校验，calibration_date 等于使用当日(UTC 日界)才有效。
CREATE TABLE IF NOT EXISTS torque_tool (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    tool_code         VARCHAR(64) NOT NULL COMMENT '力矩工具编号',
    calibration_date  DATE        NOT NULL COMMENT '日校验日期(UTC)，仅当日有效',
    created_at        TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_torque_tool_code UNIQUE (tool_code)
);

-- 力矩紧固记录（追加链，永不覆盖）：每次测量一条，attempt_no 在该紧固点内递增。
CREATE TABLE IF NOT EXISTS torque_record (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    card_id         BIGINT        NOT NULL COMMENT '所属工序卡',
    fastener_id     BIGINT        NOT NULL COMMENT '紧固点',
    attempt_no      INT           NOT NULL COMMENT '紧固点内测量序号：1=首次，2/3=返工后测量',
    measured_torque DECIMAL(12,3) NOT NULL COMMENT '实测力矩(N·m)',
    deviation_pct   DECIMAL(8,4)  NOT NULL COMMENT '相对标称偏差百分比，正=超扭负=欠扭',
    in_tolerance    TINYINT       NOT NULL COMMENT '1=在 ±允差内(含边界)，0=超差',
    operator        VARCHAR(64)   NOT NULL COMMENT '紧固操作者',
    tool_code       VARCHAR(64)   NOT NULL COMMENT '使用的力矩工具编号',
    measured_at     TIMESTAMP(6)  NOT NULL COMMENT '测量时间(UTC)',
    request_id      VARCHAR(64)   NOT NULL COMMENT '幂等请求号',
    created_at      TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_torque_attempt UNIQUE (card_id, fastener_id, attempt_no)
);
CREATE INDEX IF NOT EXISTS idx_torque_card ON torque_record (card_id);

-- 返工台账：超差即返工并记录；同一紧固点最多 2 条返工，与力矩记录同一事务追加。
CREATE TABLE IF NOT EXISTS rework_record (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    card_id      BIGINT       NOT NULL COMMENT '所属工序卡',
    fastener_id  BIGINT       NOT NULL COMMENT '返工紧固点',
    rework_no    INT          NOT NULL COMMENT '该紧固点第几次返工：合法值 1、2',
    request_id   VARCHAR(64)  NOT NULL COMMENT '触发返工的力矩请求号',
    reason       VARCHAR(255) NOT NULL COMMENT '返工原因（超差描述）',
    created_at   TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_rework_no UNIQUE (card_id, fastener_id, rework_no)
);

-- 自检互检记录：自检人与互检人必须不同。
CREATE TABLE IF NOT EXISTS inspection_record (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    card_id         BIGINT       NOT NULL COMMENT '所属工序卡',
    self_inspector  VARCHAR(64)  NOT NULL COMMENT '自检人员',
    mutual_inspector VARCHAR(64) NOT NULL COMMENT '互检人员，须与自检人不同',
    request_id      VARCHAR(64)  NOT NULL COMMENT '幂等请求号',
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_inspection_card UNIQUE (card_id)
);

-- 见证链：按 seq 追加，状态 ABSENT(缺席)/WAITING(等待)/PRESENT(见证到场)/ESCALATED(等待升级)。
CREATE TABLE IF NOT EXISTS witness_record (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    card_id      BIGINT       NOT NULL COMMENT '所属工序卡',
    seq          INT          NOT NULL COMMENT '见证链序号，从 1 递增',
    status       VARCHAR(16)  NOT NULL COMMENT '见证状态：ABSENT/WAITING/PRESENT/ESCALATED',
    witness_name VARCHAR(64)  NULL     COMMENT '见证人；缺席/等待时为 NULL',
    event_time   TIMESTAMP(6) NOT NULL COMMENT '见证事件时间(UTC)',
    request_id   VARCHAR(64)  NOT NULL COMMENT '幂等请求号',
    note         VARCHAR(255) NULL     COMMENT '备注（升级原因等）',
    created_at   TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_witness_seq UNIQUE (card_id, seq)
);
CREATE INDEX IF NOT EXISTS idx_witness_card ON witness_record (card_id);

-- 放行归档记录：每卡至多一条，归档为终态。
CREATE TABLE IF NOT EXISTS release_record (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    card_id     BIGINT       NOT NULL COMMENT '所属工序卡',
    released_by VARCHAR(64)  NOT NULL COMMENT '放行人',
    request_id  VARCHAR(64)  NOT NULL COMMENT '幂等请求号',
    archived_at TIMESTAMP(6) NOT NULL COMMENT '归档时间(UTC)',
    created_at  TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_release_card UNIQUE (card_id)
);

-- 归档后迟到测量：只记迟到事件，不改变工序状态，不进入力矩 attempt 链。
CREATE TABLE IF NOT EXISTS late_measurement_event (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    card_id         BIGINT        NOT NULL COMMENT '所属（已归档）工序卡',
    point_code      VARCHAR(64)   NOT NULL COMMENT '迟到测量紧固点编号',
    measured_torque DECIMAL(12,3) NOT NULL COMMENT '迟到实测力矩(N·m)',
    operator        VARCHAR(64)   NOT NULL COMMENT '操作者',
    tool_code       VARCHAR(64)   NOT NULL COMMENT '工具编号',
    measured_at     TIMESTAMP(6)  NOT NULL COMMENT '迟到测量时间(UTC)',
    request_id      VARCHAR(64)  NOT NULL COMMENT '幂等请求号',
    created_at      TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
CREATE INDEX IF NOT EXISTS idx_late_card ON late_measurement_event (card_id);

-- 工具采集会话：绑定工序卡、力矩工具及其日校验版本（创建时快照），并快照紧固点清单。
-- watermark 为已连续入账的最大包序号（0=未入账），只推进到连续完整前缀；
-- version 为乐观锁版本，从 1 开始，每次成功变更（入账/迟到登记/关闭）加 1。
CREATE TABLE IF NOT EXISTS collection_session (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_no       VARCHAR(64) NOT NULL COMMENT '采集会话号（全库唯一）',
    card_no          VARCHAR(64) NOT NULL COMMENT '绑定的工序卡编号',
    tool_code        VARCHAR(64) NOT NULL COMMENT '绑定的力矩工具编号',
    calibration_date DATE        NOT NULL COMMENT '绑定的工具日校验版本(UTC日期)，入账时按测量时刻校验',
    status           VARCHAR(16) NOT NULL COMMENT '会话状态：OPEN=采集中，CLOSED=已关闭(终态)',
    watermark        INT         NOT NULL DEFAULT 0 COMMENT '水位线：已连续入账的最大包序号，0=尚未入账',
    version          INT         NOT NULL DEFAULT 1 COMMENT '乐观锁版本，从1开始，成功变更加1',
    created_at       TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    closed_at        TIMESTAMP(6) NULL     COMMENT '关闭时间(UTC)，NULL=未关闭',
    CONSTRAINT uk_collection_session_no UNIQUE (session_no)
);
CREATE INDEX IF NOT EXISTS idx_collection_session_card ON collection_session (card_no);

-- 会话绑定的紧固点清单快照：创建会话时从工序卡工艺复制，上传按此校验未知点。
CREATE TABLE IF NOT EXISTS collection_session_point (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT      NOT NULL COMMENT '所属采集会话',
    point_code VARCHAR(64) NOT NULL COMMENT '绑定的紧固点编号（会话创建时快照）',
    CONSTRAINT uk_session_point UNIQUE (session_id, point_code)
);

-- 原始采集包（append-only 证据）：同会话同序号唯一；同内容重放不追加，异内容 409。
-- 状态：BUFFERED=乱序缓冲（不参与自检/互检/见证放行），CONSUMED=已按前缀入账，LATE=归档后迟到（不复活卡片）。
CREATE TABLE IF NOT EXISTS collection_packet (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id      BIGINT        NOT NULL COMMENT '所属采集会话',
    seq             INT           NOT NULL COMMENT '会话内包序号，从1开始连续',
    point_code      VARCHAR(64)   NOT NULL COMMENT '紧固点编号',
    measured_torque DECIMAL(12,3) NOT NULL COMMENT '实测力矩(N·m)，有限正数',
    measured_at     TIMESTAMP(6)  NOT NULL COMMENT '测量时刻(UTC)',
    status          VARCHAR(16)   NOT NULL COMMENT '包状态：BUFFERED/CONSUMED/LATE',
    content_hash    CHAR(64)      NOT NULL COMMENT '包内容SHA-256指纹：判同内容重放与异内容冲突',
    request_id      VARCHAR(64)   NOT NULL COMMENT '上传批次幂等请求号',
    created_at      TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_collection_packet_seq UNIQUE (session_id, seq)
);
CREATE INDEX IF NOT EXISTS idx_collection_packet_session ON collection_packet (session_id, status);

-- 采集上传批次台账：每次接受的上传一批，便于批量采集查询。
CREATE TABLE IF NOT EXISTS collection_batch (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id   BIGINT      NOT NULL COMMENT '所属采集会话',
    request_id   VARCHAR(64) NOT NULL COMMENT '上传批次幂等请求号',
    packet_count INT         NOT NULL COMMENT '本批实际追加的包数（不含同内容重放跳过）',
    created_at   TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_collection_batch_req UNIQUE (session_id, request_id)
);

-- 幂等请求台账：指纹覆盖 requestId 与全部业务字段，同键异参冲突 (409)。
CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id    VARCHAR(64)  NOT NULL COMMENT '幂等请求号（主键）',
    operation     VARCHAR(48)  NOT NULL COMMENT '操作类型',
    fingerprint   CHAR(64)     NOT NULL COMMENT '业务字段 SHA-256 指纹',
    status_code   INT          NOT NULL COMMENT '首次响应 HTTP 状态码',
    response_type VARCHAR(255) NOT NULL COMMENT '首次响应体 Java 类型名，重放时按此还原',
    response_json CLOB         NOT NULL COMMENT '首次响应体 JSON，重放原样返回',
    created_at    TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_idempotent_request PRIMARY KEY (request_id)
);
