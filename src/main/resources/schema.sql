-- 信用证审单服务建表脚本（H2 MODE=MySQL 兼容；时间列均为 UTC）。
CREATE TABLE IF NOT EXISTS lc_account (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    lc_no            VARCHAR(64)  NOT NULL COMMENT '信用证编号',
    applicant        VARCHAR(128) NOT NULL COMMENT '开证申请人',
    beneficiary      VARCHAR(128) NOT NULL COMMENT '受益人',
    currency         VARCHAR(3)   NOT NULL DEFAULT 'USD' COMMENT '币种',
    amount_total     DECIMAL(18,2) NOT NULL COMMENT '信用证总金额',
    amount_available DECIMAL(18,2) NOT NULL COMMENT '当前可用额度，随成功付款核减；单位与币种一致',
    expiry_utc       DATETIME     NOT NULL COMMENT '交单有效期截止时刻(UTC)，逾期不得交单/改单重交',
    created_at_utc   DATETIME     NOT NULL,
    updated_at_utc   DATETIME     NOT NULL,
    CONSTRAINT uq_lc_no UNIQUE (lc_no)
);

CREATE TABLE IF NOT EXISTS presentation_batch (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    presentation_no  VARCHAR(64)  NOT NULL COMMENT '交单批次业务编号',
    lc_no            VARCHAR(64)  NOT NULL COMMENT '所属信用证编号',
    batch_seq        INT          NOT NULL COMMENT '批次序号：首次交单=1，改单重交逐次加1',
    supersedes_id    BIGINT       NULL COMMENT '改单重交时被取代的上一批次ID，首交为空',
    status           VARCHAR(32)  NOT NULL COMMENT '状态：UNDER_REVIEW审单中/REJECTED已拒付/DEEMED_ACCEPTED超期视为接受/ACCEPTED已接受/PAID已付款终态/REFUNDED拒付退单终态/SUPERSEDED被改单重交取代',
    draw_amount      DECIMAL(18,2) NOT NULL COMMENT '本批次索偿/付款金额',
    docs_json        CLOB         NOT NULL COMMENT '交单单据清单JSON（合成数据）',
    received_at_utc  DATETIME     NOT NULL COMMENT '银行收到单据时刻(UTC)，审单期起算点',
    deadline_utc     DATETIME     NOT NULL COMMENT '审单截止：收到次日起第5个银行工作日17:00(UTC)',
    notice_at_utc    DATETIME     NULL COMMENT '拒付通知发出时刻(UTC)，未发为空',
    accepted_at_utc  DATETIME     NULL COMMENT '接受（含放弃不符点/视为接受）时刻(UTC)',
    paid_at_utc      DATETIME     NULL COMMENT '实际付款时刻(UTC)，终态凭据',
    refunded_at_utc  DATETIME     NULL COMMENT '拒付退单时刻(UTC)',
    active_slot      BIGINT       NULL COMMENT '审单中占位值=lc_account.id；离开UNDER_REVIEW置空，配合唯一索引保证同一信用证同时只有一个审单中批次',
    created_at_utc   DATETIME     NOT NULL,
    updated_at_utc   DATETIME     NOT NULL,
    CONSTRAINT uq_presentation_no UNIQUE (presentation_no),
    CONSTRAINT uq_active_presentation UNIQUE (lc_no, active_slot)
);

CREATE TABLE IF NOT EXISTS discrepancy (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    presentation_id  BIGINT       NOT NULL COMMENT '所属交单批次ID',
    doc_type         VARCHAR(64)  NOT NULL COMMENT '不符点所在单据类型，如BILL_OF_LADING/COMMERCIAL_INVOICE',
    discrepancy_code VARCHAR(64)  NOT NULL COMMENT '不符点代码',
    description      VARCHAR(512) NOT NULL COMMENT '不符点描述（合成）',
    seq_no           INT          NOT NULL COMMENT '一次性通知内的排列序号',
    created_at_utc   DATETIME     NOT NULL,
    CONSTRAINT uq_discrepancy UNIQUE (presentation_id, doc_type, discrepancy_code)
);

CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id       VARCHAR(64)  PRIMARY KEY COMMENT '客户端幂等键requestId',
    operation_type   VARCHAR(32)  NOT NULL COMMENT '操作类型：PRESENT/NOTICE/WAIVER/PAY/RESUBMIT/REFUND',
    fingerprint      CHAR(64)     NOT NULL COMMENT 'requestId与全部业务字段（不符点按单据类型排序规范化）的SHA-256指纹',
    http_status      INT          NOT NULL COMMENT '首次响应HTTP状态码',
    response_body    CLOB         NOT NULL COMMENT '首次响应体JSON，重放原样返回',
    created_at_utc   DATETIME     NOT NULL
);
