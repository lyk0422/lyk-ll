-- 生猪屠宰同步检疫与无害化处理服务建表脚本（H2 MySQL 兼容模式）。
-- 时间字段统一为 UTC ISO-8601 字符串（VARCHAR(40)），空值表示该环节尚未发生。

CREATE TABLE IF NOT EXISTS slaughter_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no VARCHAR(64) NOT NULL,
    species VARCHAR(32) NOT NULL,
    farm_origin VARCHAR(128) NOT NULL,
    head_count INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    declared_at VARCHAR(40) NOT NULL,
    pre_quarantined_at VARCHAR(40) NULL,
    judged_at VARCHAR(40) NULL,
    released_at VARCHAR(40) NULL,
    created_at VARCHAR(40) NOT NULL,
    CONSTRAINT uk_slaughter_batch_no UNIQUE (batch_no)
);
COMMENT ON TABLE slaughter_batch IS '屠宰批次主表，承载申报到出厂核销的状态流转';
COMMENT ON COLUMN slaughter_batch.batch_no IS '屠宰批次号，业务唯一';
COMMENT ON COLUMN slaughter_batch.species IS '畜种（合成数据，如 PIG）';
COMMENT ON COLUMN slaughter_batch.farm_origin IS '养殖场来源（合成数据）';
COMMENT ON COLUMN slaughter_batch.head_count IS '批次头只数，与头只台账行数一致';
COMMENT ON COLUMN slaughter_batch.status IS '批次状态：DECLARED 已申报 / PRE_QUARANTINED 宰前检疫通过 / INSPECTING 同步检验中 / PAUSED 漏检暂停 / JUDGED 判定分流完成 / RELEASED 出厂核销（终态）';
COMMENT ON COLUMN slaughter_batch.declared_at IS '最近一次申报时间（UTC ISO-8601），宰前检疫 6 小时有效期自该时刻起算';
COMMENT ON COLUMN slaughter_batch.pre_quarantined_at IS '宰前检疫通过时间（UTC ISO-8601），NULL 表示未检疫';
COMMENT ON COLUMN slaughter_batch.judged_at IS '判定分流完成时间（UTC ISO-8601），NULL 表示未判定';
COMMENT ON COLUMN slaughter_batch.released_at IS '出厂核销时间（UTC ISO-8601），NULL 表示未核销';
COMMENT ON COLUMN slaughter_batch.created_at IS '记录创建时间（UTC ISO-8601）';

CREATE TABLE IF NOT EXISTS head_ledger (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    head_no VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    inspection_result VARCHAR(32) NULL,
    judgment_category VARCHAR(32) NULL,
    high_temp BOOLEAN NULL,
    circulation_area VARCHAR(128) NULL,
    inventory_marked BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at VARCHAR(40) NOT NULL,
    CONSTRAINT uk_head_batch_no UNIQUE (batch_id, head_no)
);
COMMENT ON TABLE head_ledger IS '头只台账：每头只一行，记录同步检验、判定与库存标识，可复算';
COMMENT ON COLUMN head_ledger.batch_id IS '所属屠宰批次主键';
COMMENT ON COLUMN head_ledger.head_no IS '头只编号，批次内唯一';
COMMENT ON COLUMN head_ledger.status IS '头只状态：PENDING 待检 / INSPECTED 已同步检验';
COMMENT ON COLUMN head_ledger.inspection_result IS '同步检验结果（如 NORMAL/ABNORMAL），NULL 表示未检';
COMMENT ON COLUMN head_ledger.judgment_category IS '判定类别：QUALIFIED 合格 / CONDITIONAL 有条件食用 / RENDERING 化制 / DESTRUCTION 销毁，NULL 表示未判定';
COMMENT ON COLUMN head_ledger.high_temp IS '有条件食用是否标注高温处理，NULL 表示不适用';
COMMENT ON COLUMN head_ledger.circulation_area IS '有条件食用限定流通区域，NULL 表示不限定或不适用';
COMMENT ON COLUMN head_ledger.inventory_marked IS '库存标识：判定合格的头只在判定事务内同步置 TRUE';
COMMENT ON COLUMN head_ledger.updated_at IS '行最近更新时间（UTC ISO-8601）';

