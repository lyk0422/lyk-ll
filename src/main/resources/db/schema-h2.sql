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

-- 本地力矩采集会话：绑定工具日校验版本（工具编号 + 绑定当日校验日期快照）、工序卡与紧固点清单。
-- 设备不联网，原始包仅经本地 HTTP 提交；version 为乐观版本（从 1 开始，每次成功变更加 1），
-- watermark 为已连续入账前缀长度（从 0 开始，只推进到连续完整前缀）。
CREATE TABLE IF NOT EXISTS collection_session (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_no         VARCHAR(64)  NOT NULL COMMENT '采集会话号（全库唯一）',
    card_id            BIGINT       NOT NULL COMMENT '绑定的工序卡',
    tool_code          VARCHAR(64)  NOT NULL COMMENT '绑定的力矩工具编号',
    calibration_date   DATE         NOT NULL COMMENT '绑定时工具日校验日期(UTC)快照；入账时另按测量时刻复核',
    status             VARCHAR(16)  NOT NULL COMMENT '会话状态：OPEN=采集中，CLOSED=已关闭（终态）',
    version            INT          NOT NULL COMMENT '乐观版本号：创建为 1，每次成功变更加 1',
    watermark          INT          NOT NULL COMMENT '连续入账水位线：1..watermark 均已入账，缺号不推进',
    expected_count     INT          NOT NULL COMMENT '计划采集包总数（序号 1..expected_count）',
    created_at         TIMESTAMP(6) NOT NULL COMMENT '会话创建时间(UTC)',
    closed_at          TIMESTAMP(6) NULL     COMMENT '会话关闭时间(UTC)，NULL=未关闭',
    CONSTRAINT uk_collection_session_no UNIQUE (session_no)
);
CREATE INDEX IF NOT EXISTS idx_collection_session_card ON collection_session (card_id);

-- 会话绑定的紧固点清单：仅清单内点号合法，未知点整批拒绝。
CREATE TABLE IF NOT EXISTS collection_session_point (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id  BIGINT      NOT NULL COMMENT '所属采集会话',
    point_code  VARCHAR(64) NOT NULL COMMENT '紧固点编号（须存在于绑定工序卡）',
    CONSTRAINT uk_collection_session_point UNIQUE (session_id, point_code)
);

-- 采集原始包证据（追加链）：同会话同序号在非迟到状态下唯一；ADMITTED=已入前缀（有力矩记录），
-- BUFFERED=水位线后第 1～20 号乱序缓冲（无力矩记录，不参与自检/互检/见证），
-- LATE=工序卡归档后迟到（只记迟到，不复活卡片；允许与已入账序号并存为迟到证据）。
CREATE TABLE IF NOT EXISTS collection_packet (
    id              BIGINT        AUTO_INCREMENT PRIMARY KEY,
    session_id      BIGINT        NOT NULL COMMENT '所属采集会话',
    seq             INT           NOT NULL COMMENT '会话内连续序号，从 1 开始',
    point_code      VARCHAR(64)   NOT NULL COMMENT '紧固点编号',
    measured_torque DECIMAL(12,3) NOT NULL COMMENT '实测力矩(N·m)，有限正数',
    measured_at     TIMESTAMP(6)  NOT NULL COMMENT 'UTC 测量时刻（设备产生，非提交时刻）',
    state           VARCHAR(16)   NOT NULL COMMENT '包状态：ADMITTED/BUFFERED/LATE',
    attempt_no      INT           NULL     COMMENT '入账后对应紧固点力矩测量序号；缓冲/迟到为 NULL',
    request_id      VARCHAR(64)   NOT NULL COMMENT '首次提交该包的幂等请求号',
    received_at     TIMESTAMP(6)  NOT NULL COMMENT '服务端接收时间(UTC)',
    admitted_at     TIMESTAMP(6)  NULL     COMMENT '前缀入账时间(UTC)，缓冲/迟到为 NULL',
    CONSTRAINT uk_collection_packet_seq UNIQUE (session_id, seq, state)
);
CREATE INDEX IF NOT EXISTS idx_collection_packet_session ON collection_packet (session_id);

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
