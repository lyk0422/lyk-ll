-- 数电发票红冲与授信额度恢复服务建表脚本（H2 MySQL 兼容模式，仅合成数据）
-- 金额单位：元；既有业务表时间均为应用服务器本地时间（Asia/Shanghai）；
-- 差异单（discrepancy*）相关表按 72 小时升级规则统一使用 UTC 时间；NULL 含义见各列注释。

CREATE TABLE IF NOT EXISTS credit_account (
    seller_id    VARCHAR(64)   NOT NULL COMMENT '开票方ID（合成）',
    total_limit  DECIMAL(19,2) NOT NULL COMMENT '授信总额度，单位元',
    created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (seller_id)
);

CREATE TABLE IF NOT EXISTS credit_ledger (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '台账流水号',
    seller_id       VARCHAR(64)  NOT NULL COMMENT '开票方ID',
    change_amount   DECIMAL(19,2) NOT NULL COMMENT '额度变动金额，正为恢复/释放，负为占用，单位元',
    reason          VARCHAR(32)  NOT NULL COMMENT '变动原因：ISSUE开票占用/RED_CONFIRM红票确认恢复/ARCHIVE归档释放',
    ref_no          VARCHAR(64)  NOT NULL COMMENT '关联单据号（发票号或红字信息表号）',
    available_after DECIMAL(19,2) NOT NULL COMMENT '变动后可用额度，单位元',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS invoice (
    id                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    invoice_no         VARCHAR(64)  NOT NULL COMMENT '发票号码，全局唯一',
    kind               VARCHAR(8)   NOT NULL COMMENT '票种：BLUE蓝票/RED红票',
    seller_id          VARCHAR(64)  NOT NULL COMMENT '开票方ID',
    buyer_id           VARCHAR(64)  NOT NULL COMMENT '购方ID',
    buyer_booked       BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '购方是否已入账；TRUE时全额红冲须取得购方确认',
    total_amount       DECIMAL(19,2) NOT NULL COMMENT '票面金额，红票为红冲金额，单位元',
    red_flushed_amount DECIMAL(19,2) NOT NULL DEFAULT 0 COMMENT '蓝票累计已红冲金额，红票恒为0，单位元',
    status             VARCHAR(16)  NOT NULL COMMENT '蓝票状态：ISSUED已开具/DELIVERED已交付/PARTIAL_RED部分红冲/RED_FLUSHED全额红冲终态/ARCHIVED归档终态；红票固定ISSUED',
    blue_invoice_no    VARCHAR(64)  NULL COMMENT '红票引用的原蓝票号码；蓝票为NULL',
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_invoice_no (invoice_no)
);

CREATE TABLE IF NOT EXISTS invoice_line (
    id                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    invoice_id         BIGINT       NOT NULL COMMENT '所属发票ID',
    line_no            INT          NOT NULL COMMENT '行号，从1开始',
    item_name          VARCHAR(128) NOT NULL COMMENT '行项目名称',
    amount             DECIMAL(19,2) NOT NULL COMMENT '行金额，单位元',
    red_flushed_amount DECIMAL(19,2) NOT NULL DEFAULT 0 COMMENT '该行累计已红冲金额，单位元',
    PRIMARY KEY (id),
    UNIQUE KEY uk_invoice_line (invoice_id, line_no)
);

CREATE TABLE IF NOT EXISTS red_flush_request (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    request_no      VARCHAR(64)  NOT NULL COMMENT '红字信息表编号，全局唯一',
    blue_invoice_id BIGINT       NOT NULL COMMENT '被红冲蓝票ID',
    blue_invoice_no VARCHAR(64)  NOT NULL COMMENT '被红冲蓝票号码（红冲链引用）',
    flush_type      VARCHAR(8)   NOT NULL COMMENT '红冲类型：FULL全额/PARTIAL部分（按行项目）',
    total_amount    DECIMAL(19,2) NOT NULL COMMENT '本次申请红冲金额，单位元',
    status          VARCHAR(16)  NOT NULL COMMENT '状态：PENDING待双方确认/SUSPENDED意见不一致挂起/ISSUED已开红票/TERMINATED整批终止释放',
    seller_confirmed BOOLEAN     NOT NULL DEFAULT FALSE COMMENT '销方是否已确认',
    buyer_confirmed  BOOLEAN     NOT NULL DEFAULT FALSE COMMENT '购方是否已确认',
    red_invoice_no  VARCHAR(64)  NULL COMMENT '开具的红票号码；未开具为NULL',
    batch_id        BIGINT       NULL COMMENT '所属整批红冲ID；普通红冲为NULL，受控子单禁止普通确认入口',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_red_request_no (request_no)
);

CREATE TABLE IF NOT EXISTS red_flush_line (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    request_id   BIGINT       NOT NULL COMMENT '所属红字信息表ID',
    blue_line_id BIGINT       NOT NULL COMMENT '被红冲的蓝票行ID',
    line_no      INT          NOT NULL COMMENT '蓝票行号',
    amount       DECIMAL(19,2) NOT NULL COMMENT '本行红冲金额，单位元',
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS red_flush_confirmation (
    id         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    request_id BIGINT      NOT NULL COMMENT '所属红字信息表ID',
    side       VARCHAR(8)  NOT NULL COMMENT '确认方：SELLER销方/BUYER购方',
    opinion    VARCHAR(8)  NOT NULL COMMENT '意见：AGREE同意/REJECT异议',
    created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '确认时间',
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id      VARCHAR(64)  NOT NULL COMMENT '幂等键，调用方提供',
    endpoint        VARCHAR(64)  NOT NULL COMMENT '业务端点标识',
    fingerprint     VARCHAR(128) NOT NULL COMMENT '请求指纹：requestId+全部业务字段的SHA-256',
    response_status INT          NOT NULL COMMENT '首次响应HTTP状态码',
    response_body   CLOB         NOT NULL COMMENT '首次响应报文，重放原样返回',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (request_id, endpoint)
);

CREATE TABLE IF NOT EXISTS discrepancy (
    id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    discrepancy_no  VARCHAR(64)   NOT NULL COMMENT '差异单编号，全局唯一',
    blue_invoice_id BIGINT        NOT NULL COMMENT '蓝票ID',
    blue_invoice_no VARCHAR(64)   NOT NULL COMMENT '蓝票号码',
    blue_line_id    BIGINT        NOT NULL COMMENT '蓝票行ID',
    line_no         INT           NOT NULL COMMENT '蓝票行号',
    claimed_amount  DECIMAL(19,2) NOT NULL COMMENT '购方主张的该行正确金额，单位元',
    diff_amount     DECIMAL(19,2) NOT NULL COMMENT '需冲减差异金额=行金额-购方主张金额，单位元',
    evidence_ref    VARCHAR(128)  NOT NULL COMMENT '合成凭据标识',
    status          VARCHAR(16)   NOT NULL COMMENT '状态：OPEN待销方答复/CONFIRMED销方已确认/REJECTED销方已驳回/ESCALATED满72小时未答复升级人工',
    red_request_no  VARCHAR(64)   NULL COMMENT '销方确认后按原红字信息表流程创建的申请编号；未创建为NULL',
    created_at      TIMESTAMP     NOT NULL COMMENT '提交时间（UTC）',
    responded_at    TIMESTAMP     NULL COMMENT '销方答复时间（UTC）；未答复为NULL',
    PRIMARY KEY (id),
    UNIQUE KEY uk_discrepancy_no (discrepancy_no)
);

CREATE TABLE IF NOT EXISTS discrepancy_event (
    id             BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    discrepancy_id BIGINT      NOT NULL COMMENT '差异单ID',
    event          VARCHAR(16) NOT NULL COMMENT '事件：SUBMITTED提交/CONFIRMED确认/REJECTED驳回/ESCALATED升级人工',
    created_at     TIMESTAMP   NOT NULL COMMENT '事件时间（UTC）',
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS discrepancy_conflict (
    id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    blue_invoice_no VARCHAR(64)   NOT NULL COMMENT '蓝票号码',
    line_no         INT           NOT NULL COMMENT '蓝票行号',
    claimed_amount  DECIMAL(19,2) NOT NULL COMMENT '购方主张的该行正确金额，单位元',
    evidence_ref    VARCHAR(128)  NOT NULL COMMENT '合成凭据标识',
    reason          VARCHAR(32)   NOT NULL COMMENT '冲突原因：RED_FLUSH_IN_PROGRESS红冲在途/RED_FLUSH_COMPLETED红冲已完成',
    created_at      TIMESTAMP     NOT NULL COMMENT '记录时间（UTC）',
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS red_flush_batch (
    id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    batch_no        VARCHAR(64)   NOT NULL COMMENT '整批红冲编号，全局唯一',
    seller_id       VARCHAR(64)   NOT NULL COMMENT '销方ID（批内各票一致）',
    buyer_id        VARCHAR(64)   NOT NULL COMMENT '购方ID（批内各票一致）',
    total_amount    DECIMAL(19,2) NOT NULL COMMENT '整批预占红冲金额合计，单位元',
    status          VARCHAR(16)   NOT NULL COMMENT '状态：PENDING待双方确认/ISSUED已生效开红票/TERMINATED已终止（拒绝或撤回）',
    version         INT           NOT NULL DEFAULT 1 COMMENT '乐观锁版本，自1起，每次确认/拒绝/撤回成功加1',
    created_at      TIMESTAMP     NOT NULL COMMENT '创建时间（UTC）',
    updated_at      TIMESTAMP     NOT NULL COMMENT '最近更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_red_batch_no (batch_no)
);

CREATE TABLE IF NOT EXISTS red_flush_batch_opinion (
    id         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    batch_id   BIGINT      NOT NULL COMMENT '所属整批ID',
    side       VARCHAR(8)  NOT NULL COMMENT '确认方：SELLER销方/BUYER购方',
    opinion    VARCHAR(8)  NOT NULL COMMENT '意见：AGREE同意/REJECT拒绝；单方同意只留意见不生效',
    created_at TIMESTAMP   NOT NULL COMMENT '意见时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_batch_opinion_side (batch_id, side)
);
