-- 档案库房虫情分区处置：数据库结构（H2，MODE=MySQL）
-- 时间列一律以 UTC 存储（LocalDateTime），由应用层 Clock 统一提供当前时间。

CREATE TABLE archive_zone (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(64) NOT NULL,
    adjacent_codes VARCHAR(255) NOT NULL DEFAULT '',
    CONSTRAINT uk_archive_zone_code UNIQUE (code)
);
COMMENT ON TABLE archive_zone IS '档案库区';
COMMENT ON COLUMN archive_zone.id IS '主键';
COMMENT ON COLUMN archive_zone.code IS '库区编码，唯一';
COMMENT ON COLUMN archive_zone.name IS '库区名称';
COMMENT ON COLUMN archive_zone.adjacent_codes IS '相邻库区编码，逗号分隔；空串表示无相邻库区';

CREATE TABLE pest_case (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    zone_id BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL,
    reporter VARCHAR(64) NOT NULL,
    description VARCHAR(512) NULL,
    reported_at TIMESTAMP NOT NULL,
    confirmed_at TIMESTAMP NULL,
    treated_at TIMESTAMP NULL,
    release_applied_at TIMESTAMP NULL,
    disinfect_confirmed_at TIMESTAMP NULL,
    storage_confirmed_at TIMESTAMP NULL,
    released_at TIMESTAMP NULL,
    CONSTRAINT fk_pest_case_zone FOREIGN KEY (zone_id) REFERENCES archive_zone (id)
);
COMMENT ON TABLE pest_case IS '虫情处置链：上报-确认-投放-监测-解除全过程';
COMMENT ON COLUMN pest_case.zone_id IS '所属库区';
COMMENT ON COLUMN pest_case.status IS '状态：REPORTED 已上报；CONFIRMED 已确认（处置中，禁止该库区出库）；TREATED 已投放（14 天监测期）；PENDING_REPLACEMENT 待补投（批号召回后存在未补齐投放）；RELEASE_APPLIED 已申请解除；RELEASED 已解除';
COMMENT ON COLUMN pest_case.reporter IS '虫情上报人';
COMMENT ON COLUMN pest_case.description IS '虫情描述，可空';
COMMENT ON COLUMN pest_case.reported_at IS '上报时间（UTC）';
COMMENT ON COLUMN pest_case.confirmed_at IS '确认时间（UTC），确认后库区进入处置状态';
COMMENT ON COLUMN pest_case.treated_at IS '最近一次治理投放生效时间（UTC），监测期起点';
COMMENT ON COLUMN pest_case.release_applied_at IS '解除申请时间（UTC）';
COMMENT ON COLUMN pest_case.disinfect_confirmed_at IS '消杀方确认时间（UTC），空表示未确认';
COMMENT ON COLUMN pest_case.storage_confirmed_at IS '库房方确认时间（UTC），空表示未确认';
COMMENT ON COLUMN pest_case.released_at IS '解除完成时间（UTC），空表示尚未解除';

CREATE TABLE zone_active_case (
    zone_id BIGINT PRIMARY KEY,
    case_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_active_case_case FOREIGN KEY (case_id) REFERENCES pest_case (id)
);
COMMENT ON TABLE zone_active_case IS '进行中处置链占位表：zone_id 主键保证同库区同时刻仅一条进行中处置链，解除时删除';
COMMENT ON COLUMN zone_active_case.zone_id IS '库区主键';
COMMENT ON COLUMN zone_active_case.case_id IS '进行中的处置链';
COMMENT ON COLUMN zone_active_case.created_at IS '占位创建时间（UTC）';

CREATE TABLE outbound_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    zone_id BIGINT NOT NULL,
    item_name VARCHAR(128) NOT NULL,
    borrower VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_outbound_zone FOREIGN KEY (zone_id) REFERENCES archive_zone (id)
);
COMMENT ON TABLE outbound_record IS '档案出库登记；处置状态库区禁止登记，其它库区不受影响';
COMMENT ON COLUMN outbound_record.zone_id IS '出库库区';
COMMENT ON COLUMN outbound_record.item_name IS '出库档案名称';
COMMENT ON COLUMN outbound_record.borrower IS '借阅人';
COMMENT ON COLUMN outbound_record.created_at IS '登记时间（UTC）';

CREATE TABLE pesticide (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    quantity DECIMAL(10,2) NOT NULL,
    unit VARCHAR(16) NOT NULL,
    safety_line DECIMAL(10,2) NOT NULL,
    expiry_date DATE NOT NULL
);
COMMENT ON TABLE pesticide IS '药剂库存';
COMMENT ON COLUMN pesticide.name IS '药剂名称';
COMMENT ON COLUMN pesticide.quantity IS '当前库存量，单位见 unit';
COMMENT ON COLUMN pesticide.unit IS '计量单位（如 克/毫升）';
COMMENT ON COLUMN pesticide.safety_line IS '安全库存线，低于该值自动生成采购建议';
COMMENT ON COLUMN pesticide.expiry_date IS '有效期截止日（含当日），过期药剂拒绝登记投放';

