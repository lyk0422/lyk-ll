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

-- ========== 风险抽检层（合成规则，不用于生产） ==========

CREATE TABLE IF NOT EXISTS risk_binding (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    version INT NOT NULL,
    level VARCHAR(32) NOT NULL,
    rule_version VARCHAR(64) NOT NULL,
    current_sequence_no BIGINT NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    updated_at VARCHAR(40) NOT NULL,
    CONSTRAINT uk_risk_binding_batch UNIQUE (batch_id)
);
COMMENT ON TABLE risk_binding IS '风险绑定：受控批次宰前检疫合格后绑定合成风险等级，每批仅一行，承载乐观版本与当前清单序号';
COMMENT ON COLUMN risk_binding.batch_id IS '所属屠宰批次主键';
COMMENT ON COLUMN risk_binding.version IS '风险版本：首次绑定为 1，每次成功变更（等级变更/接受新结果）加 1，同结果重复不增加，迟到事件不增加';
COMMENT ON COLUMN risk_binding.level IS '当前风险等级：NORMAL 普通抽检10% / TIGHTENED 加严抽检30%';
COMMENT ON COLUMN risk_binding.rule_version IS '当前合成风险规则版本（随每次清单冻结更新，仅合成数据）';
COMMENT ON COLUMN risk_binding.current_sequence_no IS '当前清单序号：同一批次只一个当前清单，原子扩充沿用原序号，补充清单序号加 1';
COMMENT ON COLUMN risk_binding.created_at IS '绑定时间（UTC ISO-8601）';
COMMENT ON COLUMN risk_binding.updated_at IS '最近一次成功风险变更时间（UTC ISO-8601）';

CREATE TABLE IF NOT EXISTS risk_checklist (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    sequence_no BIGINT NOT NULL,
    kind VARCHAR(32) NOT NULL,
    level VARCHAR(32) NOT NULL,
    rule_version VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    frozen_declared CLOB NOT NULL,
    frozen_samples CLOB NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    completed_at VARCHAR(40) NULL,
    CONSTRAINT uk_risk_checklist_seq UNIQUE (batch_id, sequence_no)
);
COMMENT ON TABLE risk_checklist IS '风险抽检清单：冻结编号清单、风险规则版本及申报头只集合，同一批次只一个当前清单';
COMMENT ON COLUMN risk_checklist.batch_id IS '所属屠宰批次主键';
COMMENT ON COLUMN risk_checklist.sequence_no IS '清单序号，批次内从 1 递增：原子扩充沿用原序号，补充清单另开新序号';
COMMENT ON COLUMN risk_checklist.kind IS '清单类型：INITIAL 首份 / EXPANDED 提高等级原子扩充 / SUPPLEMENTARY 已完成后新增风险补充清单';
COMMENT ON COLUMN risk_checklist.level IS '清单冻结时的风险等级：NORMAL/TIGHTENED';
COMMENT ON COLUMN risk_checklist.rule_version IS '清单冻结时的合成风险规则版本';
COMMENT ON COLUMN risk_checklist.status IS '清单状态：OPEN 进行中（可提交结果）/ COMPLETED 已完成（全部冻结样本有结果，不可改）';
COMMENT ON COLUMN risk_checklist.frozen_declared IS '冻结申报头只集合（JSON 数组，字典序），与头只台账集合一致';
COMMENT ON COLUMN risk_checklist.frozen_samples IS '冻结抽检编号集合（JSON 数组，字典序）：申报集合字典序前 ceil(总数×比例) 只且至少 1 只';
COMMENT ON COLUMN risk_checklist.created_at IS '清单冻结时间（UTC ISO-8601）';
COMMENT ON COLUMN risk_checklist.completed_at IS '清单完成时间（UTC ISO-8601，最后一只结果登记时刻），NULL 表示未完成';

CREATE TABLE IF NOT EXISTS risk_sample (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    sequence_no BIGINT NOT NULL,
    head_no VARCHAR(64) NOT NULL,
    result VARCHAR(16) NOT NULL,
    submitted_at VARCHAR(40) NOT NULL,
    CONSTRAINT uk_risk_sample UNIQUE (batch_id, sequence_no, head_no)
);
COMMENT ON TABLE risk_sample IS '抽检逐只结果：每个冻结样本至多一条结果，同结果重复不新增，异结果冲突，清单外编号拒绝';
COMMENT ON COLUMN risk_sample.batch_id IS '所属屠宰批次主键';
COMMENT ON COLUMN risk_sample.sequence_no IS '所属抽检清单序号';
COMMENT ON COLUMN risk_sample.head_no IS '头只编号，须属于同序号清单冻结抽检集合';
COMMENT ON COLUMN risk_sample.result IS '抽检结果：NEGATIVE 阴性 / POSITIVE 阳性（任一阳性整批专项隔离）';
COMMENT ON COLUMN risk_sample.submitted_at IS '结果提交业务精确时刻（UTC ISO-8601）';

