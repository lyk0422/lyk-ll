-- 印染配方放大与缸差控制服务：H2 (MODE=MySQL) 建表脚本
-- 金额/重量类一律使用 decimal；时间戳为 JVM 默认时区（应用层按 Asia/Shanghai 输出）

-- 染化料主数据与库存（库存单位：克 gram）
create table if not exists dyestuff (
    id           bigint auto_increment primary key,
    code         varchar(64)  not null comment '染化料编码',
    name         varchar(128) not null comment '染化料名称',
    stock_grams  decimal(14,3) not null comment '当前库存克重，单位克；不允许为负',
    updated_at   timestamp     not null comment '最近库存变更时间'
);
create unique index if not exists uk_dyestuff_code on dyestuff (code);

-- 配方（配方头，按版本保存；发布后冻结）
create table if not exists recipe (
    id            bigint auto_increment primary key,
    recipe_code   varchar(64)   not null comment '配方业务编码，同编码多版本',
    version       int           not null comment '版本号，从1递增',
    name          varchar(128)  not null comment '配方名称',
    liquor_ratio  int           not null comment '浴比 1:N 中的 N（液量升/织物千克）',
    status        varchar(16)   not null comment '状态：DRAFT 草稿 / PUBLISHED 已发布冻结',
    created_at    timestamp     not null comment '创建时间',
    published_at  timestamp     null comment '发布时间，空值表示未发布'
);
create unique index if not exists uk_recipe_code_version on recipe (recipe_code, version);

-- 配方条目：小样浓度（克/升）
create table if not exists recipe_item (
    id            bigint auto_increment primary key,
    recipe_id     bigint        not null comment '所属配方版本主键',
    dyestuff_code varchar(64)   not null comment '染化料编码',
    grams_per_liter decimal(12,4) not null comment '小样浓度，单位克/升',
    seq_no        int           not null comment '序号',
    constraint fk_item_recipe foreign key (recipe_id) references recipe (id)
);
create unique index if not exists uk_recipe_item on recipe_item (recipe_id, dyestuff_code);

-- 染缸（设备）
create table if not exists dye_vat (
    id             bigint auto_increment primary key,
    vat_code       varchar(64)  not null comment '染缸编码',
    capacity_kg    decimal(10,2) not null comment '额定容量，单位千克织物',
    active_run_id  bigint       null comment '当前执行中缸次主键；空值表示空闲，并发排缸依赖该列条件更新唯一受理'
);
create unique index if not exists uk_dye_vat_code on dye_vat (vat_code);
create unique index if not exists uk_dye_vat_active_run on dye_vat (active_run_id);

-- 缸次（一次染色执行）
create table if not exists vat_run (
    id              bigint auto_increment primary key,
    run_no          varchar(64)  not null comment '缸次业务编号',
    vat_id          bigint       not null comment '占用染缸主键',
    recipe_id       bigint       not null comment '投用配方版本主键（冻结快照）',
    recipe_code     varchar(64)  not null comment '投用配方编码（冗余便于缸差复算）',
    recipe_version  int          not null comment '投用配方版本',
    liquor_ratio    int          not null comment '实际浴比 N，须与配方一致',
    fabric_kg       decimal(10,2) not null comment '本缸织物重量，单位千克',
    merged_run_no   varchar(64)  null comment '并缸伙伴缸次编号，空值表示未并缸',
    status          varchar(24)  not null comment 'PLANNED 已排缸 / RUNNING 投料执行中 / DISCHARGED 已出缸待判 / REPAIRED 已回修 / GRADED_DOWN 已降级 / ARCHIVED 判定归档终态',
    repair_count    int          not null default 0 comment '已回修次数，最多允许1次',
    out_delta_e     decimal(8,4) null comment '出缸测色 ΔE（与标准样差值），空值表示尚未测色',
    final_disposition varchar(24) null comment '最终处置：PASS 合格 / REPAIR 回修 / GRADED_DOWN 降级；归档前为空',
    judged_at       timestamp    null comment '最近一次缸差判定时间',
    created_at      timestamp    not null comment '排缸时间'
);
create unique index if not exists uk_vat_run_no on vat_run (run_no);
create index if not exists idx_vat_run_recipe on vat_run (recipe_code);

