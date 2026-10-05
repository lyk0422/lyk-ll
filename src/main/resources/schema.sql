-- 渔业捕捞配额与渔捞日志核销：H2 内存库（MODE=MySQL）建表脚本，仅合成数据。
-- 所有重量单位千克（DECIMAL(14,3)），时刻为 UTC 时间线（TIMESTAMP），日期为 UTC 日界（DATE）。
-- 中文 COMMENT 使用 H2 的 COMMENT ON 语法（MySQL 兼容模式下不支持列级内联 COMMENT）。

-- 渔船台账（合成）
CREATE TABLE IF NOT EXISTS vessel (
    code        VARCHAR(32)  NOT NULL PRIMARY KEY,
    name        VARCHAR(64)  NOT NULL,
    created_at  TIMESTAMP(6) NOT NULL
);
COMMENT ON TABLE vessel IS '渔船台账（合成数据）';
COMMENT ON COLUMN vessel.code IS '渔船编号';
COMMENT ON COLUMN vessel.name IS '渔船名称';
COMMENT ON COLUMN vessel.created_at IS '建档时刻（UTC）';

-- 鱼种台账（合成），protected_species=TRUE 表示兼捕保护鱼种
CREATE TABLE IF NOT EXISTS species (
    code               VARCHAR(32)  NOT NULL PRIMARY KEY,
    name               VARCHAR(64)  NOT NULL,
    protected_species  BOOLEAN      NOT NULL
);
COMMENT ON TABLE species IS '鱼种台账（合成数据）';
COMMENT ON COLUMN species.code IS '鱼种编号';
COMMENT ON COLUMN species.name IS '鱼种名称';
COMMENT ON COLUMN species.protected_species IS '是否兼捕保护鱼种：TRUE 是，FALSE 否';

-- 年度配额台账：船＋鱼种＋UTC 年度；used_kg 为已结案核销扣减重量（千克）
CREATE TABLE IF NOT EXISTS quota (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    vessel_code  VARCHAR(32)   NOT NULL,
    species_code VARCHAR(32)   NOT NULL,
    quota_year   INT           NOT NULL,
    quota_kg     DECIMAL(14,3) NOT NULL,
    used_kg      DECIMAL(14,3) NOT NULL DEFAULT 0,
    version      INT           NOT NULL DEFAULT 0,
    CONSTRAINT uq_quota_vessel_species_year UNIQUE (vessel_code, species_code, quota_year)
);
COMMENT ON TABLE quota IS '年度配额台账：船＋鱼种＋UTC 年度';
COMMENT ON COLUMN quota.vessel_code IS '渔船编号';
COMMENT ON COLUMN quota.species_code IS '鱼种编号';
COMMENT ON COLUMN quota.quota_year IS '配额年度（UTC）';
COMMENT ON COLUMN quota.quota_kg IS '年度核定配额（千克）';
COMMENT ON COLUMN quota.used_kg IS '已核销扣减重量（千克）';
COMMENT ON COLUMN quota.version IS '乐观锁版本号';

-- 航次台账：DEPARTED→(IN_REVIEW)→DISCHARGED→CLOSED；frozen=TRUE 表示该船复核期间余额冻结
CREATE TABLE IF NOT EXISTS trip (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    trip_code   VARCHAR(48)  NOT NULL,
    vessel_code VARCHAR(32)  NOT NULL,
    depart_at   TIMESTAMP(6) NOT NULL,
    landed_at   TIMESTAMP(6) NULL,
    status      VARCHAR(16)  NOT NULL,
    frozen      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP(6) NOT NULL,
    updated_at  TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_trip_code UNIQUE (trip_code)
);
CREATE INDEX IF NOT EXISTS idx_trip_vessel_status ON trip (vessel_code, status);
COMMENT ON TABLE trip IS '航次台账';
COMMENT ON COLUMN trip.trip_code IS '航次业务编号';
COMMENT ON COLUMN trip.vessel_code IS '渔船编号';
COMMENT ON COLUMN trip.depart_at IS '出港时刻（UTC）';
COMMENT ON COLUMN trip.landed_at IS '进港时刻（UTC），未进港为空';
COMMENT ON COLUMN trip.status IS '航次状态：DEPARTED/DISCHARGED/IN_REVIEW/CLOSED';
COMMENT ON COLUMN trip.frozen IS '复核冻结标志：TRUE 冻结中，FALSE 正常';
COMMENT ON COLUMN trip.created_at IS '创建时刻（UTC）';
COMMENT ON COLUMN trip.updated_at IS '最近更新时刻（UTC）';

