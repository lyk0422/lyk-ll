-- 集装箱残损检验与修理放行服务建表脚本（H2 MODE=MySQL，合成数据）
-- 时间口径：日期均为 UTC 自然日；金额单位为箱值同一货币单位，保留两位小数。

create table if not exists iicl_code (
    code varchar(16) not null primary key,
    description varchar(128) not null
);
comment on table iicl_code is '合成 IICL 残损代码表（非真实 IICL 数据，仅本地演示）';
comment on column iicl_code.code is '残损代码，如 DN/BR/CT';
comment on column iicl_code.description is '残损代码中文说明';

merge into iicl_code key(code) values
    ('DN', '凹痕'),
    ('BR', '弯曲变形'),
    ('CT', '割伤破口'),
    ('HO', '穿孔'),
    ('RS', '锈蚀'),
    ('PT', '涂层脱落'),
    ('CR', '裂纹'),
    ('MS', '构件缺失');

create table if not exists damage_case (
    id bigint auto_increment primary key,
    container_no varchar(20) not null,
    container_value decimal(19,2) not null,
    status varchar(32) not null,
    determination varchar(16) null,
    rework_count int not null default 0,
    active_key varchar(20) null,
    created_at timestamp not null,
    updated_at timestamp not null,
    closed_at timestamp null
);
comment on table damage_case is '残损案主表；status 为流转状态，结案（RELEASED/TOTAL_LOSS）为终态';
comment on column damage_case.container_no is '箱号（合成数据）';
comment on column damage_case.container_value is '箱值，货币单位与修理费一致';
comment on column damage_case.status is 'REGISTERED/SURVEYED/ESTIMATED/APPROVED/REWORK/REPAIR_DONE/REPAIR_PASSED/TOTAL_LOSS_PENDING/RELEASED/TOTAL_LOSS';
comment on column damage_case.determination is '当前唯一判定：REPAIR 或 TOTAL_LOSS；全损与修理不并存，NULL 表示未估价';
comment on column damage_case.rework_count is '已返修次数，最多 1 次';
comment on column damage_case.active_key is '进行中案件的箱号唯一键；结案后置 NULL 以释放箱号';
comment on column damage_case.created_at is '创建时间（UTC）';
comment on column damage_case.updated_at is '最近更新时间（UTC）';
comment on column damage_case.closed_at is '结案时间（UTC），NULL 表示未结案';

create unique index if not exists uk_damage_case_active on damage_case(active_key);

create table if not exists damage_item (
    id bigint auto_increment primary key,
    case_id bigint not null,
    iicl_code varchar(16) not null,
    component varchar(64) not null,
    description varchar(256) null,
    responsibility varchar(16) not null,
    repair_cost decimal(19,2) not null
);
comment on table damage_item is '残损项清单，按合成 IICL 代码登记，可复算';
comment on column damage_item.case_id is '所属残损案 ID';
comment on column damage_item.iicl_code is '合成 IICL 残损代码，引用 iicl_code';
comment on column damage_item.component is '受损部位，如 侧板/箱门';
comment on column damage_item.description is '残损描述，NULL 表示未填写';
comment on column damage_item.responsibility is '责任方：OWNER 箱东 / TERMINAL 码头';
comment on column damage_item.repair_cost is '单项修理费，与箱值同货币单位';

create table if not exists estimate (
    id bigint auto_increment primary key,
    case_id bigint not null,
    version int not null,
    total_cost decimal(19,2) not null,
    determination varchar(16) not null,
    status varchar(16) not null,
    request_id varchar(64) not null,
    created_at timestamp not null
);
comment on table estimate is '估价版本表，每次估价产生新版本，旧版本置 SUPERSEDED，全量保留可复算';
comment on column estimate.version is '估价版本号，按案件从 1 递增';
comment on column estimate.total_cost is '残损项修理费合计';
comment on column estimate.determination is '本版本判定：REPAIR/TOTAL_LOSS（单项修理费超箱值 30% 判全损，恰 30% 判修理）';
comment on column estimate.status is 'CURRENT 当前有效 / SUPERSEDED 已被新版本取代';
comment on column estimate.request_id is '产生本版本的幂等请求 ID';

