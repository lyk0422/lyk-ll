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
comment on table case_event is '案件事件流；结案后迟到检验照片仅记录 LATE_PHOTO 事件，不改变案件状态；QUALIFICATION_RISK 为批复后供应商资格撤销风险事件';
comment on column case_event.event_type is '事件类型：PHOTO 检验照片 / LATE_PHOTO 结案后迟到照片 / QUALIFICATION_RISK 批复后资格撤销风险';
comment on column case_event.payload is '事件负载（如照片引用或结构化 JSON），NULL 表示无';

-- ======================================================================
-- 修箱批复前密封询价与比价（合成规则，仅本地演示，不用于生产）
-- 金额单位：正整数分；所有时刻均为 UTC 精确时刻（timestamp）。
-- ======================================================================

create table if not exists repair_method (
    code varchar(16) not null primary key,
    description varchar(128) not null
);
comment on table repair_method is '合成修箱工法代码表（非真实数据，仅本地演示）';
comment on column repair_method.code is '工法代码，如 WELD/STRAIGHTEN/REPLACE/PATCH/PAINT';
comment on column repair_method.description is '工法中文说明';

merge into repair_method key(code) values
    ('WELD', '焊接矫正'),
    ('STRAIGHTEN', '机械矫正'),
    ('REPLACE', '板材更换'),
    ('PATCH', '补板'),
    ('PAINT', '补漆');

create table if not exists supplier (
    id bigint auto_increment primary key,
    supplier_no varchar(32) not null,
    name varchar(128) not null,
    created_at timestamp not null
);
comment on table supplier is '合成修箱供应商；编号用于同价时升序中标，资格与适用工法见子表';
comment on column supplier.supplier_no is '供应商编号（合成数据，全局唯一；同价按编号字典序升序中标）';
comment on column supplier.name is '供应商名称（合成数据）';

create unique index if not exists uk_supplier_no on supplier(supplier_no);

create table if not exists supplier_method (
    supplier_id bigint not null,
    method_code varchar(16) not null,
    primary key (supplier_id, method_code)
);
comment on table supplier_method is '供应商适用工法集合；报价工法不在集合内则整版拒绝';
comment on column supplier_method.supplier_id is '供应商 ID';
comment on column supplier_method.method_code is '适用工法代码，引用 repair_method';

create table if not exists supplier_qualification (
    id bigint auto_increment primary key,
    supplier_id bigint not null,
    version int not null,
    status varchar(16) not null,
    valid_from timestamp not null,
    valid_to timestamp null,
    request_id varchar(64) not null,
    created_at timestamp not null
);
comment on table supplier_qualification is '供应商资格版本；从 1 起追加，撤销追加 REVOKED 新版本，历史行不改正以保留中标证据快照';
comment on column supplier_qualification.version is '资格版本号，按供应商从 1 递增，撤销成功后 +1';
comment on column supplier_qualification.status is 'ACTIVE 有效 / REVOKED 已撤销（撤销行的 valid_from/valid_to 均为撤销时刻）';
comment on column supplier_qualification.valid_from is '资格生效时刻（UTC，含）';
comment on column supplier_qualification.valid_to is '资格失效时刻（UTC，不含）；NULL 表示长期有效';
comment on column supplier_qualification.request_id is '产生本版本的幂等请求 ID';

create unique index if not exists uk_qualification_version on supplier_qualification(supplier_id, version);

create table if not exists inquiry (
    id bigint auto_increment primary key,
    case_id bigint not null,
    seq int not null,
    version int not null,
    deadline timestamp not null,
    status varchar(16) not null,
    request_id varchar(64) not null,
    created_at timestamp not null
);
comment on table inquiry is '密封询价版本；锚定待批复残损案，固定残损项集合与每项允许工法；version 为乐观锁版本，从 1 起，开标/转批复成功各 +1';
comment on column inquiry.case_id is '所属残损案 ID';
comment on column inquiry.seq is '案件下询价版本序号，从 1 递增；零有效报价后可发布下一序号';
comment on column inquiry.version is '乐观锁版本，从 1 开始，写操作须携带 expectedVersion，成功变更后 +1';
comment on column inquiry.deadline is 'UTC 报价截止时刻；now>=deadline 拒绝投标（恰在截止时刻亦拒绝），截止后方可开标';
comment on column inquiry.status is 'OPEN 投标中 / OPENED 已开标有中标 / NO_AWARD 开标零有效报价 / CONVERTED 已转为修理批复';
comment on column inquiry.request_id is '产生本询价版本的幂等请求 ID';