-- 对色记录（打小样对色）
create table if not exists color_assessment (
    id            bigint auto_increment primary key,
    request_id    varchar(64)  not null comment '对色幂等键',
    sample_code   varchar(64)  not null comment '小样编号',
    std_l         decimal(9,4) not null comment '标准样 L*',
    std_a         decimal(9,4) not null comment '标准样 a*',
    std_b         decimal(9,4) not null comment '标准样 b*',
    sample_l      decimal(9,4) not null comment '实测样 L*',
    sample_a      decimal(9,4) not null comment '实测样 a*',
    sample_b      decimal(9,4) not null comment '实测样 b*',
    delta_e       decimal(9,4) not null comment 'CIE76 色差 ΔE',
    passed        boolean      not null comment '是否放行：ΔE<=0.8 合格',
    created_at    timestamp    not null comment '对色时间'
);
create unique index if not exists uk_color_request on color_assessment (request_id);

-- 称量链（放大配方后的投料称量明细，可按缸次复算）
create table if not exists weigh_record (
    id              bigint auto_increment primary key,
    run_id          bigint        not null comment '所属缸次',
    request_id      varchar(64)   not null comment '投料幂等键',
    dyestuff_code   varchar(64)   not null comment '染化料编码',
    planned_grams   decimal(14,3) not null comment '按浴比放大的计划克重，单位克',
    actual_grams    decimal(14,3) not null comment '实际称量克重，单位克',
    deviation_ratio decimal(10,6) not null comment '称量偏差比 = |实际-计划|/计划，合法上限0.005',
    accepted        boolean       not null comment '本次称量是否受理',
    created_at      timestamp     not null comment '称量时间'
);
create index if not exists idx_weigh_run on weigh_record (run_id);
create unique index if not exists uk_weigh_request on weigh_record (request_id);

-- 库存台账（每次扣减/回补都留痕，可复算库存）
create table if not exists stock_movement (
    id            bigint auto_increment primary key,
    request_id    varchar(64)  not null comment '触发库存变动的幂等键',
    dyestuff_code varchar(64)  not null comment '染化料编码',
    change_grams  decimal(14,3) not null comment '变动克重，投料扣减为负、回补为正，单位克',
    balance_grams decimal(14,3) not null comment '变动后余额，单位克',
    run_no        varchar(64)  null comment '关联缸次编号',
    reason        varchar(32)  not null comment 'CONSUME 投料消耗 / REJECT_REFUND 拒绝回补',
    created_at    timestamp     not null comment '台账时间'
);
create index if not exists idx_stock_dyestuff on stock_movement (dyestuff_code);
create unique index if not exists uk_stock_request_dye on stock_movement (request_id, dyestuff_code);

-- 缸差判定台账（可复算：同配方相邻缸次出缸 ΔE 差值与处置）
create table if not exists lot_judgement (
    id               bigint auto_increment primary key,
    run_id           bigint       not null comment '被判定缸次',
    run_no           varchar(64)  not null comment '被判定缸次编号',
    recipe_code      varchar(64)  not null comment '配方编码',
    baseline_run_no  varchar(64)  null comment '对照缸次编号（同配方基准缸）',
    delta_e_gap      decimal(9,4) null comment '两缸出缸 ΔE 差值绝对值',
    result           varchar(24)  not null comment 'PASS 合格 / REPAIR 回修 / GRADED_DOWN 降级 / ARCHIVED 归档',
    repair_count     int          not null comment '判定后该缸累计回修次数',
    request_id       varchar(64)  not null comment '判定/归档幂等键',
    created_at       timestamp    not null comment '判定时间'
);
create index if not exists idx_lot_run on lot_judgement (run_id);

-- 迟到测色事件（归档后到达的测色数据仅登记事件）
create table if not exists late_color_event (
    id          bigint auto_increment primary key,
    run_id      bigint       not null comment '目标缸次',
    run_no      varchar(64)  not null comment '目标缸次编号',
    delta_e     decimal(9,4) not null comment '迟到的测色 ΔE',
    created_at  timestamp    not null comment '事件登记时间'
);
create index if not exists idx_late_run on late_color_event (run_id);

-- 幂等请求登记：requestId 唯一；指纹覆盖 requestId 与全部业务字段
create table if not exists idempotent_request (
    request_id   varchar(64)  primary key comment '幂等键',
    operation    varchar(32)  not null comment '操作类型：COLOR_MATCH/FEED/JUDGE/ARCHIVE',
    fingerprint  varchar(128) not null comment 'requestId+全部业务字段的 SHA-256 指纹',
    status_code  int          not null comment '首次受理时返回的 HTTP 状态码',
    response_clob clob        not null comment '首次结果 JSON，同键重放原样返回',
    created_at   timestamp    not null comment '首次受理时间'
);