create table if not exists repair_approval (
    id bigint auto_increment primary key,
    case_id bigint not null,
    estimate_id bigint not null,
    approval_date date not null,
    status varchar(16) not null,
    created_at timestamp not null
);
comment on table repair_approval is '修理批复；有效期 7 个 UTC 自然日（批复日为第 1 日，恰第 7 日完工有效）';
comment on column repair_approval.estimate_id is '批复依据的估价版本 ID';
comment on column repair_approval.approval_date is '批复日期（UTC 自然日）';
comment on column repair_approval.status is 'ACTIVE 有效 / SUPERSEDED 因重新估价失效';

create table if not exists repair_completion (
    id bigint auto_increment primary key,
    case_id bigint not null,
    completion_date date not null,
    rework boolean not null,
    created_at timestamp not null
);
comment on table repair_completion is '修理完工登记；rework=true 表示返修完工（仅允许一次返修）';
comment on column repair_completion.completion_date is '完工日期（UTC 自然日），须在批复有效期内';

create table if not exists reinspection (
    id bigint auto_increment primary key,
    case_id bigint not null,
    passed boolean not null,
    created_at timestamp not null
);
comment on table reinspection is '复检记录；不合格允许返修一次，返修后仍不合格转全损评估';
comment on column reinspection.passed is 'true 合格 / false 不合格';

create table if not exists idempotency_record (
    request_id varchar(64) not null primary key,
    endpoint varchar(64) not null,
    fingerprint varchar(64) not null,
    status varchar(16) not null,
    response_json clob null,
    created_at timestamp not null
);
comment on table idempotency_record is '幂等请求记录：同键同参返回首次结果，同键异参返回 409';
comment on column idempotency_record.request_id is '幂等请求 ID，全局唯一';
comment on column idempotency_record.endpoint is '请求入口标识，参与指纹计算';
comment on column idempotency_record.fingerprint is 'requestId 与全部业务字段的 SHA-256 指纹';
comment on column idempotency_record.status is 'IN_PROGRESS 处理中 / COMPLETED 已完成';
comment on column idempotency_record.response_json is '首次成功响应报文，用于同键重放';

create table if not exists case_event (
    id bigint auto_increment primary key,
    case_id bigint not null,
    event_type varchar(32) not null,
    payload varchar(512) null,
    created_at timestamp not null
);
comment on table case_event is '案件事件流；结案后迟到检验照片仅记录 LATE_PHOTO 事件，不改变案件状态';
comment on column case_event.event_type is '事件类型：PHOTO 检验照片 / LATE_PHOTO 结案后迟到照片';
comment on column case_event.payload is '事件负载（如照片引用），NULL 表示无';

-- ============ 修箱密封报价与供应商资格冻结（本题新增） ============
-- 时间口径：时刻均为 UTC；报价金额单位为正整数分。

create table if not exists repair_method (
    code varchar(16) not null primary key,
    description varchar(128) not null
);
comment on table repair_method is '合成修箱工法代码表（非真实行业标准，仅本地演示）';
comment on column repair_method.code is '工法代码，如 WELD/SEAL';
comment on column repair_method.description is '工法中文说明';

merge into repair_method key(code) values
    ('WELD', '焊接'),
    ('PATCH', '补板'),
    ('SEAL', '密封'),
    ('REPL', '换件'),
    ('ALIGN', '校正');

create table if not exists supplier (
    id bigint auto_increment primary key,
    supplier_no varchar(32) not null,
    name varchar(128) not null,
    version int not null,
    created_at timestamp not null,
    updated_at timestamp not null
);
comment on table supplier is '合成供应商主表；version 为当前资格版本号，从 1 开始，每次成功变更加 1';
comment on column supplier.supplier_no is '供应商编号，全局唯一；开标同价时按编号升序';
comment on column supplier.name is '供应商名称（合成数据）';
comment on column supplier.version is '当前资格版本号，从 1 开始；修改须携带 expectedVersion 乐观校验';
comment on column supplier.created_at is '创建时间（UTC）';
comment on column supplier.updated_at is '最近更新时间（UTC）';

create unique index if not exists uk_supplier_no on supplier(supplier_no);