CREATE TABLE IF NOT EXISTS judgment_detail (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    head_no VARCHAR(64) NOT NULL,
    category VARCHAR(32) NOT NULL,
    high_temp BOOLEAN NOT NULL DEFAULT FALSE,
    circulation_area VARCHAR(128) NULL,
    created_at VARCHAR(40) NOT NULL,
    CONSTRAINT uk_judgment_head UNIQUE (batch_id, head_no)
);
COMMENT ON TABLE judgment_detail IS '判定明细：每头只一条判定记录，与台账共同支撑复算';
COMMENT ON COLUMN judgment_detail.batch_id IS '所属屠宰批次主键';
COMMENT ON COLUMN judgment_detail.head_no IS '头只编号';
COMMENT ON COLUMN judgment_detail.category IS '判定类别：QUALIFIED/CONDITIONAL/RENDERING/DESTRUCTION';
COMMENT ON COLUMN judgment_detail.high_temp IS '有条件食用是否标注高温处理';
COMMENT ON COLUMN judgment_detail.circulation_area IS '有条件食用限定流通区域，NULL 表示不适用';
COMMENT ON COLUMN judgment_detail.created_at IS '判定登记时间（UTC ISO-8601）';

CREATE TABLE IF NOT EXISTS harmless_receipt (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    receipt_no VARCHAR(64) NOT NULL,
    category VARCHAR(32) NOT NULL,
    head_count INT NOT NULL,
    processor VARCHAR(128) NOT NULL,
    late BOOLEAN NOT NULL DEFAULT FALSE,
    received_at VARCHAR(40) NOT NULL,
    CONSTRAINT uk_receipt_no UNIQUE (batch_id, receipt_no)
);
COMMENT ON TABLE harmless_receipt IS '无害化处理回执：化制/销毁判定头只的处理闭环凭证，与判定数勾稽';
COMMENT ON COLUMN harmless_receipt.batch_id IS '所属屠宰批次主键';
COMMENT ON COLUMN harmless_receipt.receipt_no IS '回执编号，批次内唯一';
COMMENT ON COLUMN harmless_receipt.category IS '回执对应判定类别：RENDERING 化制 / DESTRUCTION 销毁';
COMMENT ON COLUMN harmless_receipt.head_count IS '回执载明处理头只数，出厂核销时须与同类判定数恰等';
COMMENT ON COLUMN harmless_receipt.processor IS '无害化处理单位（合成数据）';
COMMENT ON COLUMN harmless_receipt.late IS '是否核销后迟到的回执，TRUE 仅记录不参与勾稽';
COMMENT ON COLUMN harmless_receipt.received_at IS '回执登记时间（UTC ISO-8601）';

CREATE TABLE IF NOT EXISTS late_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    detail VARCHAR(512) NOT NULL,
    created_at VARCHAR(40) NOT NULL
);
COMMENT ON TABLE late_event IS '迟到事件台账：批次核销终态后到达的回执等事件，仅记录并告警，不改变批次状态';
COMMENT ON COLUMN late_event.batch_id IS '所属屠宰批次主键';
COMMENT ON COLUMN late_event.event_type IS '事件类型，如 LATE_RECEIPT 迟到回执';
COMMENT ON COLUMN late_event.detail IS '事件明细（合成数据）';
COMMENT ON COLUMN late_event.created_at IS '事件记录时间（UTC ISO-8601）';

CREATE TABLE IF NOT EXISTS idempotency_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(128) NOT NULL,
    endpoint VARCHAR(128) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    response_body CLOB NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    CONSTRAINT uk_request_id UNIQUE (request_id)
);
COMMENT ON TABLE idempotency_record IS '幂等登记：requestId 全局唯一，指纹覆盖 requestId 与全部业务字段，同键同参返回首次结果';
COMMENT ON COLUMN idempotency_record.request_id IS '调用方请求标识，全局唯一';
COMMENT ON COLUMN idempotency_record.endpoint IS '受理接口标识';
COMMENT ON COLUMN idempotency_record.fingerprint IS '请求指纹：requestId 与全部业务字段的 SHA-256';
COMMENT ON COLUMN idempotency_record.response_body IS '首次受理的响应报文（JSON），同键同参重放时原样返回';
COMMENT ON COLUMN idempotency_record.created_at IS '登记时间（UTC ISO-8601）';
