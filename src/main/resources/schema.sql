-- 闸门调度服务建表脚本（H2 MODE=MySQL 兼容；时间列为 UTC 时刻）

-- 水库：合成数据
CREATE TABLE IF NOT EXISTS reservoir (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '水库ID',
    code         VARCHAR(32)  NOT NULL COMMENT '水库编码',
    name         VARCHAR(64)  NOT NULL COMMENT '水库名称',
    created_at   TIMESTAMP    NOT NULL COMMENT '记录创建UTC时刻'
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_reservoir_code ON reservoir (code);

-- 闸门：合成数据；opening 为当前开度百分比（0~100，单位：%，UTC 时刻由 updated_at 标记）
CREATE TABLE IF NOT EXISTS gate (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '闸门ID',
    code         VARCHAR(32)  NOT NULL COMMENT '闸门编码',
    name         VARCHAR(64)  NOT NULL COMMENT '闸门名称',
    reservoir_id BIGINT       NOT NULL COMMENT '所属水库ID',
    opening      DECIMAL(7,3) NOT NULL DEFAULT 0 COMMENT '当前开度百分比，单位%，0~100',
    updated_at   TIMESTAMP    NOT NULL COMMENT '当前开度对应UTC时刻',
    CONSTRAINT fk_gate_reservoir FOREIGN KEY (reservoir_id) REFERENCES reservoir (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_gate_code ON gate (code);

-- 水情读数：合成数据（入库流量、水位），仅作背景信息
CREATE TABLE IF NOT EXISTS water_reading (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '水情读数ID',
    reservoir_id BIGINT       NOT NULL COMMENT '所属水库ID',
    inflow       DECIMAL(12,3) NOT NULL COMMENT '入库流量，单位立方米每秒',
    level_value  DECIMAL(8,3)  NOT NULL COMMENT '库水位，单位米',
    read_at      TIMESTAMP    NOT NULL COMMENT '读数UTC时刻',
    CONSTRAINT fk_reading_reservoir FOREIGN KEY (reservoir_id) REFERENCES reservoir (id)
);

-- 调度令状态机：
-- ISSUED 已下达 -> ACCEPTED 已签收 -> GATE_DONE(每门回执进度，不单独建状态，用回执行表示)
-- 终态：COMPLETED 全部完成 / DEVIATION 偏差告警后仍可继续？本实现偏差仅告警不阻断完成
--       TIMEOUT 超时升级 / CANCELLED 签收前撤销（终态）/ ABORTED 签收后中止（终态）
CREATE TABLE IF NOT EXISTS dispatch_order (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '调度令ID',
    request_id         VARCHAR(64)  NOT NULL COMMENT '幂等键，调用方生成的请求ID',
    fingerprint        VARCHAR(64)  NOT NULL COMMENT '业务指纹SHA-256，覆盖requestId与全部业务字段',
    gate_codes         VARCHAR(1000) NOT NULL COMMENT '令中闸门编码，按执行顺序，逗号分隔',
    target_openings    VARCHAR(1000) NOT NULL COMMENT '目标开度序列(%)，与闸门一一对应，逗号分隔',
    deadline           TIMESTAMP    NOT NULL COMMENT '执行时限，UTC，逾期未收齐回执转超时升级',
    status             VARCHAR(16)  NOT NULL COMMENT '状态：ISSUED/ACCEPTED/COMPLETED/TIMEOUT/CANCELLED/ABORTED',
    issued_at          TIMESTAMP    NOT NULL COMMENT '下达UTC时刻',
    accepted_at        TIMESTAMP    NULL     COMMENT '签收UTC时刻，空表示尚未签收',
    completed_at       TIMESTAMP    NULL     COMMENT '全部回执收齐UTC时刻',
    timeout_at         TIMESTAMP    NULL     COMMENT '超时升级UTC时刻',
    cancelled_at       TIMESTAMP    NULL     COMMENT '撤销UTC时刻',
    aborted_at         TIMESTAMP    NULL     COMMENT '中止UTC时刻',
    escalated          TINYINT      NOT NULL DEFAULT 0 COMMENT '是否已通知上级：1已通知0未通知',
    aborted_detail     VARCHAR(2000) NULL     COMMENT '中止时已执行闸门实际开度留痕JSON',
    version            BIGINT       NOT NULL DEFAULT 0 COMMENT '乐观锁版本号'
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_order_request ON dispatch_order (request_id);

-- 闸门在令占用：同一闸门同一时刻只允许一张未完成调度令
-- order_id 非空表示被该令占用；令进入终态时删除其占用行（或保留历史用 active 标记）。
CREATE TABLE IF NOT EXISTS gate_occupancy (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '占用记录ID',
    gate_code   VARCHAR(32) NOT NULL COMMENT '被占用闸门编码',
    order_id    BIGINT      NOT NULL COMMENT '占用该闸门的调度令ID',
    created_at  TIMESTAMP   NOT NULL COMMENT '占用UTC时刻',
    CONSTRAINT fk_occ_order FOREIGN KEY (order_id) REFERENCES dispatch_order (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_occ_gate ON gate_occupancy (gate_code);

-- 逐门执行回执
CREATE TABLE IF NOT EXISTS gate_receipt (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '回执记录ID',
    order_id        BIGINT       NOT NULL COMMENT '所属调度令ID',
    seq_no          INT          NOT NULL COMMENT '闸门在令中的顺序，从0开始',
    gate_code       VARCHAR(32)  NOT NULL COMMENT '闸门编码',
    target_opening  DECIMAL(7,3) NOT NULL COMMENT '目标开度百分比，单位%',
    actual_opening  DECIMAL(7,3) NULL     COMMENT '回执实际开度百分比，单位%，空表示尚未回执',
    in_position     TINYINT      NULL     COMMENT '是否到位：1到位0偏差，空表示未回执',
    deviation_pct   DECIMAL(9,4) NULL     COMMENT '偏差百分比=(实际-目标)/目标*100，空表示未回执',
    alarmed         TINYINT      NOT NULL DEFAULT 0 COMMENT '偏差是否已自动告警：1已告警0未告警',
    received_at     TIMESTAMP    NULL     COMMENT '回执到达UTC时刻',
    CONSTRAINT fk_rcpt_order FOREIGN KEY (order_id) REFERENCES dispatch_order (id),
    CONSTRAINT uk_rcpt_order_seq UNIQUE (order_id, seq_no)
);

-- 告警记录（执行偏差自动告警 + 超时升级通知上级，均落库留痕，不依赖外部监控）
CREATE TABLE IF NOT EXISTS alarm_event (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '告警记录ID',
    order_id    BIGINT       NOT NULL COMMENT '关联调度令ID',
    alarm_type  VARCHAR(24)  NOT NULL COMMENT '告警类型：GATE_DEVIATION闸门偏差/TIMEOUT_ESCALATION超时升级',
    gate_code   VARCHAR(32)  NULL     COMMENT '偏差闸门编码，超时告警为空',
    detail      VARCHAR(2000) NOT NULL COMMENT '告警内容',
    created_at  TIMESTAMP    NOT NULL COMMENT '告警UTC时刻'
);