create unique index if not exists uk_inquiry_case_seq on inquiry(case_id, seq);

create table if not exists inquiry_item (
    id bigint auto_increment primary key,
    inquiry_id bigint not null,
    line_no int not null,
    damage_item_id bigint not null
);
comment on table inquiry_item is '询价固定残损项行；行号在询价版本内固定，报价必须按行号精确覆盖全部项';
comment on column inquiry_item.line_no is '行号，询价版本内从 1 递增，开标与比价以此对齐';
comment on column inquiry_item.damage_item_id is '锚定的案件残损项 ID（damage_item），固定残损项集合';

create unique index if not exists uk_inquiry_item_line on inquiry_item(inquiry_id, line_no);

create table if not exists inquiry_item_method (
    inquiry_item_id bigint not null,
    method_code varchar(16) not null,
    primary key (inquiry_item_id, method_code)
);
comment on table inquiry_item_method is '询价每项允许工法集合；报价工法不在其内则整版拒绝';
comment on column inquiry_item_method.inquiry_item_id is '询价残损项行 ID';
comment on column inquiry_item_method.method_code is '允许工法代码，引用 repair_method';

create table if not exists bid (
    id bigint auto_increment primary key,
    inquiry_id bigint not null,
    supplier_id bigint not null,
    current_version int not null,
    status varchar(16) not null,
    created_at timestamp not null,
    updated_at timestamp not null
);
comment on table bid is '报价主表；一供应商一询价版本仅一个当前报价，修订在 bid_version 留历史';
comment on column bid.current_version is '当前报价版本号，从 1 开始，修订须携带 expectedVersion，成功后 +1';
comment on column bid.status is 'ACTIVE 可修订 / FROZEN 已随开标冻结，不得再修订';

create unique index if not exists uk_bid_inquiry_supplier on bid(inquiry_id, supplier_id);

create table if not exists bid_version (
    id bigint auto_increment primary key,
    bid_id bigint not null,
    version int not null,
    total_cents bigint not null,
    status varchar(16) not null,
    request_id varchar(64) not null,
    submitted_at timestamp not null
);
comment on table bid_version is '报价修订历史；每次整版合格提交追加新版本，旧版本置 SUPERSEDED 全量保留';
comment on column bid_version.version is '报价版本号，从 1 递增';
comment on column bid_version.total_cents is '报价合计，单位正整数分';
comment on column bid_version.status is 'CURRENT 当前有效 / SUPERSEDED 已被修订取代';
comment on column bid_version.request_id is '产生本版本的幂等请求 ID';

create unique index if not exists uk_bid_version on bid_version(bid_id, version);

create table if not exists bid_line (
    id bigint auto_increment primary key,
    bid_version_id bigint not null,
    inquiry_item_id bigint not null,
    line_no int not null,
    method_code varchar(16) not null,
    amount_cents bigint not null
);
comment on table bid_line is '报价明细行；金额为正整数分，工法须同时属于询价允许工法与供应商适用工法';
comment on column bid_line.amount_cents is '单行金额，正整数分，必须大于 0';

create unique index if not exists uk_bid_line_version on bid_line(bid_version_id, line_no);

create table if not exists bid_opening (
    id bigint auto_increment primary key,
    inquiry_id bigint not null,
    inquiry_version int not null,
    opened_at timestamp not null,
    status varchar(16) not null,
    winner_supplier_id bigint null,
    winner_bid_version_id bigint null,
    winner_total_cents bigint null,
    request_id varchar(64) not null
);
comment on table bid_opening is '开标记录；每个询价仅一条，开标即冻结，重复开标返回本首次清单不重算';
comment on column bid_opening.inquiry_version is '开标时询价乐观锁版本快照';
comment on column bid_opening.opened_at is '开标时刻（UTC），资格有效性按该时刻判定';
comment on column bid_opening.status is 'AWARDED 已选出中标 / NO_VALID 零有效报价不开批复';
comment on column bid_opening.winner_supplier_id is '中标供应商 ID；NULL 表示无有效报价';
comment on column bid_opening.winner_bid_version_id is '中标报价版本 ID（冻结证据），NULL 表示无中标';
comment on column bid_opening.winner_total_cents is '中标总价，正整数分；NULL 表示无中标';

