-- 数电发票红冲与授信额度恢复服务建表脚本（H2 MySQL 兼容模式）
-- 金额单位：元；时间均为 Asia/Shanghai 本地时间；空值含义见各列注释。

CREATE TABLE IF NOT EXISTS credit_account (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    seller_tax_no VARCHAR(32) NOT NULL COMMENT '销方纳税人识别号，全局唯一',
    total_limit DECIMAL(19,2) NOT NULL COMMENT '授信总额度，单位：元',
    blue_unarchived_amount DECIMAL(19,2) NOT NULL DEFAULT 0 COMMENT '未归档蓝字发票占用金额，单位：元',
    red_confirmed_amount DECIMAL(19,2) NOT NULL DEFAULT 0 COMMENT '已确认红字发票累计金额，单位：元',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '行版本号，每次变更加一',
    created_at TIMESTAMP NOT NULL COMMENT '创建时间',
    updated_at TIMESTAMP NOT NULL COMMENT '更新时间',
    CONSTRAINT uk_credit_account_seller UNIQUE (seller_tax_no)
) COMMENT '开票方授信额度账户；可用额度=总额度-未归档蓝票金额+已确认红票金额';

CREATE TABLE IF NOT EXISTS credit_ledger (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    seller_tax_no VARCHAR(32) NOT NULL COMMENT '销方纳税人识别号',
    biz_type VARCHAR(32) NOT NULL COMMENT '业务类型：ISSUE开票/ARCHIVE归档/RED_CONFIRM红票确认',
    biz_no VARCHAR(64) NOT NULL COMMENT '关联业务单号（发票号码）',
    delta_blue_unarchived DECIMAL(19,2) NOT NULL DEFAULT 0 COMMENT '未归档蓝票金额变动，单位：元，可为负',
    delta_red_confirmed DECIMAL(19,2) NOT NULL DEFAULT 0 COMMENT '已确认红票金额变动，单位：元，可为负',
    blue_unarchived_after DECIMAL(19,2) NOT NULL COMMENT '变动后未归档蓝票金额，单位：元',
    red_confirmed_after DECIMAL(19,2) NOT NULL COMMENT '变动后已确认红票金额，单位：元',
    created_at TIMESTAMP NOT NULL COMMENT '入账时间'
) COMMENT '授信额度台账，可据此复算额度占用';

CREATE TABLE IF NOT EXISTS invoice (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求号，开票方保证唯一',
    fingerprint VARCHAR(64) NOT NULL COMMENT '幂等指纹：requestId 与全部业务字段的 SHA-256',
    invoice_no VARCHAR(32) NOT NULL COMMENT '发票号码，全局唯一',
    direction VARCHAR(8) NOT NULL COMMENT '发票方向：BLUE蓝字/RED红字',
    status VARCHAR(16) NOT NULL COMMENT '状态：ISSUED已开具/DELIVERED已交付/RED_FLUSHED已全额红冲(终态)/ARCHIVED已归档(终态)',
    seller_tax_no VARCHAR(32) NOT NULL COMMENT '销方纳税人识别号',
    buyer_tax_no VARCHAR(32) NOT NULL COMMENT '购方纳税人识别号',
    buyer_booked TINYINT NOT NULL DEFAULT 0 COMMENT '购方是否已入账：0否/1是',
    total_amount DECIMAL(19,2) NOT NULL COMMENT '价税合计金额，单位：元',
    red_flushed_amount DECIMAL(19,2) NOT NULL DEFAULT 0 COMMENT '累计已红冲金额，单位：元',
    original_invoice_id BIGINT NULL COMMENT '红票引用的原蓝票主键；蓝票为 NULL',
    info_table_id BIGINT NULL COMMENT '红票来源的红字信息表主键；蓝票为 NULL',
    created_at TIMESTAMP NOT NULL COMMENT '开具时间',
    updated_at TIMESTAMP NOT NULL COMMENT '更新时间',
    CONSTRAINT uk_invoice_request UNIQUE (request_id),
    CONSTRAINT uk_invoice_no UNIQUE (invoice_no)
) COMMENT '发票主表，蓝字与红字发票同表，红票保留原票引用形成红冲链';