-- 在航护栏：每艘渔船至多一个未结案航次；出港时插入，结案时删除（数据库级唯一受理）
CREATE TABLE IF NOT EXISTS vessel_active_trip (
    vessel_code VARCHAR(32) NOT NULL PRIMARY KEY,
    trip_code   VARCHAR(48) NOT NULL
);
COMMENT ON TABLE vessel_active_trip IS '在航护栏：每艘渔船至多一个未结案航次';
COMMENT ON COLUMN vessel_active_trip.vessel_code IS '渔船编号（进行中航次唯一）';
COMMENT ON COLUMN vessel_active_trip.trip_code IS '当前进行中航次业务编号';

-- 出港申报分鱼种数量
CREATE TABLE IF NOT EXISTS departure_line (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    trip_code   VARCHAR(48)   NOT NULL,
    species_code VARCHAR(32)  NOT NULL,
    weight_kg   DECIMAL(14,3) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_dep_line_trip ON departure_line (trip_code);
COMMENT ON TABLE departure_line IS '出港申报分鱼种数量';
COMMENT ON COLUMN departure_line.trip_code IS '航次业务编号';
COMMENT ON COLUMN departure_line.species_code IS '鱼种编号';
COMMENT ON COLUMN departure_line.weight_kg IS '申报捕捞量（千克）';

-- 渔捞日志：按 UTC 日界归属，按“船＋日期＋渔区”唯一去重
CREATE TABLE IF NOT EXISTS logbook (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    trip_code    VARCHAR(48)   NOT NULL,
    vessel_code  VARCHAR(32)   NOT NULL,
    zone_code    VARCHAR(32)   NOT NULL,
    log_date     DATE          NOT NULL,
    fishing_at   TIMESTAMP(6)  NOT NULL,
    total_kg     DECIMAL(14,3) NOT NULL,
    bycatch_flag BOOLEAN       NOT NULL DEFAULT FALSE,
    late         BOOLEAN       NOT NULL DEFAULT FALSE,
    seq_no       INT           NOT NULL,
    created_at   TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_log_vessel_date_zone UNIQUE (vessel_code, log_date, zone_code)
);
CREATE INDEX IF NOT EXISTS idx_log_trip_time ON logbook (trip_code, fishing_at);
COMMENT ON TABLE logbook IS '渔捞日志：按 UTC 日界归属，按船＋日期＋渔区唯一去重';
COMMENT ON COLUMN logbook.trip_code IS '航次业务编号';
COMMENT ON COLUMN logbook.vessel_code IS '渔船编号';
COMMENT ON COLUMN logbook.zone_code IS '渔区编号';
COMMENT ON COLUMN logbook.log_date IS '作业归属日期（UTC 日界）';
COMMENT ON COLUMN logbook.fishing_at IS '实际作业时刻（UTC）';
COMMENT ON COLUMN logbook.total_kg IS '本日志总渔获量（千克）';
COMMENT ON COLUMN logbook.bycatch_flag IS '兼捕保护鱼种超 5% 单独标注：TRUE 是，FALSE 否';
COMMENT ON COLUMN logbook.late IS '结案后迟到日志标志：TRUE 迟到（仅入审查队列）';
COMMENT ON COLUMN logbook.seq_no IS '航次内按作业时刻排序的日志链序号（乱序归位）';
COMMENT ON COLUMN logbook.created_at IS '提交时刻（UTC）';

-- 渔捞日志分鱼种数量
CREATE TABLE IF NOT EXISTS logbook_line (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    logbook_id  BIGINT        NOT NULL,
    species_code VARCHAR(32)  NOT NULL,
    weight_kg   DECIMAL(14,3) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_log_line_logbook ON logbook_line (logbook_id);
COMMENT ON TABLE logbook_line IS '渔捞日志分鱼种数量';
COMMENT ON COLUMN logbook_line.logbook_id IS '日志主键';
COMMENT ON COLUMN logbook_line.species_code IS '鱼种编号';
COMMENT ON COLUMN logbook_line.weight_kg IS '该鱼种渔获量（千克）';

-- 结案后迟到日志事件（不参与核销，只入审查队列）
CREATE TABLE IF NOT EXISTS late_log_event (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    trip_code   VARCHAR(48)   NOT NULL,
    vessel_code VARCHAR(32)   NOT NULL,
    zone_code   VARCHAR(32)   NOT NULL,
    log_date    DATE          NOT NULL,
    fishing_at  TIMESTAMP(6)  NOT NULL,
    payload     CLOB          NOT NULL,
    created_at  TIMESTAMP(6)  NOT NULL
);
COMMENT ON TABLE late_log_event IS '结案后迟到日志事件（不参与核销，只入审查队列）';
COMMENT ON COLUMN late_log_event.trip_code IS '航次业务编号';
COMMENT ON COLUMN late_log_event.vessel_code IS '渔船编号';
COMMENT ON COLUMN late_log_event.zone_code IS '渔区编号';
COMMENT ON COLUMN late_log_event.log_date IS '作业归属日期（UTC 日界）';
COMMENT ON COLUMN late_log_event.fishing_at IS '实际作业时刻（UTC）';
COMMENT ON COLUMN late_log_event.payload IS '迟到日志原始报文（JSON）';
COMMENT ON COLUMN late_log_event.created_at IS '收到时刻（UTC）';

-- 进港卸渔报告
CREATE TABLE IF NOT EXISTS landing (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    trip_code             VARCHAR(48)   NOT NULL,
    landed_at             TIMESTAMP(6)  NOT NULL,
    total_kg              DECIMAL(14,3) NOT NULL,
    deviation_pct         DECIMAL(9,4)  NOT NULL,
    review_triggered      BOOLEAN       NOT NULL DEFAULT FALSE,
    deviation_explanation VARCHAR(512)  NULL,
    created_at            TIMESTAMP(6)  NOT NULL,
    CONSTRAINT uq_landing_trip UNIQUE (trip_code)
);
COMMENT ON TABLE landing IS '进港卸渔报告';
COMMENT ON COLUMN landing.trip_code IS '航次业务编号';
COMMENT ON COLUMN landing.landed_at IS '进港时刻（UTC）';
COMMENT ON COLUMN landing.total_kg IS '卸渔总量（千克）';
COMMENT ON COLUMN landing.deviation_pct IS '与日志累计量最大鱼种偏差（百分比，3.0000 表示 3%）';
COMMENT ON COLUMN landing.review_triggered IS '偏差超 3% 转复核：TRUE 是，FALSE 否';
COMMENT ON COLUMN landing.deviation_explanation IS '差异说明（偏差超 3% 时必填）';
COMMENT ON COLUMN landing.created_at IS '提交时刻（UTC）';

-- 进港卸渔分鱼种数量
CREATE TABLE IF NOT EXISTS landing_line (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    landing_id  BIGINT        NOT NULL,
    species_code VARCHAR(32)  NOT NULL,
    weight_kg   DECIMAL(14,3) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_land_line_landing ON landing_line (landing_id);
COMMENT ON TABLE landing_line IS '进港卸渔分鱼种数量';
COMMENT ON COLUMN landing_line.landing_id IS '卸渔报告主键';
COMMENT ON COLUMN landing_line.species_code IS '鱼种编号';
COMMENT ON COLUMN landing_line.weight_kg IS '该鱼种卸渔量（千克）';

-- 航次分鱼种口径量（核销依据）：logged_kg 日志累计，landed_kg 卸渔量，final_kg 最终口径
CREATE TABLE IF NOT EXISTS trip_catch_line (
    trip_code    VARCHAR(48)   NOT NULL,
    species_code VARCHAR(32)   NOT NULL,
    logged_kg    DECIMAL(14,3) NOT NULL DEFAULT 0,
    landed_kg    DECIMAL(14,3) NOT NULL DEFAULT 0,
    final_kg     DECIMAL(14,3) NOT NULL DEFAULT 0,
    CONSTRAINT pk_trip_catch PRIMARY KEY (trip_code, species_code)
);
COMMENT ON TABLE trip_catch_line IS '航次分鱼种口径量（核销依据）';
COMMENT ON COLUMN trip_catch_line.trip_code IS '航次业务编号';
COMMENT ON COLUMN trip_catch_line.species_code IS '鱼种编号';
COMMENT ON COLUMN trip_catch_line.logged_kg IS '日志累计量（千克）';
COMMENT ON COLUMN trip_catch_line.landed_kg IS '进港卸渔量（千克）';
COMMENT ON COLUMN trip_catch_line.final_kg IS '核销最终口径量（千克）';

-- 人工复核案件
CREATE TABLE IF NOT EXISTS review_case (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    trip_code   VARCHAR(48)  NOT NULL,
    vessel_code VARCHAR(32)  NOT NULL,
    reason      VARCHAR(24)  NOT NULL,
    status      VARCHAR(8)   NOT NULL,
    conclusion  VARCHAR(24)  NULL,
    note        VARCHAR(512) NULL,
    created_at  TIMESTAMP(6) NOT NULL,
    decided_at  TIMESTAMP(6) NULL
);
CREATE INDEX IF NOT EXISTS idx_review_vessel_status ON review_case (vessel_code, status);
CREATE INDEX IF NOT EXISTS idx_review_trip_status ON review_case (trip_code, status);
COMMENT ON TABLE review_case IS '人工复核案件';
COMMENT ON COLUMN review_case.trip_code IS '航次业务编号';
COMMENT ON COLUMN review_case.vessel_code IS '渔船编号';
COMMENT ON COLUMN review_case.reason IS '触发原因：BYCATCH_RATIO/LANDING_DEVIATION/LATE_LOG';
COMMENT ON COLUMN review_case.status IS '状态：OPEN/CLOSED';
COMMENT ON COLUMN review_case.conclusion IS '最终结论：CONFIRM_LANDED/CONFIRM_LOGGED/ADJUSTED';
COMMENT ON COLUMN review_case.note IS '复核备注/差异说明';
COMMENT ON COLUMN review_case.created_at IS '立案时刻（UTC）';
COMMENT ON COLUMN review_case.decided_at IS '结论时刻（UTC），未决为空';

-- 配额核销明细（台账可复算）
CREATE TABLE IF NOT EXISTS settlement (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    trip_code   VARCHAR(48)   NOT NULL,
    vessel_code VARCHAR(32)   NOT NULL,
    quota_year  INT           NOT NULL,
    species_code VARCHAR(32)  NOT NULL,
    deducted_kg DECIMAL(14,3) NOT NULL,
    created_at  TIMESTAMP(6)  NOT NULL,
    CONSTRAINT uq_settlement_trip_species UNIQUE (trip_code, species_code)
);
COMMENT ON TABLE settlement IS '配额核销明细（台账可复算）';
COMMENT ON COLUMN settlement.trip_code IS '航次业务编号';
COMMENT ON COLUMN settlement.vessel_code IS '渔船编号';
COMMENT ON COLUMN settlement.quota_year IS '核销年度（UTC）';
COMMENT ON COLUMN settlement.species_code IS '鱼种编号';
COMMENT ON COLUMN settlement.deducted_kg IS '本次核销扣减量（千克）';
COMMENT ON COLUMN settlement.created_at IS '核销时刻（UTC）';

-- 幂等请求台账：request_id 主键，指纹覆盖 requestId 之外全部业务字段
CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id    VARCHAR(64)  NOT NULL PRIMARY KEY,
    operation     VARCHAR(24)  NOT NULL,
    fingerprint   CHAR(64)     NOT NULL,
    response_json CLOB         NULL,
    created_at    TIMESTAMP(6) NOT NULL
);
COMMENT ON TABLE idempotency_record IS '幂等请求台账：request_id 主键';
COMMENT ON COLUMN idempotency_record.request_id IS '幂等键 requestId';
COMMENT ON COLUMN idempotency_record.operation IS '操作类型';
COMMENT ON COLUMN idempotency_record.fingerprint IS '业务参数指纹（SHA-256 十六进制）';
COMMENT ON COLUMN idempotency_record.response_json IS '首次成功结果快照（JSON），受理中为空';
COMMENT ON COLUMN idempotency_record.created_at IS '首次受理时刻（UTC）';

-- 跨船配额调剂单：同鱼种同年度，双方确认后生效；PENDING→EFFECTIVE/EXPIRED
CREATE TABLE IF NOT EXISTS quota_transfer (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id       VARCHAR(64)   NOT NULL,
    from_vessel_code VARCHAR(32)   NOT NULL,
    to_vessel_code   VARCHAR(32)   NOT NULL,
    species_code     VARCHAR(32)   NOT NULL,
    quota_year       INT           NOT NULL,
    amount_kg        DECIMAL(14,3) NOT NULL,
    status           VARCHAR(12)   NOT NULL,
    created_at       TIMESTAMP(6)  NOT NULL,
    expires_at       TIMESTAMP(6)  NOT NULL,
    confirmed_at     TIMESTAMP(6)  NULL,
    CONSTRAINT uq_transfer_request UNIQUE (request_id)
);
CREATE INDEX IF NOT EXISTS idx_transfer_vessels ON quota_transfer (from_vessel_code, to_vessel_code, status);
COMMENT ON TABLE quota_transfer IS '跨船配额调剂单：同鱼种同年度，双方确认后生效';
COMMENT ON COLUMN quota_transfer.request_id IS '发起方幂等键 requestId';
COMMENT ON COLUMN quota_transfer.from_vessel_code IS '调出渔船编号';
COMMENT ON COLUMN quota_transfer.to_vessel_code IS '调入渔船编号';
COMMENT ON COLUMN quota_transfer.species_code IS '鱼种编号';
COMMENT ON COLUMN quota_transfer.quota_year IS '配额年度（UTC）';
COMMENT ON COLUMN quota_transfer.amount_kg IS '调剂量（千克），不得超调出船剩余额度';
COMMENT ON COLUMN quota_transfer.status IS '状态：PENDING 待确认/EFFECTIVE 已生效/EXPIRED 已失效';
COMMENT ON COLUMN quota_transfer.created_at IS '发起时刻（UTC）';
COMMENT ON COLUMN quota_transfer.expires_at IS '确认截止时刻（UTC，发起后 48 小时，恰满即失效）';
COMMENT ON COLUMN quota_transfer.confirmed_at IS '对方确认生效时刻（UTC），未确认为空';

-- 调剂在途护栏：同船同鱼种同年度至多一张进行中调剂单（数据库级唯一受理）
CREATE TABLE IF NOT EXISTS transfer_active_guard (
    vessel_code VARCHAR(32) NOT NULL,
    species_code VARCHAR(32) NOT NULL,
    quota_year  INT          NOT NULL,
    transfer_id BIGINT       NOT NULL,
    CONSTRAINT pk_transfer_guard PRIMARY KEY (vessel_code, species_code, quota_year)
);
COMMENT ON TABLE transfer_active_guard IS '调剂在途护栏：同船同鱼种同年度至多一张进行中调剂单';
COMMENT ON COLUMN transfer_active_guard.vessel_code IS '参与调剂渔船编号（调出与调入各占一行）';
COMMENT ON COLUMN transfer_active_guard.species_code IS '鱼种编号';
COMMENT ON COLUMN transfer_active_guard.quota_year IS '配额年度（UTC）';
COMMENT ON COLUMN transfer_active_guard.transfer_id IS '进行中调剂单主键';

-- 产地预警台账：船＋鱼种＋年度核销用量达到配额 90% 触发；已触发额度不得结转
CREATE TABLE IF NOT EXISTS origin_alert (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    vessel_code  VARCHAR(32)  NOT NULL,
    species_code VARCHAR(32)  NOT NULL,
    quota_year   INT          NOT NULL,
    usage_pct    DECIMAL(9,4) NOT NULL,
    triggered_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_alert_vessel_species_year UNIQUE (vessel_code, species_code, quota_year)
);
COMMENT ON TABLE origin_alert IS '产地预警台账：核销用量达年度配额 90% 触发，已触发额度不得结转';
COMMENT ON COLUMN origin_alert.vessel_code IS '渔船编号';
COMMENT ON COLUMN origin_alert.species_code IS '鱼种编号';
COMMENT ON COLUMN origin_alert.quota_year IS '配额年度（UTC）';
COMMENT ON COLUMN origin_alert.usage_pct IS '触发时用量占比（百分比，90.0000 表示 90%）';
COMMENT ON COLUMN origin_alert.triggered_at IS '触发时刻（UTC）';

-- 年度结转台账：上年未用配额按不超过 20% 结转至次年，次年台账单列并优先核销
CREATE TABLE IF NOT EXISTS quota_carryover (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    vessel_code  VARCHAR(32)   NOT NULL,
    species_code VARCHAR(32)   NOT NULL,
    quota_year   INT           NOT NULL,
    source_year  INT           NOT NULL,
    carried_kg   DECIMAL(14,3) NOT NULL,
    used_kg      DECIMAL(14,3) NOT NULL DEFAULT 0,
    created_at   TIMESTAMP(6)  NOT NULL,
    CONSTRAINT uq_carryover_vessel_species_year UNIQUE (vessel_code, species_code, quota_year)
);
COMMENT ON TABLE quota_carryover IS '年度结转台账：上年未用配额按不超过 20% 结转至次年，次年单列优先核销';
COMMENT ON COLUMN quota_carryover.vessel_code IS '渔船编号';
COMMENT ON COLUMN quota_carryover.species_code IS '鱼种编号';
COMMENT ON COLUMN quota_carryover.quota_year IS '结转目标年度（UTC，次年）';
COMMENT ON COLUMN quota_carryover.source_year IS '结转来源年度（UTC）';
COMMENT ON COLUMN quota_carryover.carried_kg IS '结转额度（千克），不超过上年配额 20%（恰 20% 合法）';
COMMENT ON COLUMN quota_carryover.used_kg IS '次年已优先核销的结转额度（千克）';
COMMENT ON COLUMN quota_carryover.created_at IS '结转时刻（UTC）';

-- 结转额度核销明细（与年度配额核销分账，台账可复算）
CREATE TABLE IF NOT EXISTS carryover_settlement (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    trip_code   VARCHAR(48)   NOT NULL,
    vessel_code VARCHAR(32)   NOT NULL,
    quota_year  INT           NOT NULL,
    species_code VARCHAR(32)  NOT NULL,
    deducted_kg DECIMAL(14,3) NOT NULL,
    created_at  TIMESTAMP(6)  NOT NULL,
    CONSTRAINT uq_carry_settlement_trip_species UNIQUE (trip_code, species_code)
);
COMMENT ON TABLE carryover_settlement IS '结转额度核销明细（次年优先核销，与年度配额分账）';
COMMENT ON COLUMN carryover_settlement.trip_code IS '航次业务编号';
COMMENT ON COLUMN carryover_settlement.vessel_code IS '渔船编号';
COMMENT ON COLUMN carryover_settlement.quota_year IS '核销年度（UTC，结转目标年度）';
COMMENT ON COLUMN carryover_settlement.species_code IS '鱼种编号';
COMMENT ON COLUMN carryover_settlement.deducted_kg IS '本次核销的结转额度（千克）';
COMMENT ON COLUMN carryover_settlement.created_at IS '核销时刻（UTC）';
