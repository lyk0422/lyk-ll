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
comment on table case_event is '案件事件流；结案后迟到检验照片仅记录 LATE_PHOTO 事件，不改变案件状态；中标供应商批复后被撤销资格仅记录 QUALIFICATION_RISK 风险事件';
comment on column case_event.event_type is '事件类型：PHOTO 检验照片 / LATE_PHOTO 结案后迟到照片 / QUALIFICATION_RISK 中标供应商资格撤销风险';
comment on column case_event.payload is '事件负载（如照片引用或风险描述），NULL 表示无';

-- ========== 修箱批复前密封询价与比价（合成规则，仅本地演示，金额单位为正整数分） ==========

create table if not exists supplier (
    id bigint auto_increment primary key,
    supplier_code varchar(32) not null,
    name varchar(128) not null,
    version int not null,
    status varchar(16) not null,
    created_at timestamp not null,
    updated_at timestamp not null,
    constraint uk_supplier_code unique (supplier_code)
);
comment on table supplier is '合成修箱供应商；version 为乐观版本，从 1 开始，撤销资格成功后加 1';
comment on column supplier.supplier_code is '供应商编号，业务唯一；开标同价时按编号升序中标';
comment on column supplier.version is '乐观锁版本，初始为 1，每次成功撤销资格加 1';
comment on column supplier.status is 'ACTIVE 资格有效 / REVOKED 已撤销（撤销时刻与开标竞争口径一致）';

create table if not exists supplier_method (
    supplier_id bigint not null,
    method_code varchar(32) not null,
    primary key (supplier_id, method_code)
);
comment on table supplier_method is '供应商适用工法集合；投标工法不在集合内整版拒绝';
comment on column supplier_method.method_code is '工法代码（合成数据），如 WELD/PATCH/STRAIGHTEN';

create table if not exists supplier_qualification (
    id bigint auto_increment primary key,
    supplier_id bigint not null,
    version int not null,
    valid_from timestamp not null,
    valid_to timestamp not null,
    status varchar(16) not null,
    revoked_at timestamp null,
    revoke_reason varchar(256) null,
    created_at timestamp not null,
    constraint uk_supplier_qual_version unique (supplier_id, version)
);
comment on table supplier_qualification is '供应商资格版本；开标时按开标时刻重新判断有效期，不采用投标时旧资格';
comment on column supplier_qualification.version is '资格版本号，从 1 开始；中标后该版本快照冻结在 repair_award';
comment on column supplier_qualification.valid_from is '资格生效时刻（UTC，含该时刻）';
comment on column supplier_qualification.valid_to is '资格失效时刻（UTC，不含该时刻；恰在该时刻视为已失效）';
comment on column supplier_qualification.status is 'ACTIVE 有效 / REVOKED 已撤销';
comment on column supplier_qualification.revoked_at is '撤销时刻（UTC），NULL 表示未撤销';
comment on column supplier_qualification.revoke_reason is '撤销原因（合成文本），NULL 表示无';

create table if not exists inquiry (
    id bigint auto_increment primary key,
    case_id bigint not null,
    version int not null,
    deadline timestamp not null,
    status varchar(16) not null,
    request_id varchar(64) not null,
    created_at timestamp not null,
    constraint uk_inquiry_case_version unique (case_id, version)
);
comment on table inquiry is '密封询价版本，按案件从 1 递增；固定残损项集合与每项允许工法，中标转批复后冻结';
comment on column inquiry.version is '询价版本号，按案件从 1 开始；新版本发布后旧版本置 SUPERSEDED';
comment on column inquiry.deadline is 'UTC 报价截止精确时刻；now<截止方可投标，恰在截止拒绝；截止后方可开标';
comment on column inquiry.status is 'OPEN 投标中 / OPENED 已开标 / FROZEN 已双人复核转批复并冻结 / SUPERSEDED 被新版本取代';

create table if not exists inquiry_item (
    id bigint auto_increment primary key,
    inquiry_id bigint not null,
    damage_item_id bigint not null,
    seq_no int not null,
    iicl_code varchar(16) not null,
    component varchar(64) not null,
    constraint uk_inquiry_item_seq unique (inquiry_id, seq_no)
);
comment on table inquiry_item is '询价固定残损项快照；发布时必须与案件残损项集合一一对应';
comment on column inquiry_item.damage_item_id is '对应 damage_item.id，固定后不随后续变化';
comment on column inquiry_item.seq_no is '询价内项序号，从 1 开始，用于比价排序与复算';

create table if not exists inquiry_item_method (
    inquiry_item_id bigint not null,
    method_code varchar(32) not null,
    primary key (inquiry_item_id, method_code)
);
comment on table inquiry_item_method is '每个询价残损项允许的工法集合；非空，投标工法须在集合内';

