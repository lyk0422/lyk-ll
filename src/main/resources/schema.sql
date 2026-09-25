-- 航空装配力矩紧固与见证点控制服务：H2(MODE=MySQL) 建表脚本
-- 全部时间字段以 UTC 存储，单位在列注释中标明。

-- 工序卡（派工单元）
CREATE TABLE IF NOT EXISTS work_card (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    card_no         VARCHAR(64)  NOT NULL COMMENT '工序卡编号（合成数据）',
    station         VARCHAR(64)  NOT NULL COMMENT '工位编号；同一工位同时刻仅允许一张执行中工序卡',
    product_no      VARCHAR(64)  NOT NULL COMMENT '产品/批次号',
    status          VARCHAR(32)  NOT NULL COMMENT '状态：DISPATCHED 已派工/TIGHTENING 紧固中/SELF_CHECK 自检/MUTUAL_CHECK 互检/WITNESS 见证待办/WAITING 见证缺席等待/COVERING 可进入覆盖工序/RELEASED 已放行归档/QUARANTINED 整批隔离评审',
    dispatched_at   TIMESTAMP    NOT NULL COMMENT '派工时间(UTC)',
    released_at     TIMESTAMP    NULL     COMMENT '放行归档时间(UTC)，未归档为空',
    quarantined_at  TIMESTAMP    NULL     COMMENT '隔离评审判定时间(UTC)，未隔离为空',
    current_rework  INT          NOT NULL DEFAULT 0 COMMENT '当前累计返工次数，0 表示首次；同一紧固点最多 2 次',
    UNIQUE (card_no)
);

-- 工位执行中唯一约束的辅助表：同一工位同时刻只允许一张执行中工序卡
CREATE TABLE IF NOT EXISTS station_active_card (
    station     VARCHAR(64) NOT NULL COMMENT '工位编号',
    card_id     BIGINT      NOT NULL COMMENT '执行中工序卡主键',
    occupied_at TIMESTAMP   NOT NULL COMMENT '占用时间(UTC)',
    PRIMARY KEY (station)
);

-- 工艺卡紧固件定义（含标称力矩、是否 H 点）
CREATE TABLE IF NOT EXISTS fastener_spec (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    card_id         BIGINT       NOT NULL COMMENT '所属工序卡',
    point_code      VARCHAR(64)  NOT NULL COMMENT '紧固点编号',
    nominal_torque  DECIMAL(12,3) NOT NULL COMMENT '工艺卡标称力矩，单位 N·m',
    h_point         BOOLEAN      NOT NULL COMMENT '是否 H 点（停工见证点）',
    UNIQUE (card_id, point_code)
);

-- 力矩工具台账
CREATE TABLE IF NOT EXISTS torque_tool (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    tool_no         VARCHAR(64)  NOT NULL COMMENT '工具编号',
    last_check_utc_date DATE     NOT NULL COMMENT '最近一次日校验的 UTC 日期；当日(UTC 日界)有效',
    UNIQUE (tool_no)
);

-- 力矩紧固记录链：只追加，不覆盖；返工产生新记录行
CREATE TABLE IF NOT EXISTS tightening_record (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    card_id         BIGINT       NOT NULL COMMENT '所属工序卡',
    point_code      VARCHAR(64)  NOT NULL COMMENT '紧固点编号',
    attempt_no      INT          NOT NULL COMMENT '第几次尝试：1 首次，2/3 为第 1/2 次返工',
    measured_torque DECIMAL(12,3) NOT NULL COMMENT '实测力矩，单位 N·m',
    nominal_torque  DECIMAL(12,3) NOT NULL COMMENT '标称力矩快照，单位 N·m',
    within_tolerance BOOLEAN     NOT NULL COMMENT '是否在标称值 ±5% 内（含边界）',
    tool_no         VARCHAR(64)  NOT NULL COMMENT '使用的力矩工具编号',
    operator        VARCHAR(64)  NOT NULL COMMENT '紧固操作人员',
    recorded_at     TIMESTAMP    NOT NULL COMMENT '测量时间(UTC)',
    late            BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '是否为归档后迟到测量：TRUE 仅记迟到事件，不改变状态',
    UNIQUE (card_id, point_code, attempt_no)
);

-- 返工台账
CREATE TABLE IF NOT EXISTS rework_record (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    card_id       BIGINT      NOT NULL COMMENT '所属工序卡',
    point_code    VARCHAR(64) NOT NULL COMMENT '紧固点编号',
    rework_no     INT         NOT NULL COMMENT '返工序号：1 第一次返工，2 第二次返工（恰 2 次合法）',
    reason        VARCHAR(255) NOT NULL COMMENT '返工原因（如力矩超差）',
    created_at    TIMESTAMP   NOT NULL COMMENT '返工登记时间(UTC)',
    UNIQUE (card_id, point_code, rework_no)
);

-- 自检/互检签署
CREATE TABLE IF NOT EXISTS inspection_sign (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    card_id     BIGINT      NOT NULL COMMENT '所属工序卡',
    sign_type   VARCHAR(16) NOT NULL COMMENT '签署类型：SELF 自检 / MUTUAL 互检',
    inspector   VARCHAR(64) NOT NULL COMMENT '签署人员；自检与互检不得为同一人',
    signed_at   TIMESTAMP   NOT NULL COMMENT '签署时间(UTC)',
    UNIQUE (card_id, sign_type)
);

-- 见证链
CREATE TABLE IF NOT EXISTS witness_event (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    card_id       BIGINT      NOT NULL COMMENT '所属工序卡',
    point_code    VARCHAR(64) NOT NULL COMMENT '见证的 H 点编号',
    event_type    VARCHAR(32) NOT NULL COMMENT '事件：REQUESTED 提请见证/ABSENT 见证缺席转等待/ESCALATED 等待满 2 小时升级/WITNESSED 见证完成',
    witness       VARCHAR(64) NULL     COMMENT '见证人员，仅 WITNESSED 非空',
    event_at      TIMESTAMP   NOT NULL COMMENT '事件时间(UTC)',
    detail        VARCHAR(255) NULL    COMMENT '备注/升级说明'
);

-- 幂等记录：指纹覆盖 requestId 与全部业务字段
CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id    VARCHAR(96)  NOT NULL COMMENT '业务幂等键',
    request_type  VARCHAR(48)  NOT NULL COMMENT '请求类型（派工/紧固/自检/互检/见证/放行等）',
    fingerprint   VARCHAR(128) NOT NULL COMMENT 'requestId+全部业务字段的 SHA-256 指纹',
    response_json CLOB         NOT NULL COMMENT '首次成功响应快照（JSON）',
    http_status   INT          NOT NULL COMMENT '首次响应 HTTP 状态码',
    created_at    TIMESTAMP    NOT NULL COMMENT '首次受理时间(UTC)',
    PRIMARY KEY (request_id, request_type)
);

-- 迟到测量事件（归档后到达）
CREATE TABLE IF NOT EXISTS late_measurement (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    card_id       BIGINT       NOT NULL COMMENT '所属工序卡',
    point_code    VARCHAR(64)  NOT NULL COMMENT '紧固点编号',
    measured_torque DECIMAL(12,3) NOT NULL COMMENT '迟到实测力矩，单位 N·m',
    tool_no       VARCHAR(64)  NOT NULL COMMENT '工具编号',
    operator      VARCHAR(64)  NOT NULL COMMENT '操作人员',
    recorded_at   TIMESTAMP    NOT NULL COMMENT '测量发生时间(UTC)',
    received_at   TIMESTAMP    NOT NULL COMMENT '系统收到时间(UTC)'
);
