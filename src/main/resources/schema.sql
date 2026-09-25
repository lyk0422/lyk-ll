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