create table if not exists bid (
    id bigint auto_increment primary key,
    inquiry_id bigint not null,
    supplier_id bigint not null,
    revision int not null,
    total_amount_cents bigint not null,
    status varchar(24) not null,
    exclude_reason varchar(48) null,
    request_id varchar(64) not null,
    created_at timestamp not null,
    constraint uk_bid_revision unique (inquiry_id, supplier_id, revision)
);
comment on table bid is '报价版本（一供应商一询价版本一行当前报价，修订全量保留历史）';
comment on column bid.revision is '报价修订号，从 1 开始，每次成功修订加 1；expectedVersion 校验当前修订号';
comment on column bid.total_amount_cents is '全部项金额合计，正整数分；各项金额亦为正整数分';
comment on column bid.status is 'CURRENT 当前 / SUPERSEDED 已被修订取代 / FROZEN_VALID 开标冻结且有效 / FROZEN_EXCLUDED 开标冻结但排除';
comment on column bid.exclude_reason is '开标排除原因：SUPPLIER_QUALIFICATION_EXPIRED 资格到期 / SUPPLIER_QUALIFICATION_REVOKED 资格已撤销；NULL 表示有效';

create table if not exists bid_item (
    id bigint auto_increment primary key,
    bid_id bigint not null,
    inquiry_item_id bigint not null,
    method_code varchar(32) not null,
    amount_cents bigint not null
);
comment on table bid_item is '报价逐项明细；整版覆盖全部询价项，金额为正整数分，工法须在允许集合且供应商适用';
comment on column bid_item.amount_cents is '该项报价，正整数分，必须大于 0';

create table if not exists bid_opening (
    id bigint auto_increment primary key,
    inquiry_id bigint not null,
    opened_at timestamp not null,
    winner_bid_id bigint null,
    winner_supplier_code varchar(32) null,
    request_id varchar(64) not null,
    created_at timestamp not null,
    constraint uk_opening_inquiry unique (inquiry_id)
);
comment on table bid_opening is '一次询价版本仅一个开标结果；重复开标返回首次清单，不因后续资格变化重算';
comment on column bid_opening.opened_at is '开标时刻（UTC），资格有效性按该时刻判断';
comment on column bid_opening.winner_bid_id is '中标报价 ID；零有效报价时为 NULL，不得转批复';

create table if not exists bid_opening_line (
    id bigint auto_increment primary key,
    opening_id bigint not null,
    bid_id bigint not null,
    supplier_id bigint not null,
    supplier_code varchar(32) not null,
    revision int not null,
    total_amount_cents bigint not null,
    qualified boolean not null,
    qualification_version int null,
    exclude_reason varchar(48) null,
    rank_no int null
);
comment on table bid_opening_line is '开标清单：全部当前报价冻结留痕，排除项记录原因，有效项按总价升序、供应商编号升序排名';
comment on column bid_opening_line.qualified is 'true 开标时资格有效 / false 开标时资格已失效或撤销';
comment on column bid_opening_line.qualification_version is '开标时刻判定有效的资格版本号，双人复核按此版本精确冻结；被排除项为 NULL';
comment on column bid_opening_line.rank_no is '有效报价排名，从 1 开始；被排除项为 NULL';

create table if not exists repair_award (
    id bigint auto_increment primary key,
    case_id bigint not null,
    inquiry_id bigint not null,
    opening_id bigint not null,
    winner_bid_id bigint not null,
    supplier_id bigint not null,
    supplier_code varchar(32) not null,
    qualification_version int not null,
    qual_valid_from timestamp not null,
    qual_valid_to timestamp not null,
    reviewer1 varchar(64) not null,
    reviewer2 varchar(64) not null,
    approval_id bigint not null,
    request_id varchar(64) not null,
    created_at timestamp not null,
    constraint uk_repair_award_case unique (case_id),
    constraint uk_repair_award_inquiry unique (inquiry_id)
);
comment on table repair_award is '双人不同复核后中标报价转原案修理批复的凭证；同时冻结询价、报价与资格版本快照';
comment on column repair_award.qualification_version is '中标供应商开标时有效的资格版本号，冻结后不再变化';
comment on column repair_award.qual_valid_from is '冻结的资格生效时刻（UTC）';
comment on column repair_award.qual_valid_to is '冻结的资格失效时刻（UTC，不含）';
comment on column repair_award.reviewer1 is '第一复核人，非空且必须与第二复核人不同';
comment on column repair_award.reviewer2 is '第二复核人，非空且必须与第一复核人不同';
comment on column repair_award.approval_id is '生成的原案 repair_approval.id，沿用既有修理放行链';