CREATE TABLE pesticide_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    pesticide_id BIGINT NOT NULL,
    batch_no VARCHAR(64) NOT NULL,
    quantity DECIMAL(10,2) NOT NULL,
    expiry_date DATE NOT NULL,
    status VARCHAR(16) NOT NULL,
    version INT NOT NULL,
    recall_reason VARCHAR(256) NULL,
    recalled_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_batch_pesticide_no UNIQUE (pesticide_id, batch_no),
    CONSTRAINT fk_batch_pesticide FOREIGN KEY (pesticide_id) REFERENCES pesticide (id)
);
COMMENT ON TABLE pesticide_batch IS '药剂批号：同一药剂按批号分开管理库存与有效期；召回以批号为粒度，版本号用于乐观并发控制';
COMMENT ON COLUMN pesticide_batch.pesticide_id IS '所属药剂';
COMMENT ON COLUMN pesticide_batch.batch_no IS '批号，同一药剂内唯一';
COMMENT ON COLUMN pesticide_batch.quantity IS '该批号当前库存量，单位同药剂 unit';
COMMENT ON COLUMN pesticide_batch.expiry_date IS '该批号有效期截止日（含当日），过期批号拒绝投放与补投';
COMMENT ON COLUMN pesticide_batch.status IS '状态：ACTIVE 可用；RECALLED 已召回（不可撤回，禁止该批号新投放与补投）';
COMMENT ON COLUMN pesticide_batch.version IS '乐观锁版本：1 起，召回成功加 1，召回请求须携带匹配的 expectedVersion';
COMMENT ON COLUMN pesticide_batch.recall_reason IS '召回原因，空表示未召回';
COMMENT ON COLUMN pesticide_batch.recalled_at IS '召回生效时间（UTC），空表示未召回';
COMMENT ON COLUMN pesticide_batch.created_at IS '批号建档时间（UTC）';

CREATE TABLE treatment_placement (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    case_id BIGINT NOT NULL,
    pesticide_id BIGINT NOT NULL,
    batch_id BIGINT NULL,
    dosage DECIMAL(10,2) NOT NULL,
    placement_point VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    recall_status VARCHAR(16) NOT NULL DEFAULT 'NONE',
    replaces_placement_id BIGINT NULL,
    version INT NOT NULL DEFAULT 1,
    first_registrar VARCHAR(64) NOT NULL,
    first_registered_at TIMESTAMP NOT NULL,
    second_registrar VARCHAR(64) NULL,
    second_registered_at TIMESTAMP NULL,
    placed_at TIMESTAMP NULL,
    CONSTRAINT fk_placement_case FOREIGN KEY (case_id) REFERENCES pest_case (id),
    CONSTRAINT fk_placement_pesticide FOREIGN KEY (pesticide_id) REFERENCES pesticide (id),
    CONSTRAINT fk_placement_batch FOREIGN KEY (batch_id) REFERENCES pesticide_batch (id),
    CONSTRAINT fk_placement_replaces FOREIGN KEY (replaces_placement_id)
        REFERENCES treatment_placement (id)
);
COMMENT ON TABLE treatment_placement IS '治理投放记录，须双人登记方可生效；生效与库存扣减在同一事务内完成；批号、用量与双人签认在生效时冻结，召回后原记录保留为证据不覆写';
COMMENT ON COLUMN treatment_placement.case_id IS '所属处置链';
COMMENT ON COLUMN treatment_placement.pesticide_id IS '投放药剂';
COMMENT ON COLUMN treatment_placement.batch_id IS '投放批号，空表示未启用批号管理的历史投放（保留原语义，不参与召回）';
COMMENT ON COLUMN treatment_placement.dosage IS '用量，单位同药剂 unit';
COMMENT ON COLUMN treatment_placement.placement_point IS '投放点位';
COMMENT ON COLUMN treatment_placement.status IS '状态：PENDING 单人已登记待复核（不生效）；EFFECTIVE 双人登记完成已生效';
COMMENT ON COLUMN treatment_placement.recall_status IS '召回状态：NONE 未召回；RECALLED 批号已召回待补投（证据保留，不得用于观察解除）；REPLACED 已召回且已补投（证据保留）';
COMMENT ON COLUMN treatment_placement.replaces_placement_id IS '补投来源：本记录一对一替代的被召回投放，空表示非补投记录';
COMMENT ON COLUMN treatment_placement.version IS '乐观锁版本：1 起，被补投成功加 1，补投请求须携带匹配的 expectedVersion';
COMMENT ON COLUMN treatment_placement.first_registrar IS '第一登记人';
COMMENT ON COLUMN treatment_placement.first_registered_at IS '第一登记时间（UTC）';
COMMENT ON COLUMN treatment_placement.second_registrar IS '第二登记人，须与第一登记人不同；空表示尚未复核';
COMMENT ON COLUMN treatment_placement.second_registered_at IS '第二登记时间（UTC），空表示尚未复核';
COMMENT ON COLUMN treatment_placement.placed_at IS '生效时间（UTC），空表示未生效';