create unique index if not exists uk_bid_opening_inquiry on bid_opening(inquiry_id);

create table if not exists bid_opening_entry (
    id bigint auto_increment primary key,
    opening_id bigint not null,
    supplier_id bigint not null,
    bid_version_id bigint not null,
    total_cents bigint not null,
    qualification_version int not null,
    qualification_status varchar(16) not null,
    qual_valid_from timestamp not null,
    qual_valid_to timestamp null,
    valid boolean not null,
    exclude_reason varchar(48) null,
    rank_no int null
);
comment on table bid_opening_entry is '开标清单行；按开标时刻资格快照冻结，失效报价记录排除原因且不参与排名';
comment on column bid_opening_entry.qualification_version is '开标时刻供应商资格最新版本号（快照，不采用提交时旧资格）';
comment on column bid_opening_entry.qualification_status is '开标时刻资格版本状态快照：ACTIVE/REVOKED';
comment on column bid_opening_entry.qual_valid_from is '资格版本生效时刻快照（UTC）';
comment on column bid_opening_entry.qual_valid_to is '资格版本失效时刻快照（UTC，不含），NULL 为长期有效';
comment on column bid_opening_entry.valid is '开标时刻资格是否有效；false 则整份报价排除';
comment on column bid_opening_entry.exclude_reason is '排除原因：QUALIFICATION_REVOKED 已撤销 / QUALIFICATION_EXPIRED 有效期未覆盖开标时刻；NULL 为有效';
comment on column bid_opening_entry.rank_no is '有效报价排名，1 为中标；无效报价为 NULL';

create unique index if not exists uk_bid_opening_entry_supplier on bid_opening_entry(opening_id, supplier_id);

create table if not exists bid_review (
    id bigint auto_increment primary key,
    inquiry_id bigint not null,
    opening_id bigint not null,
    reviewer varchar(64) not null,
    request_id varchar(64) not null,
    created_at timestamp not null
);
comment on table bid_review is '中标报价双重复核；两名不同复核人齐后方可转为修理批复，同一人不得重复复核';
comment on column bid_review.reviewer is '复核人标识；同一开标内唯一，两人必须不同';

create unique index if not exists uk_bid_review_opening_reviewer on bid_review(opening_id, reviewer);

alter table repair_approval add column if not exists source varchar(16) not null default 'DIRECT';
alter table repair_approval add column if not exists opening_id bigint null;
comment on column repair_approval.source is '批复来源：DIRECT 无询价旧入口 / INQUIRY 密封询价中标转批复 / CHANGE 修理变更单生效换版';
comment on column repair_approval.opening_id is '询价转批复时的开标记录 ID（冻结证据）；DIRECT 旧入口为 NULL，CHANGE 沿用原开标 ID';
comment on column case_event.payload is '事件负载（如照片引用），NULL 表示无';

-- ======================================================================
-- 修理变更单（中标批复后改工法/分报价换版，合成规则，仅本地演示）
-- 金额单位：正整数分；所有时刻均为 UTC 精确时刻（timestamp）。
-- ======================================================================

alter table repair_approval add column if not exists change_order_id bigint null;
comment on column repair_approval.change_order_id is '变更生效产生本批复的修理变更单 ID；非变更来源为 NULL';