create table if not exists supplier_qualification (
    id bigint auto_increment primary key,
    supplier_id bigint not null,
    version int not null,
    valid_from timestamp not null,
    valid_until timestamp not null,
    status varchar(16) not null,
    frozen boolean not null default false,
    created_at timestamp not null
);
comment on table supplier_qualification is '供应商资格版本表，全量保留历史；开标以开标时刻的资格状态评估，不采用提交时旧资格';
comment on column supplier_qualification.supplier_id is '所属供应商 ID';
comment on column supplier_qualification.version is '资格版本号，与 supplier.version 对应，从 1 开始';
comment on column supplier_qualification.valid_from is '资格生效时刻（UTC），含边界';
comment on column supplier_qualification.valid_until is '资格失效时刻（UTC），含边界；开标时刻晚于该时刻视为已失效';
comment on column supplier_qualification.status is 'ACTIVE 有效 / SUPERSEDED 已被新版本取代 / REVOKED 已撤销';
comment on column supplier_qualification.frozen is '定标后冻结为 true，冻结后撤销只形成风险事件，不改历史中标证据';

create table if not exists supplier_qual_method (
    qualification_id bigint not null,
    method_code varchar(16) not null
);
comment on table supplier_qual_method is '资格版本适用工法明细；报价工法须属于供应商当前资格版本工法集合';
comment on column supplier_qual_method.qualification_id is '所属资格版本 ID';
comment on column supplier_qual_method.method_code is '工法代码，引用 repair_method';

create table if not exists inquiry (
    id bigint auto_increment primary key,
    case_id bigint not null,
    version int not null,
    deadline timestamp not null,
    status varchar(16) not null,
    request_id varchar(64) not null,
    created_at timestamp not null
);
comment on table inquiry is '密封询价版本表；待批复残损案（已估价且判定修理）可发布，固定残损项集合、每项允许工法与 UTC 报价截止时刻';
comment on column inquiry.case_id is '所属残损案 ID';
comment on column inquiry.version is '询价版本号，按案件从 1 递增；新版本发布后旧版本置 SUPERSEDED';
comment on column inquiry.deadline is 'UTC 报价截止时刻；now<截止方可投标，恰在截止拒绝';
comment on column inquiry.status is 'OPEN 投标中 / OPENED 已开标 / AWARDED 已定标并转修理批复 / SUPERSEDED 已被新版本取代';
comment on column inquiry.request_id is '发布本版本的幂等请求 ID';

create table if not exists inquiry_item (
    id bigint auto_increment primary key,
    inquiry_id bigint not null,
    item_id bigint not null
);
comment on table inquiry_item is '询价项：发布时固定的残损项集合；报价须覆盖全部询价项，缺一整版拒绝';
comment on column inquiry_item.inquiry_id is '所属询价版本 ID';
comment on column inquiry_item.item_id is '残损项 ID，引用 damage_item';

create table if not exists inquiry_item_method (
    inquiry_item_id bigint not null,
    method_code varchar(16) not null
);
comment on table inquiry_item_method is '询价项允许工法明细；报价工法须属于该项允许工法集合';
comment on column inquiry_item_method.inquiry_item_id is '所属询价项 ID';
comment on column inquiry_item_method.method_code is '允许工法代码，引用 repair_method';

create table if not exists quotation (
    id bigint auto_increment primary key,
    inquiry_id bigint not null,
    supplier_id bigint not null,
    version int not null,
    total_amount bigint not null,
    status varchar(16) not null,
    current_key bigint null,
    frozen boolean not null default false,
    request_id varchar(64) not null,
    created_at timestamp not null
);
comment on table quotation is '报价版本表；一供应商一询价版本只一个当前报价（current_key 唯一索引仲裁），修订保留历史';
comment on column quotation.inquiry_id is '所属询价版本 ID';
comment on column quotation.supplier_id is '报价供应商 ID';
comment on column quotation.version is '报价版本号，按供应商从 1 递增；修订须携带 expectedVersion';
comment on column quotation.total_amount is '报价总价，单位为正整数分，等于各行金额之和';
comment on column quotation.status is 'CURRENT 当前有效 / SUPERSEDED 已被修订取代';
comment on column quotation.current_key is '当前报价唯一键：CURRENT 时等于 supplier_id，否则为 NULL';
comment on column quotation.frozen is '开标后冻结为 true，冻结后不可修订';
comment on column quotation.request_id is '产生本版本的幂等请求 ID';

