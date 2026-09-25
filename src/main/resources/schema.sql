-- 生猪屠宰同步检疫与无害化处理：H2（MODE=MySQL）建表脚本
-- 所有时刻均以 UTC 存储；状态语义见对应枚举与 Entity Javadoc。

CREATE TABLE IF NOT EXISTS slaughter_batch (
    id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no                 VARCHAR(64)  NOT NULL,
    status                   VARCHAR(32)  NOT NULL,
    head_count               INT          NOT NULL,
    declared_at_utc          TIMESTAMP    NOT NULL,
    pre_quarantine_result    VARCHAR(16),
    pre_quarantine_at_utc    TIMESTAMP,
    valid_until_utc          TIMESTAMP,
    inspection_chain_id      BIGINT,
    judged_at_utc            TIMESTAMP,
    checked_out_at_utc       TIMESTAMP,
    redeclare_no             INT          NOT NULL DEFAULT 0,
    version                  INT          NOT NULL DEFAULT 0,
    created_at_utc           TIMESTAMP    NOT NULL,
    updated_at_utc           TIMESTAMP    NOT NULL,
    CONSTRAINT uk_batch_no UNIQUE (batch_no)
);
COMMENT ON COLUMN slaughter_batch.batch_no IS '屠宰批次编号（合成数据，业务唯一）';
COMMENT ON COLUMN slaughter_batch.status IS '批次状态：DECLARED/PRE_QUARANTINED/QUARANTINE_REJECTED/INSPECTING/INSPECTED/SUSPENDED/JUDGED/CHECKED_OUT';
COMMENT ON COLUMN slaughter_batch.head_count IS '申报头只总数，单位：头';
COMMENT ON COLUMN slaughter_batch.declared_at_utc IS '宰前申报时刻（UTC）';
COMMENT ON COLUMN slaughter_batch.pre_quarantine_result IS '宰前检疫结论：PASS/FAIL；NULL 表示尚未检疫';
COMMENT ON COLUMN slaughter_batch.pre_quarantine_at_utc IS '宰前检疫时刻（UTC）；NULL 表示尚未检疫';
COMMENT ON COLUMN slaughter_batch.valid_until_utc IS '宰前检疫/申报有效期截止（UTC）=申报时刻+6 小时，恰满即失效';
COMMENT ON COLUMN slaughter_batch.inspection_chain_id IS '当前同步检验链 ID；同一批次同时刻只允许一条';
COMMENT ON COLUMN slaughter_batch.judged_at_utc IS '判定分流完成时刻（UTC）；NULL 表示未判定';
COMMENT ON COLUMN slaughter_batch.checked_out_at_utc IS '出厂核销时刻（UTC）；非 NULL 表示终态已核销';
COMMENT ON COLUMN slaughter_batch.redeclare_no IS '重新申报次数；恰满 6 小时未屠宰重新申报时递增';

CREATE TABLE IF NOT EXISTS batch_head (
    id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no                 VARCHAR(64)  NOT NULL,
    head_no                  VARCHAR(64)  NOT NULL,
    inspection_result        VARCHAR(16),
    inspected_at_utc         TIMESTAMP,
    judgment_category        VARCHAR(16),
    judged_at_utc            TIMESTAMP,
    inventory_scope          VARCHAR(16),
    limited_region           VARCHAR(128),
    heat_treatment           BOOLEAN,
    receipt_id               BIGINT,
    created_at_utc           TIMESTAMP    NOT NULL,
    CONSTRAINT uk_head UNIQUE (batch_no, head_no)
);
COMMENT ON COLUMN batch_head.head_no IS '头只编号（合成数据），批次内唯一';
COMMENT ON COLUMN batch_head.inspection_result IS '同步检验结果：NORMAL/SUSPECT；NULL 表示漏检';
COMMENT ON COLUMN batch_head.judgment_category IS '判定分流：QUALIFIED/CONDITIONAL/RENDERED/DESTROYED；NULL 未判定';
COMMENT ON COLUMN batch_head.inventory_scope IS '库存标识：GENERAL 普通/LIMITED_REGION 限定区域/NONE 不入库（化制销毁）';
COMMENT ON COLUMN batch_head.limited_region IS '有条件食用的限定流通区域；仅 CONDITIONAL 非空';
COMMENT ON COLUMN batch_head.heat_treatment IS '是否标注高温处理；仅 CONDITIONAL 为 TRUE';
COMMENT ON COLUMN batch_head.receipt_id IS '无害化处理回执 ID；NULL 表示回执未闭环';