create table if not exists change_order (
    id bigint auto_increment primary key,
    case_id bigint not null,
    seq int not null,
    version int not null,
    status varchar(16) not null,
    pending_key bigint null,
    approval_id bigint not null,
    estimate_id bigint not null,
    opening_id bigint not null,
    supplier_id bigint not null,
    qualification_version int not null,
    old_total_cents bigint not null,
    new_total_cents bigint not null,
    new_estimate_id bigint null,
    new_approval_id bigint null,
    request_id varchar(64) not null,
    created_at timestamp not null,
    updated_at timestamp not null
);
comment on table change_order is '修理变更单候选；仅绑定已批复未完工修箱案的当前有效中标批复，双人复核同意后换版新估价与批复';
comment on column change_order.case_id is '所属残损案 ID';
comment on column change_order.seq is '案件下变更单序号，从 1 递增；创建请求 expectedVersion 为当前序号数';
comment on column change_order.version is '单据乐观锁版本，从 1 起；复核/撤回等修改须携带 expectedVersion，成功 +1';
comment on column change_order.status is 'PENDING 待复核 / EFFECTIVE 已生效 / WITHDRAWN 已撤回 / REJECTED 已驳回';
comment on column change_order.pending_key is '待复核期间等于 case_id（唯一索引保证每案最多一个活动候选），离开 PENDING 置 NULL';
comment on column change_order.approval_id is '冻结的原有效批复 ID（中标证据，不改写）';
comment on column change_order.estimate_id is '冻结的原有效估价版本 ID';
comment on column change_order.opening_id is '原开标冻结记录 ID（中标溯源）';
comment on column change_order.supplier_id is '中标供应商 ID';
comment on column change_order.qualification_version is '申请时刻冻结的供应商资格版本号；生效前最终事务仍重查最新资格';
comment on column change_order.old_total_cents is '原中标总价，正整数分';
comment on column change_order.new_total_cents is '变更后总价（变更项新价 + 其余项沿用原中标价），正整数分';
comment on column change_order.new_estimate_id is '生效后新估价版本 ID；未生效为 NULL';
comment on column change_order.new_approval_id is '生效后新批复 ID；未生效为 NULL';
comment on column change_order.request_id is '产生本候选的幂等请求 ID';
comment on column change_order.created_at is '创建时刻（UTC）';
comment on column change_order.updated_at is '最近变更时刻（UTC）';

create unique index if not exists uk_change_order_case_seq on change_order(case_id, seq);
create unique index if not exists uk_change_order_pending on change_order(pending_key);

create table if not exists change_order_line (
    id bigint auto_increment primary key,
    change_order_id bigint not null,
    damage_item_id bigint not null,
    changed boolean not null,
    old_method_code varchar(16) not null,
    old_amount_cents bigint not null,
    new_method_code varchar(16) not null,
    new_amount_cents bigint not null
);
comment on table change_order_line is '变更单全清单冻结行：案件每个残损项一行，记录前后工法与分报价，未变更项前后一致';
comment on column change_order_line.change_order_id is '所属变更单 ID';
comment on column change_order_line.damage_item_id is '案件残损项 ID（damage_item），仅限本案已有项';
comment on column change_order_line.changed is 'true 本次变更项 / false 沿用原中标';
comment on column change_order_line.old_method_code is '原中标工法代码（冻结证据）';
comment on column change_order_line.old_amount_cents is '原中标分报价，正整数分（冻结证据）';
comment on column change_order_line.new_method_code is '变更后工法代码；未变更项等于原工法';
comment on column change_order_line.new_amount_cents is '变更后分报价，正整数分；未变更项等于原报价';

create unique index if not exists uk_change_order_line_item on change_order_line(change_order_id, damage_item_id);

create table if not exists change_order_review (
    id bigint auto_increment primary key,
    change_order_id bigint not null,
    reviewer varchar(64) not null,
    decision varchar(8) not null,
    request_id varchar(64) not null,
    created_at timestamp not null
);
comment on table change_order_review is '变更单复核记录；两名不同复核人均 AGREE 才生效换版，任一 REJECT 即驳回';
comment on column change_order_review.change_order_id is '所属变更单 ID';
comment on column change_order_review.reviewer is '复核人标识；同一变更单内唯一';
comment on column change_order_review.decision is '复核决定：AGREE 同意 / REJECT 驳回';
comment on column change_order_review.request_id is '产生本记录的幂等请求 ID';

create unique index if not exists uk_change_order_review_reviewer on change_order_review(change_order_id, reviewer);