create unique index if not exists uk_quotation_current on quotation(inquiry_id, current_key);

create table if not exists quotation_line (
    id bigint auto_increment primary key,
    quotation_id bigint not null,
    item_id bigint not null,
    method_code varchar(16) not null,
    amount_cents bigint not null
);
comment on table quotation_line is '报价行：每个询价项一行，金额为正整数分，工法须有资格';
comment on column quotation_line.quotation_id is '所属报价版本 ID';
comment on column quotation_line.item_id is '残损项 ID，须属于询价固定项集合';
comment on column quotation_line.method_code is '报价工法代码';
comment on column quotation_line.amount_cents is '该行报价金额，单位为正整数分';

create table if not exists bid_opening (
    id bigint auto_increment primary key,
    inquiry_id bigint not null,
    winner_quotation_id bigint null,
    opened_at timestamp not null
);
comment on table bid_opening is '开标记录；一询价版本只开标一次，重复开标返回首次清单，不因后来资格变化重算';
comment on column bid_opening.inquiry_id is '所属询价版本 ID，唯一';
comment on column bid_opening.winner_quotation_id is '中标报价版本 ID；零有效报价时为 NULL，不开批复';
comment on column bid_opening.opened_at is '开标时刻（UTC），须不早于截止时刻';

create unique index if not exists uk_bid_opening_inquiry on bid_opening(inquiry_id);

create table if not exists bid_opening_entry (
    id bigint auto_increment primary key,
    opening_id bigint not null,
    quotation_id bigint not null,
    supplier_id bigint not null,
    supplier_no varchar(32) not null,
    total_amount bigint not null,
    included boolean not null,
    exclusion_reason varchar(256) null,
    qual_version int null,
    rank_no int null
);
comment on table bid_opening_entry is '开标明细：全部当前报价的冻结评估清单；资格已失效的报价排除并列明原因';
comment on column bid_opening_entry.quotation_id is '评估的报价版本 ID';
comment on column bid_opening_entry.supplier_no is '供应商编号快照，用于同价升序tie-break';
comment on column bid_opening_entry.total_amount is '报价总价快照，单位为正整数分';
comment on column bid_opening_entry.included is 'true 参与比价 / false 开标时资格已失效被排除';
comment on column bid_opening_entry.exclusion_reason is '排除原因（如开标时资格已过期/已撤销），included=true 时为 NULL';
comment on column bid_opening_entry.qual_version is '开标时评估的资格版本号';
comment on column bid_opening_entry.rank_no is '有效报价中的名次（总价升序、同价按供应商编号升序），从 1 开始；被排除时为 NULL';

create table if not exists award_review (
    id bigint auto_increment primary key,
    inquiry_id bigint not null,
    reviewer varchar(64) not null,
    created_at timestamp not null
);
comment on table award_review is '定标复核记录；双人不同复核后才将中标报价转为原案修理批复';
comment on column award_review.inquiry_id is '所属询价版本 ID';
comment on column award_review.reviewer is '复核人标识；同一复核人不得重复复核';

create unique index if not exists uk_award_review on award_review(inquiry_id, reviewer);

create table if not exists risk_event (
    id bigint auto_increment primary key,
    case_id bigint null,
    inquiry_id bigint null,
    supplier_id bigint not null,
    event_type varchar(48) not null,
    detail varchar(512) null,
    created_at timestamp not null
);
comment on table risk_event is '风险事件流；批复后撤销资格只形成风险事件，不改历史中标证据';
comment on column risk_event.case_id is '关联残损案 ID，无关联时为 NULL';
comment on column risk_event.inquiry_id is '关联询价版本 ID，无关联时为 NULL';
comment on column risk_event.supplier_id is '涉及供应商 ID';
comment on column risk_event.event_type is '事件类型：QUALIFICATION_REVOKED_AFTER_AWARD 定标后撤销资格';
comment on column risk_event.detail is '事件说明，NULL 表示无';
