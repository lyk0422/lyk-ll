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
comment on table case_event is '案件事件流；结案后迟到检验照片仅记录 LATE_PHOTO 事件，不改变案件状态；中标资格批复后撤销记 SUPPLIER_RISK 事件';
comment on column case_event.event_type is '事件类型：PHOTO 检验照片 / LATE_PHOTO 结案后迟到照片 / SUPPLIER_RISK 中标供应商资格事后撤销';
comment on column case_event.payload is '事件负载（如照片引用），NULL 表示无';

-- ===================== 修箱密封报价与供应商资格（合成数据，仅本地演示） =====================
-- 时间口径：所有时刻均为 UTC；报价金额单位为正整数分（cents），不允许小数。

create table if not exists repair_method (
    code varchar(16) not null primary key,
    description varchar(128) not null
);
comment on table repair_method is '合成修箱工法代码表（非真实行业数据，仅本地演示）';
comment on column repair_method.code is '工法代码，如 HM 锤平矫正 / WD 焊接修补 / RP 板件更换 / SP 打磨喷漆 / PT 贴补密封';
comment on column repair_method.description is '工法中文说明';

merge into repair_method key(code) values
    ('HM', '锤平矫正'),
    ('WD', '焊接修补'),
    ('RP', '板件更换'),
    ('SP', '打磨喷漆'),
    ('PT', '贴补密封');

create table if not exists supplier (
    id bigint auto_increment primary key,
    supplier_no varchar(32) not null,
    name varchar(128) not null,
    created_at timestamp not null
);
comment on table supplier is '合成修箱供应商；supplier_no 全局唯一，密封报价同价时按编号升序定标';
comment on column supplier.supplier_no is '供应商编号（业务键），同价比价时按字典序升序';
comment on column supplier.name is '供应商名称（合法文本，可含任意分隔符，不参与结构化指纹拼接）';

create unique index if not exists uk_supplier_no on supplier(supplier_no);

create table if not exists supplier_qualification (
    id bigint auto_increment primary key,
    supplier_id bigint not null,
    version int not null,
    valid_from timestamp not null,
    valid_until timestamp not null,
    status varchar(16) not null,
    request_id varchar(64) not null,
    created_at timestamp not null,
    revoked_at timestamp null
);
comment on table supplier_qualification is '供应商资格版本，按供应商从 1 递增；版本行只追加，开标按开标时刻重算有效性，不沿用投标时旧资格';
comment on column supplier_qualification.version is '资格版本号，从 1 开始，成功续期加 1';
comment on column supplier_qualification.valid_from is '资格有效期起点（UTC，含该时刻）';
comment on column supplier_qualification.valid_until is '资格有效期终点（UTC，含该时刻）；开标时刻晚于该值即失效排除';
comment on column supplier_qualification.status is 'ACTIVE 有效 / REVOKED 已撤销；撤销不删除、不改写已冻结的中标证据';
comment on column supplier_qualification.request_id is '产生本版本的幂等请求 ID';
comment on column supplier_qualification.revoked_at is '撤销时刻（UTC），NULL 表示未撤销';

create unique index if not exists uk_qual_version on supplier_qualification(supplier_id, version);

create table if not exists qualification_method (
    qualification_id bigint not null,
    method_code varchar(16) not null,
    primary key (qualification_id, method_code)
);
comment on table qualification_method is '资格版本适用工法集合；投标工法须被开标时有效资格版本覆盖';

create table if not exists inquiry (
    id bigint auto_increment primary key,
    case_id bigint not null,
    version int not null,
    deadline timestamp not null,
    status varchar(16) not null,
    request_id varchar(64) not null,
    created_at timestamp not null,
    opened_at timestamp null,
    approval_id bigint null
);
comment on table inquiry is '密封询价版本，按案件从 1 递增；发布即固定残损项集合与每项允许工法，零有效报价开标后可发新版本';
comment on column inquiry.version is '询价版本号，从 1 开始，成功发布变更加 1';
comment on column inquiry.deadline is 'UTC 报价截止时刻；now<deadline 可投标，恰在截止时刻拒绝';
comment on column inquiry.status is 'OPEN 投标中 / OPENED 已开标冻结 / CONVERTED 已双人复核转为修理批复';
comment on column inquiry.opened_at is '开标时刻（UTC），NULL 表示未开标；重复开标返回首次结果不重算';
comment on column inquiry.approval_id is '转原案修理批复后写入的批复 ID，NULL 表示尚未转换';

create unique index if not exists uk_inquiry_version on inquiry(case_id, version);

create table if not exists inquiry_item (
    id bigint auto_increment primary key,
    inquiry_id bigint not null,
    item_seq int not null,
    case_item_id bigint not null,
    iicl_code varchar(16) not null,
    component varchar(64) not null
);
comment on table inquiry_item is '询价版本固定的残损项快照；开标与比价均以快照为准，不随后续编辑变化';
comment on column inquiry_item.item_seq is '项序号，从 1 开始，报价必须按序号覆盖全部项';
comment on column inquiry_item.case_item_id is '快照来源 damage_item.id';

create unique index if not exists uk_inquiry_item_seq on inquiry_item(inquiry_id, item_seq);

create table if not exists inquiry_item_method (
    inquiry_item_id bigint not null,
    method_code varchar(16) not null,
    primary key (inquiry_item_id, method_code)
);
comment on table inquiry_item_method is '每个询价残损项允许的工法集合；报价工法不在集合内则整版拒绝';