CREATE TABLE IF NOT EXISTS inspection_chain (
    id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no                 VARCHAR(64)  NOT NULL,
    status                   VARCHAR(16)  NOT NULL,
    inspected_count          INT          NOT NULL DEFAULT 0,
    expected_count           INT          NOT NULL,
    opened_at_utc            TIMESTAMP    NOT NULL,
    completed_at_utc         TIMESTAMP,
    CONSTRAINT uk_chain_batch UNIQUE (batch_no)
);
COMMENT ON COLUMN inspection_chain.status IS '检验链状态：OPEN/COMPLETED；唯一约束保证同批次同时刻仅一条链';
COMMENT ON COLUMN inspection_chain.inspected_count IS '已一一对应检验的头只数，单位：头';
COMMENT ON COLUMN inspection_chain.expected_count IS '申报头只总数，单位：头';

CREATE TABLE IF NOT EXISTS disposal_receipt (
    id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
    receipt_no               VARCHAR(64)  NOT NULL,
    batch_no                 VARCHAR(64)  NOT NULL,
    category                 VARCHAR(16)  NOT NULL,
    head_count               INT          NOT NULL,
    accepted                 BOOLEAN      NOT NULL DEFAULT FALSE,
    late                     BOOLEAN      NOT NULL DEFAULT FALSE,
    alerted                  BOOLEAN      NOT NULL DEFAULT FALSE,
    received_at_utc          TIMESTAMP    NOT NULL,
    CONSTRAINT uk_receipt_no UNIQUE (receipt_no)
);
COMMENT ON COLUMN disposal_receipt.receipt_no IS '无害化处理回执编号（合成数据）';
COMMENT ON COLUMN disposal_receipt.category IS '处理类别：RENDERED 化制 / DESTROYED 销毁';
COMMENT ON COLUMN disposal_receipt.head_count IS '回执登记头只数，单位：头，须与同类判定数勾稽';
COMMENT ON COLUMN disposal_receipt.accepted IS '勾稽恰等受理为 TRUE；不等则 FALSE 并拒绝出厂';
COMMENT ON COLUMN disposal_receipt.late IS '是否核销终态后迟到回执：TRUE 只记迟到事件';
COMMENT ON COLUMN disposal_receipt.alerted IS '迟到回执是否已告警';

CREATE TABLE IF NOT EXISTS receipt_head (
    id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
    receipt_id               BIGINT       NOT NULL,
    batch_no                 VARCHAR(64)  NOT NULL,
    head_no                  VARCHAR(64)  NOT NULL,
    created_at_utc           TIMESTAMP    NOT NULL,
    CONSTRAINT uk_receipt_head UNIQUE (receipt_id, head_no)
);
COMMENT ON COLUMN receipt_head.head_no IS '回执闭环头只编号，须为同类别判定头只';

CREATE TABLE IF NOT EXISTS inventory_mark (
    id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no                 VARCHAR(64)  NOT NULL,
    head_no                  VARCHAR(64)  NOT NULL,
    category                 VARCHAR(16)  NOT NULL,
    scope                    VARCHAR(16)  NOT NULL,
    limited_region           VARCHAR(128),
    heat_treatment           BOOLEAN,
    marked_at_utc            TIMESTAMP    NOT NULL,
    CONSTRAINT uk_inventory_head UNIQUE (batch_no, head_no)
);
COMMENT ON COLUMN inventory_mark.scope IS '库存标识：GENERAL 合格普通库存/LIMITED_REGION 有条件食用限定区域';
COMMENT ON COLUMN inventory_mark.limited_region IS '限定流通区域；仅 LIMITED_REGION 非空';
COMMENT ON COLUMN inventory_mark.heat_treatment IS '是否标注高温处理；有条件食用为 TRUE，合格为 FALSE，化制销毁不入库无标识';

CREATE TABLE IF NOT EXISTS late_receipt_event (
    id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no                 VARCHAR(64)  NOT NULL,
    receipt_no               VARCHAR(64)  NOT NULL,
    category                 VARCHAR(16)  NOT NULL,
    head_count               INT          NOT NULL,
    occurred_at_utc          TIMESTAMP    NOT NULL,
    alerted                  BOOLEAN      NOT NULL DEFAULT FALSE
);
COMMENT ON COLUMN late_receipt_event.occurred_at_utc IS '迟到回执到达时刻（UTC），发生在出厂核销之后';

CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id               VARCHAR(64)  NOT NULL,
    operation                VARCHAR(32)  NOT NULL,
    fingerprint              CHAR(64)     NOT NULL,
    http_status              INT          NOT NULL,
    response_body            CLOB         NOT NULL,
    created_at_utc           TIMESTAMP    NOT NULL,
    CONSTRAINT pk_idempotent_request PRIMARY KEY (request_id)
);
COMMENT ON COLUMN idempotent_request.fingerprint IS 'SHA-256 指纹，覆盖 requestId 与全部业务字段';
COMMENT ON COLUMN idempotent_request.operation IS '操作类型：DECLARE/PRE_QUARANTINE/INSPECTION/JUDGMENT/RECEIPT/CHECKOUT/REDECLARE';