CREATE TABLE pest_trace (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    case_id BIGINT NOT NULL,
    description VARCHAR(256) NULL,
    found_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_trace_case FOREIGN KEY (case_id) REFERENCES pest_case (id)
);
COMMENT ON TABLE pest_trace IS '监测期新增虫迹记录；投放后存在虫迹时不得申请解除';
COMMENT ON COLUMN pest_trace.case_id IS '所属处置链';
COMMENT ON COLUMN pest_trace.description IS '虫迹描述，可空';
COMMENT ON COLUMN pest_trace.found_at IS '发现时间（UTC）';

CREATE TABLE purchase_suggestion (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    pesticide_id BIGINT NOT NULL,
    suggested_quantity DECIMAL(10,2) NOT NULL,
    reason VARCHAR(256) NOT NULL,
    placement_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_suggestion_pesticide FOREIGN KEY (pesticide_id) REFERENCES pesticide (id),
    CONSTRAINT fk_suggestion_placement FOREIGN KEY (placement_id) REFERENCES treatment_placement (id)
);
COMMENT ON TABLE purchase_suggestion IS '药剂采购建议：库存低于安全线时自动生成，并与最近投放记录勾稽';
COMMENT ON COLUMN purchase_suggestion.pesticide_id IS '涉及药剂';
COMMENT ON COLUMN purchase_suggestion.suggested_quantity IS '建议采购量（补足至安全线两倍）';
COMMENT ON COLUMN purchase_suggestion.reason IS '生成原因';
COMMENT ON COLUMN purchase_suggestion.placement_id IS '勾稽的最近投放记录';
COMMENT ON COLUMN purchase_suggestion.created_at IS '生成时间（UTC）';

CREATE TABLE preventive_evaluation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    zone_codes VARCHAR(255) NOT NULL,
    trigger_case_id BIGINT NOT NULL,
    reason VARCHAR(256) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_evaluation_case FOREIGN KEY (trigger_case_id) REFERENCES pest_case (id)
);
COMMENT ON TABLE preventive_evaluation IS '整体预防性消杀评估：相邻库区 90 日内连续第 2 次虫情时触发，恰第 2 次触发';
COMMENT ON COLUMN preventive_evaluation.zone_codes IS '涉及相邻库区编码，逗号分隔，按编码排序';
COMMENT ON COLUMN preventive_evaluation.trigger_case_id IS '触发的虫情处置链';
COMMENT ON COLUMN preventive_evaluation.reason IS '触发原因';
COMMENT ON COLUMN preventive_evaluation.created_at IS '触发时间（UTC）';

CREATE TABLE batch_recall (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    reason VARCHAR(256) NOT NULL,
    operator VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_recall_batch FOREIGN KEY (batch_id) REFERENCES pesticide_batch (id)
);
COMMENT ON TABLE batch_recall IS '批号召回事件：召回一旦生效不得撤回，同事务内阻断该批号新投放并联动态处置链';
COMMENT ON COLUMN batch_recall.batch_id IS '被召回批号';
COMMENT ON COLUMN batch_recall.reason IS '召回原因';
COMMENT ON COLUMN batch_recall.operator IS '召回操作人';
COMMENT ON COLUMN batch_recall.created_at IS '召回生效时间（UTC）';

CREATE TABLE recall_impact (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    recall_id BIGINT NOT NULL,
    placement_id BIGINT NOT NULL,
    case_id BIGINT NOT NULL,
    impact_type VARCHAR(24) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_impact_recall FOREIGN KEY (recall_id) REFERENCES batch_recall (id),
    CONSTRAINT fk_impact_placement FOREIGN KEY (placement_id) REFERENCES treatment_placement (id),
    CONSTRAINT fk_impact_case FOREIGN KEY (case_id) REFERENCES pest_case (id)
);
COMMENT ON TABLE recall_impact IS '召回关联处置链影响记录：在途链转待补投，已解除归档链仅追加风险不重开';
COMMENT ON COLUMN recall_impact.recall_id IS '所属召回事件';
COMMENT ON COLUMN recall_impact.placement_id IS '被召回批号的生效投放（证据保留）';
COMMENT ON COLUMN recall_impact.case_id IS '关联处置链';
COMMENT ON COLUMN recall_impact.impact_type IS '影响类型：PENDING_REPLACEMENT 在途链转待补投；ARCHIVED_RISK 已解除归档链仅追加风险';
COMMENT ON COLUMN recall_impact.created_at IS '影响记录时间（UTC）';

CREATE TABLE idempotency_record (
    request_id VARCHAR(64) PRIMARY KEY,
    fingerprint VARCHAR(64) NOT NULL,
    response_body CLOB NOT NULL,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE idempotency_record IS '写操作幂等记录：request_id 唯一；指纹覆盖 requestId 与全部业务字段，同键同参返回首次结果，同键异参拒绝';
COMMENT ON COLUMN idempotency_record.request_id IS '请求标识，唯一';
COMMENT ON COLUMN idempotency_record.fingerprint IS 'SHA-256 指纹（requestId + 全部业务字段）';
COMMENT ON COLUMN idempotency_record.response_body IS '首次成功的响应报文（JSON）';
COMMENT ON COLUMN idempotency_record.created_at IS '记录创建时间（UTC）';
