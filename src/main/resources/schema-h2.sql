-- 充电漫游服务 H2（MODE=MySQL）建表脚本；时间列一律存储 UTC。
-- 金额单位：元（人民币），保留 4 位小数；电量单位：Wh（瓦时，整数）。

CREATE TABLE IF NOT EXISTS ev_operator (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    operator_code   VARCHAR(32)  NOT NULL,
    operator_name   VARCHAR(64)  NOT NULL,
    tariff_rate     DECIMAL(12,4) NOT NULL,
    created_at      DATETIME(3)  NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_operator_code ON ev_operator (operator_code);
COMMENT ON COLUMN ev_operator.tariff_rate IS '运营商统一费率，单位：元/kWh（UTC 计价，无时区调整）';

CREATE TABLE IF NOT EXISTS ev_vehicle (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    cert_fingerprint VARCHAR(96) NOT NULL,
    vin             VARCHAR(32)  NOT NULL,
    plate_no        VARCHAR(16)  NOT NULL,
    home_operator   VARCHAR(32)  NOT NULL,
    created_at      DATETIME(3)  NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_vehicle_fingerprint ON ev_vehicle (cert_fingerprint);
COMMENT ON COLUMN ev_vehicle.cert_fingerprint IS '车辆证书指纹，即插即充授权的车辆标识';
COMMENT ON COLUMN ev_vehicle.home_operator IS '车辆归属运营商编码，应收按其费率计算';

CREATE TABLE IF NOT EXISTS charge_point (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    point_id        VARCHAR(32)  NOT NULL,
    point_name      VARCHAR(64)  NOT NULL,
    operator_code   VARCHAR(32)  NOT NULL,
    created_at      DATETIME(3)  NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_point_id ON charge_point (point_id);
COMMENT ON COLUMN charge_point.operator_code IS '充电桩归属运营商编码，应付按其费率计算';

CREATE TABLE IF NOT EXISTS charging_session (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_no          VARCHAR(40)  NOT NULL,
    cert_fingerprint    VARCHAR(96)  NOT NULL,
    point_id            VARCHAR(32)  NOT NULL,
    home_operator       VARCHAR(32)  NOT NULL,
    charging_operator   VARCHAR(32)  NOT NULL,
    auth_at             DATETIME(3)  NOT NULL,
    start_at            DATETIME(3)  NOT NULL,
    end_at              DATETIME(3)      NULL,
    status              VARCHAR(16)  NOT NULL,
    total_energy_wh     BIGINT       NOT NULL DEFAULT 0,
    last_meter_seq      INT          NOT NULL DEFAULT 0,
    batch_id            BIGINT           NULL,
    settlement_day      DATE             NULL,
    created_at          DATETIME(3)  NOT NULL,
    updated_at          DATETIME(3)  NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_session_no ON charging_session (session_no);
-- 同一车辆“同一请求时刻”只允许一个会话：并发授权时由该唯一约束保证唯一成功。
CREATE UNIQUE INDEX IF NOT EXISTS uk_session_fingerprint_time ON charging_session (cert_fingerprint, auth_at);
CREATE INDEX IF NOT EXISTS idx_session_fingerprint_status ON charging_session (cert_fingerprint, status);
CREATE INDEX IF NOT EXISTS idx_session_end_day ON charging_session (end_at, batch_id);
COMMENT ON COLUMN charging_session.status IS '会话状态：ACTIVE-进行中；ENDED-已结束';
COMMENT ON COLUMN charging_session.auth_at IS '授权请求时刻（UTC），同车同刻唯一';
COMMENT ON COLUMN charging_session.total_energy_wh IS '累计电量，单位 Wh；取最大抄表序号快照的累计抄表值';
COMMENT ON COLUMN charging_session.last_meter_seq IS '已归位的最大抄表序号，0 表示尚无快照';
COMMENT ON COLUMN charging_session.batch_id IS '所属清分批 id；NULL 表示尚未归批，归批后不可变更';
COMMENT ON COLUMN charging_session.settlement_day IS '清分归属日（UTC），取会话结束时刻的 UTC 日期';

CREATE TABLE IF NOT EXISTS meter_snapshot (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_no      VARCHAR(40)  NOT NULL,
    meter_seq       INT          NOT NULL,
    meter_wh        BIGINT       NOT NULL,
    snapshot_at     DATETIME(3)  NOT NULL,
    request_id      VARCHAR(64)  NOT NULL,
    created_at      DATETIME(3)  NOT NULL
);
-- 计量快照按“会话号＋抄表序号”幂等去重，乱序到达时仅归位不重计电量。
CREATE UNIQUE INDEX IF NOT EXISTS uk_snapshot_session_seq ON meter_snapshot (session_no, meter_seq);
CREATE UNIQUE INDEX IF NOT EXISTS uk_snapshot_request ON meter_snapshot (request_id);
COMMENT ON COLUMN meter_snapshot.meter_seq IS '抄表序号，从 1 递增；乱序到达按序号归位';
COMMENT ON COLUMN meter_snapshot.meter_wh IS '电表累计读数（含起点基线），单位 Wh';

CREATE TABLE IF NOT EXISTS idempotent_request (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id      VARCHAR(64)  NOT NULL,
    request_kind    VARCHAR(24)  NOT NULL,
    param_fingerprint CHAR(64)   NOT NULL,
    http_status     INT          NOT NULL,
    response_body   CLOB         NOT NULL,
    created_at      DATETIME(3)  NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_idempotent_request_id ON idempotent_request (request_id);
COMMENT ON COLUMN idempotent_request.request_kind IS '请求类型：AUTH/STOP/METER/FREEZE/SEAL/WRITE_OFF';
COMMENT ON COLUMN idempotent_request.param_fingerprint IS 'requestId 与全部业务字段的 SHA-256 指纹；异参冲突返回 409';

CREATE TABLE IF NOT EXISTS clearing_batch (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no            VARCHAR(40)  NOT NULL,
    settlement_day      DATE         NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    session_count       INT          NOT NULL DEFAULT 0,
    total_energy_wh     BIGINT       NOT NULL DEFAULT 0,
    total_receivable    DECIMAL(14,4) NOT NULL DEFAULT 0,
    total_payable       DECIMAL(14,4) NOT NULL DEFAULT 0,
    frozen_at           DATETIME(3)  NOT NULL,
    sealed_at           DATETIME(3)      NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_batch_no ON clearing_batch (batch_no);
-- 同一清分日允许存在多个批次：迟到结束的会话留待下一批；
-- 会话归批唯一性由“batch_id IS NULL”条件更新保证，不会既计入本批又留待下批。
CREATE INDEX IF NOT EXISTS idx_batch_day ON clearing_batch (settlement_day);
COMMENT ON COLUMN clearing_batch.status IS '批次状态：FROZEN-已冻结可计算；SEALED-封存终态，不可重算';
COMMENT ON COLUMN clearing_batch.total_receivable IS '应收合计（向车辆归属方用户收取口径），单位元，必须等于明细之和';
COMMENT ON COLUMN clearing_batch.total_payable IS '应付合计（向桩归属运营商结算口径），单位元，必须等于明细之和';

CREATE TABLE IF NOT EXISTS clearing_detail (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id            BIGINT       NOT NULL,
    session_no          VARCHAR(40)  NOT NULL,
    home_operator       VARCHAR(32)  NOT NULL,
    charging_operator   VARCHAR(32)  NOT NULL,
    energy_wh           BIGINT       NOT NULL,
    receivable_amount   DECIMAL(14,4) NOT NULL,
    payable_amount      DECIMAL(14,4) NOT NULL,
    created_at          DATETIME(3)  NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_detail_batch_session ON clearing_detail (batch_id, session_no);
COMMENT ON COLUMN clearing_detail.energy_wh IS '明细电量，单位 Wh；批内会话合计必须等于明细之和';
COMMENT ON COLUMN clearing_detail.receivable_amount IS '按车辆归属运营商费率计算的应收，单位元';
COMMENT ON COLUMN clearing_detail.payable_amount IS '按桩归属运营商费率计算的应付，单位元';

CREATE TABLE IF NOT EXISTS discrepancy (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    discrepancy_no      VARCHAR(40)  NOT NULL,
    session_no          VARCHAR(40)  NOT NULL,
    meter_seq           INT          NOT NULL,
    meter_wh            BIGINT       NOT NULL,
    snapshot_at         DATETIME(3)  NOT NULL,
    source_request_id   VARCHAR(64)  NOT NULL,
    batch_id            BIGINT       NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    write_off_at        DATETIME(3)      NULL,
    write_off_by        VARCHAR(64)      NULL,
    write_off_remark    VARCHAR(255)     NULL,
    write_off_request_id VARCHAR(64)     NULL,
    created_at          DATETIME(3)  NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_discrepancy_no ON discrepancy (discrepancy_no);
-- 同一封存批内同一会话同一序号只挂一次账。
CREATE UNIQUE INDEX IF NOT EXISTS uk_discrepancy_session_seq ON discrepancy (session_no, meter_seq);
COMMENT ON COLUMN discrepancy.status IS '挂账状态：OPEN-待核销；WRITTEN_OFF-已人工核销（终态，仅允许一次）';
COMMENT ON COLUMN discrepancy.write_off_by IS '核销操作人，核销后非空，留痕';
COMMENT ON COLUMN discrepancy.write_off_remark IS '核销说明，人工必填，留痕';
COMMENT ON COLUMN discrepancy.write_off_request_id IS '核销请求 requestId，幂等且留痕';