CREATE TABLE IF NOT EXISTS risk_change_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    from_level VARCHAR(32) NULL,
    to_level VARCHAR(32) NOT NULL,
    from_rule_version VARCHAR(64) NULL,
    to_rule_version VARCHAR(64) NOT NULL,
    sequence_no BIGINT NOT NULL,
    from_version INT NOT NULL,
    to_version INT NOT NULL,
    created_at VARCHAR(40) NOT NULL
);
COMMENT ON TABLE risk_change_event IS '风险变更证据台账：绑定、提高等级原子扩充、补充清单逐次留痕，支撑复算与审计';
COMMENT ON COLUMN risk_change_event.batch_id IS '所属屠宰批次主键';
COMMENT ON COLUMN risk_change_event.event_type IS '事件类型：BIND 首次绑定 / EXPAND 原子扩充 / SUPPLEMENT 补充清单';
COMMENT ON COLUMN risk_change_event.from_level IS '变更前风险等级，BIND 时为 NULL';
COMMENT ON COLUMN risk_change_event.to_level IS '变更后风险等级';
COMMENT ON COLUMN risk_change_event.from_rule_version IS '变更前规则版本，BIND 时为 NULL';
COMMENT ON COLUMN risk_change_event.to_rule_version IS '变更后规则版本';
COMMENT ON COLUMN risk_change_event.sequence_no IS '事件关联清单序号（扩充沿用原序号，补充清单为新序号）';
COMMENT ON COLUMN risk_change_event.from_version IS '变更前风险版本，BIND 时为 0';
COMMENT ON COLUMN risk_change_event.to_version IS '变更后风险版本';
COMMENT ON COLUMN risk_change_event.created_at IS '事件记录时间（UTC ISO-8601）';

-- ========== 分车出厂层（同一合成屠宰批次多车出厂，按头只预占与核销） ==========

CREATE TABLE IF NOT EXISTS truck_manifest (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    truck_no VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    head_count INT NOT NULL,
    frozen_heads CLOB NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    revoked_at VARCHAR(40) NULL,
    dispatched_at VARCHAR(40) NULL,
    CONSTRAINT uk_truck_manifest_no UNIQUE (batch_id, truck_no)
);
COMMENT ON TABLE truck_manifest IS '分车出厂清单：每车一行，创建即全量预占并冻结头只集合，版本自 1 起，撤销/发车成功加 1';
COMMENT ON COLUMN truck_manifest.batch_id IS '所属屠宰批次主键';
COMMENT ON COLUMN truck_manifest.truck_no IS '合成车号，批次内唯一（含已撤销车单，车号不可复用）';
COMMENT ON COLUMN truck_manifest.version IS '车单乐观版本：创建为 1；撤销或发车成功加 1；expectedVersion 不符返回 409';
COMMENT ON COLUMN truck_manifest.status IS '车单状态：RESERVED 已预占未发车（仅此态可撤销/发车）/ RELEASED 已发车核销（终态不可改）/ REVOKED 已整体撤销释放（终态不可改）';
COMMENT ON COLUMN truck_manifest.head_count IS '冻结头只数，1 至 50，与 frozen_heads 数组长度一致';
COMMENT ON COLUMN truck_manifest.frozen_heads IS '冻结合格头只标识集合（JSON 数组，创建时给定顺序），须全部属于本批 QUALIFIED 判定明细';
COMMENT ON COLUMN truck_manifest.created_at IS '车单创建（清单冻结）业务时刻（UTC ISO-8601）';
COMMENT ON COLUMN truck_manifest.revoked_at IS '整体撤销时刻（UTC ISO-8601），NULL 表示未撤销';
COMMENT ON COLUMN truck_manifest.dispatched_at IS '发车核销时刻（UTC ISO-8601），NULL 表示未发车';

CREATE TABLE IF NOT EXISTS truck_manifest_head (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    manifest_id BIGINT NOT NULL,
    head_no VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    CONSTRAINT uk_truck_manifest_head UNIQUE (batch_id, head_no)
);
COMMENT ON TABLE truck_manifest_head IS '车单头只活跃占用：同一批次同一头只至多一行，未撤销车单争用同一头只时由唯一约束只放行一方；撤销删行释放，发车置 RELEASED 永久占用';
COMMENT ON COLUMN truck_manifest_head.batch_id IS '所属屠宰批次主键';
COMMENT ON COLUMN truck_manifest_head.manifest_id IS '占用所属车单主键（truck_manifest.id）';
COMMENT ON COLUMN truck_manifest_head.head_no IS '头只编号，批次内唯一活跃占用，跨批占用由所属批次隔离';
COMMENT ON COLUMN truck_manifest_head.status IS '占用状态：RESERVED 预占中（车单撤销时整行删除释放）/ RELEASED 随车核销已出厂（保留占用防止再次装车）';
COMMENT ON COLUMN truck_manifest_head.created_at IS '占用建立时间（UTC ISO-8601）';

CREATE INDEX IF NOT EXISTS idx_truck_head_manifest ON truck_manifest_head(manifest_id);