create table if not exists bid (
    id bigint auto_increment primary key,
    inquiry_id bigint not null,
    supplier_id bigint not null,
    revision int not null,
    total_cents bigint not null,
    status varchar(16) not null,
    result varchar(24) null,
    exclusion_reason varchar(48) null,
    qualification_id bigint null,
    request_id varchar(64) not null,
    created_at timestamp not null
);
comment on table bid is '报价版本：一供应商一询价版本仅一个当前报价，修订全量保留历史；开标后当前行冻结';
comment on column bid.revision is '报价修订号，从 1 开始，成功修订加 1；旧修订置 SUPERSEDED';
comment on column bid.total_cents is '报价总价，正整数分合计';
comment on column bid.status is 'CURRENT 当前 / SUPERSEDED 历史修订 / FROZEN 开标冻结';
comment on column bid.result is '开标结果：WINNER 中标 / LOST 未中标 / EXCLUDED 资格失效排除；NULL 未开标';
comment on column bid.exclusion_reason is '排除原因代码，如 QUALIFICATION_EXPIRED 资格过期、METHOD_NOT_COVERED 有效资格不覆盖工法';
comment on column bid.qualification_id is '开标时实际采用的资格版本 ID（冻结证据），不使用投标时旧资格';

create unique index if not exists uk_bid_revision on bid(inquiry_id, supplier_id, revision);

create table if not exists bid_item (
    id bigint auto_increment primary key,
    bid_id bigint not null,
    inquiry_item_id bigint not null,
    method_code varchar(16) not null,
    price_cents bigint not null
);
comment on table bid_item is '报价分项快照；price_cents 为正整数分，缺项、非正整数或工法越界均整版拒绝';

create unique index if not exists uk_bid_item on bid_item(bid_id, inquiry_item_id);

create table if not exists bid_opening (
    id bigint auto_increment primary key,
    inquiry_id bigint not null,
    winner_bid_id bigint null,
    winner_supplier_id bigint null,
    total_cents bigint null,
    status varchar(16) not null,
    approval_id bigint null,
    opened_at timestamp not null,
    request_id varchar(64) not null
);
comment on table bid_opening is '开标结果（每询价版本唯一一行）：有效报价最低价中标，同价按供应商编号升序；零有效报价不开批复';
comment on column bid_opening.status is 'SELECTED 已选定 / NO_VALID_BID 零有效报价';
comment on column bid_opening.approval_id is '双人复核通过后转原案修理批复的 ID，NULL 表示尚未转换';

create unique index if not exists uk_opening_inquiry on bid_opening(inquiry_id);

create table if not exists bid_exclusion (
    id bigint auto_increment primary key,
    opening_id bigint not null,
    bid_id bigint not null,
    supplier_id bigint not null,
    reason varchar(48) not null
);
comment on table bid_exclusion is '开标排除清单及原因，随首次开标结果冻结，重复开标原样返回';

create table if not exists bid_review (
    id bigint auto_increment primary key,
    opening_id bigint not null,
    reviewer varchar(64) not null,
    request_id varchar(64) not null,
    created_at timestamp not null
);
comment on table bid_review is '中标双人不同复核记录；须两名不同复核人，同一人重复复核拒绝，第二人复核通过即转批复';

create unique index if not exists uk_review_reviewer on bid_review(opening_id, reviewer);

-- 合成供应商种子数据（仅本地演示；DB_CLOSE_DELAY 共享库下用 not exists 防重复插入）
merge into supplier(id, supplier_no, name, created_at) key(supplier_no) values
    (1, 'SUP-100', '华东修箱合成厂', '2026-01-01 00:00:00'),
    (2, 'SUP-200', '南方焊修合成厂', '2026-01-01 00:00:00'),
    (3, 'SUP-300', '江北快修合成店', '2026-01-01 00:00:00');

insert into supplier_qualification(supplier_id, version, valid_from, valid_until, status, request_id, created_at)
select s.id, 1, '2026-01-01 00:00:00', '2026-12-31 23:59:59', 'ACTIVE', 'seed', '2026-01-01 00:00:00'
from supplier s
where s.supplier_no in ('SUP-100', 'SUP-200', 'SUP-300')
  and not exists (select 1 from supplier_qualification q where q.supplier_id = s.id);

insert into qualification_method(qualification_id, method_code)
select q.id, m.code
from supplier_qualification q
join supplier s on s.id = q.supplier_id
cross join (select 'HM' code union all select 'WD' union all select 'SP') m
where s.supplier_no = 'SUP-100' and q.version = 1
  and not exists (select 1 from qualification_method qm where qm.qualification_id = q.id and qm.method_code = m.code);

insert into qualification_method(qualification_id, method_code)
select q.id, m.code
from supplier_qualification q
join supplier s on s.id = q.supplier_id
cross join (select 'WD' code union all select 'RP') m
where s.supplier_no = 'SUP-200' and q.version = 1
  and not exists (select 1 from qualification_method qm where qm.qualification_id = q.id and qm.method_code = m.code);

insert into qualification_method(qualification_id, method_code)
select q.id, m.code
from supplier_qualification q
join supplier s on s.id = q.supplier_id
cross join (select 'HM' code union all select 'PT' union all select 'SP') m
where s.supplier_no = 'SUP-300' and q.version = 1
  and not exists (select 1 from qualification_method qm where qm.qualification_id = q.id and qm.method_code = m.code);