CREATE TABLE IF NOT EXISTS invoice_line (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    invoice_id BIGINT NOT NULL COMMENT '所属发票主键',
    line_no INT NOT NULL COMMENT '行号，从 1 开始',
    item_name VARCHAR(128) NOT NULL COMMENT '项目名称',
    quantity DECIMAL(19,4) NOT NULL COMMENT '数量',
    unit_price DECIMAL(19,4) NOT NULL COMMENT '单价，单位：元',
    amount DECIMAL(19,2) NOT NULL COMMENT '行金额，单位：元',
    red_flushed_amount DECIMAL(19,2) NOT NULL DEFAULT 0 COMMENT '本行累计已红冲金额，单位：元',
    CONSTRAINT uk_invoice_line UNIQUE (invoice_id, line_no)
) COMMENT '发票行项目，部分红冲按行拆分累计';

CREATE TABLE IF NOT EXISTS red_info_table (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求号',
    fingerprint VARCHAR(64) NOT NULL COMMENT '幂等指纹：requestId 与全部业务字段的 SHA-256',
    info_no VARCHAR(40) NOT NULL COMMENT '红字信息表编号，全局唯一',
    original_invoice_id BIGINT NOT NULL COMMENT '原蓝字发票主键',
    red_type VARCHAR(8) NOT NULL COMMENT '红冲类型：FULL全额/PARTIAL部分',
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING待确认/CONFIRMED已确认并开具红票/SUSPENDED意见不一致挂起',
    seller_tax_no VARCHAR(32) NOT NULL COMMENT '销方纳税人识别号',
    buyer_tax_no VARCHAR(32) NOT NULL COMMENT '购方纳税人识别号',
    total_amount DECIMAL(19,2) NOT NULL COMMENT '本次红冲金额，单位：元',
    seller_confirmed TINYINT NOT NULL DEFAULT 0 COMMENT '销方是否已确认：0否/1是',
    buyer_confirmed TINYINT NOT NULL DEFAULT 0 COMMENT '购方是否已确认：0否/1是',
    red_invoice_id BIGINT NULL COMMENT '确认后开具的红字发票主键；未开具为 NULL',
    created_at TIMESTAMP NOT NULL COMMENT '创建时间',
    updated_at TIMESTAMP NOT NULL COMMENT '更新时间',
    CONSTRAINT uk_red_info_request UNIQUE (request_id),
    CONSTRAINT uk_red_info_no UNIQUE (info_no)
) COMMENT '红字信息表，须购销双方在同一事务内先后确认方可开具红票';

CREATE TABLE IF NOT EXISTS red_info_line (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    info_table_id BIGINT NOT NULL COMMENT '所属红字信息表主键',
    original_line_id BIGINT NOT NULL COMMENT '原蓝票行项目主键',
    amount DECIMAL(19,2) NOT NULL COMMENT '本行红冲金额，单位：元',
    CONSTRAINT uk_red_info_line UNIQUE (info_table_id, original_line_id)
) COMMENT '红字信息表行项目，仅部分红冲使用';

CREATE TABLE IF NOT EXISTS red_confirmation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    info_table_id BIGINT NOT NULL COMMENT '所属红字信息表主键',
    party VARCHAR(8) NOT NULL COMMENT '确认方：SELLER销方/BUYER购方',
    opinion VARCHAR(8) NOT NULL COMMENT '确认意见：CONFIRM同意/REJECT拒绝',
    comment VARCHAR(256) NULL COMMENT '确认备注，可空',
    confirmed_at TIMESTAMP NOT NULL COMMENT '确认时间'
) COMMENT '红字信息表确认轨迹，全程留痕可复算';
