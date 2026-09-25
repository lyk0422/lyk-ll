-- 信用证本地单证服务建表脚本（H2 MODE=MySQL 兼容语法）
-- 所有时间均以 UTC 存储，金额单位为信用证币种元，保留两位小数。

CREATE TABLE IF NOT EXISTS lc_credit (
    lc_no            VARCHAR(40)  NOT NULL PRIMARY KEY COMMENT '信用证编号',
    applicant        VARCHAR(120) NOT NULL COMMENT '开证申请人名称',
    beneficiary      VARCHAR(120) NOT NULL COMMENT '受益人名称',
    currency         VARCHAR(8)   NOT NULL DEFAULT 'USD' COMMENT '币种（ISO 4217）',
    total_amount     DECIMAL(18,2) NOT NULL COMMENT '信用证总金额（币种元）',
    available_amount DECIMAL(18,2) NOT NULL COMMENT '当前可用额度（币种元），随付款核减',
    expiry_date      DATE         NOT NULL COMMENT '交单有效期（UTC 日期，含当日）',
    status           VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE-有效 CLOSED-结清',
    created_at       TIMESTAMP    NOT NULL COMMENT '创建时间（UTC）'
);

-- 交单批次：一次交单/改单重交对应一行；status 见 SubmissionStatus
CREATE TABLE IF NOT EXISTS doc_submission (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no      VARCHAR(64)  NOT NULL UNIQUE COMMENT '交单批次号',
    lc_no         VARCHAR(40)  NOT NULL COMMENT '所属信用证编号',
    beneficiary   VARCHAR(120) NOT NULL COMMENT '交单受益人',
    draw_amount   DECIMAL(18,2) NOT NULL COMMENT '本批支款金额（币种元）',
    status        VARCHAR(24)  NOT NULL COMMENT '状态：UNDER_REVIEW-审单中 REFUSED-已拒付 DEEMED_ACCEPTED-逾期视为接受 ACCEPTED-放弃不符点后接受 PAID-已付款',
    presented_at  TIMESTAMP    NOT NULL COMMENT '银行收到单据时间（UTC），审单期起算前一日',
    deadline_at   TIMESTAMP    NOT NULL COMMENT '拒付通知截止时间（UTC，第5个工作日17:00）',
    refused_at    TIMESTAMP    NULL COMMENT '拒付通知发出时间（UTC），NULL 表示未拒付',
    accepted_at   TIMESTAMP    NULL COMMENT '接受时间（UTC），NULL 表示未接受',
    paid_at       TIMESTAMP    NULL COMMENT '付款时间（UTC），NULL 表示未付款',
    created_at    TIMESTAMP    NOT NULL COMMENT '批次创建时间（UTC）',
    -- 生成列：仅审单中批次等于信用证编号，其余为 NULL；配合唯一约束实现同一信用证同时刻仅一个审单中批次（NULL 可重复）
    reviewing_lc  VARCHAR(40) GENERATED ALWAYS AS (CASE WHEN status = 'UNDER_REVIEW' THEN lc_no ELSE NULL END),
    CONSTRAINT fk_submission_lc FOREIGN KEY (lc_no) REFERENCES lc_credit (lc_no),
    CONSTRAINT uk_submission_reviewing UNIQUE (reviewing_lc)
);

-- 交单项下单据清单
CREATE TABLE IF NOT EXISTS submission_document (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no  VARCHAR(64) NOT NULL COMMENT '所属交单批次号',
    doc_type  VARCHAR(60) NOT NULL COMMENT '单据类型，如 BILL_OF_LADING/COMMERCIAL_INVOICE',
    doc_ref   VARCHAR(120) NOT NULL COMMENT '单据编号/标识',
    CONSTRAINT fk_doc_batch FOREIGN KEY (batch_no) REFERENCES doc_submission (batch_no)
);

-- 拒付不符点：必须随拒付通知一次性列明，事后不得新增
CREATE TABLE IF NOT EXISTS discrepancy (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no    VARCHAR(64)  NOT NULL COMMENT '所属交单批次号',
    doc_type    VARCHAR(60)  NOT NULL COMMENT '不符点对应单据类型',
    description VARCHAR(500) NOT NULL COMMENT '不符点描述',
    waived      BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '是否已被申请人声明放弃',
    created_at  TIMESTAMP    NOT NULL COMMENT '不符点登记时间（UTC）',
    CONSTRAINT fk_disc_batch FOREIGN KEY (batch_no) REFERENCES doc_submission (batch_no)
);

-- requestId 幂等记录：指纹覆盖 requestId 与全部业务字段
CREATE TABLE IF NOT EXISTS idempotent_record (
    request_id   VARCHAR(80)  NOT NULL PRIMARY KEY COMMENT '幂等键 requestId',
    fingerprint  VARCHAR(64)  NOT NULL COMMENT '业务字段规范化后的 SHA-256 指纹',
    operation    VARCHAR(24)  NOT NULL COMMENT '操作类型：CREATE_LC/PRESENT/REFUSE/WAIVE/PAY',
    http_status  INT          NOT NULL COMMENT '首次响应 HTTP 状态码，0 表示首次处理进行中',
    result_body  CLOB         NOT NULL COMMENT '首次响应体 JSON，重放原样返回',
    created_at   TIMESTAMP    NOT NULL COMMENT '首次请求时间（UTC）'
);
