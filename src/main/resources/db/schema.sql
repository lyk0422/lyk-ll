-- 水泥窑替代燃料掺烧服务：H2（MODE=MySQL）建表脚本
-- 所有时间均为 Asia/Shanghai 本地时间；质量单位 kg，浓度单位 mg/Nm3，比例为小数（0.40 表示 40%）

CREATE TABLE IF NOT EXISTS hazardous_waste_license (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    license_no  VARCHAR(64)  NOT NULL,
    holder      VARCHAR(128) NOT NULL,
    valid_from  DATE         NOT NULL,
    valid_to    DATE         NOT NULL,
    remark      VARCHAR(255),
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_license_no UNIQUE (license_no)
);
COMMENT ON TABLE hazardous_waste_license IS '危废经营许可合成库，valid_to 含当日有效，早于当前日期即过期';

CREATE TABLE IF NOT EXISTS co_firing_plan (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id         VARCHAR(64)  NOT NULL,
    plan_no            VARCHAR(64)  NOT NULL,
    kiln_id            VARCHAR(32)  NOT NULL,
    license_id         BIGINT       NULL,
    substitution_rate  DECIMAL(6,4) NOT NULL,
    status             VARCHAR(16)  NOT NULL,
    planned_start      TIMESTAMP    NOT NULL,
    planned_end        TIMESTAMP    NOT NULL,
    reject_reason      VARCHAR(500),
    reject_category    VARCHAR(32),
    submitted_at       TIMESTAMP,
    reviewed_at        TIMESTAMP,
    started_at         TIMESTAMP,
    stopped_at         TIMESTAMP,
    banned_at          TIMESTAMP,
    sealed_at          TIMESTAMP,
    sealed_date        DATE,
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_plan_request_id UNIQUE (request_id),
    CONSTRAINT uk_plan_plan_no UNIQUE (plan_no),
    CONSTRAINT fk_plan_license FOREIGN KEY (license_id) REFERENCES hazardous_waste_license (id)
);
COMMENT ON TABLE co_firing_plan IS '掺烧方案主表，状态流转 SUBMITTED→APPROVED/REJECTED→EXECUTING→STOPPED/BANNED→SEALED';
COMMENT ON COLUMN co_firing_plan.substitution_rate IS '申报替代燃料热量替代率，上限0.40，恰等于合法';
COMMENT ON COLUMN co_firing_plan.status IS 'SUBMITTED已申报 APPROVED审核通过 REJECTED整单拒绝 EXECUTING执行中 STOPPED已停用 BANNED当日禁烧 SEALED已日结封存(终态)';

CREATE TABLE IF NOT EXISTS plan_fuel (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id         BIGINT        NOT NULL,
    fuel_name       VARCHAR(128)  NOT NULL,
    fuel_category   VARCHAR(16)   NOT NULL,
    high_cl_sulfur  BOOLEAN       NOT NULL DEFAULT FALSE,
    heat_value_mjkg DECIMAL(10,3) NOT NULL,
    mass_kg         DECIMAL(14,3) NOT NULL,
    chlorine_pct    DECIMAL(8,5)  NOT NULL DEFAULT 0,
    sulfur_pct      DECIMAL(8,5)  NOT NULL DEFAULT 0,
    CONSTRAINT fk_fuel_plan FOREIGN KEY (plan_id) REFERENCES co_firing_plan (id)
);
COMMENT ON TABLE plan_fuel IS '方案燃料明细，high_cl_sulfur=TRUE 的高氯硫燃料按质量加权计入入窑氯硫总量';
COMMENT ON COLUMN plan_fuel.heat_value_mjkg IS '燃料低位热值，单位 MJ/kg';
COMMENT ON COLUMN plan_fuel.chlorine_pct IS '氯质量分数百分比，如0.05表示0.05%';

CREATE TABLE IF NOT EXISTS kiln_active_plan (
    kiln_id     VARCHAR(32) PRIMARY KEY,
    plan_id     BIGINT       NOT NULL,
    acquired_at TIMESTAMP    NOT NULL,
    CONSTRAINT uk_active_plan_id UNIQUE (plan_id)
);
COMMENT ON TABLE kiln_active_plan IS '窑当前执行中方案占用锁，同一窑同时刻只允许一个执行中方案，靠主键唯一约束并发受理';

CREATE TABLE IF NOT EXISTS kiln_lock (
    kiln_id VARCHAR(32) PRIMARY KEY
);
COMMENT ON TABLE kiln_lock IS '窑级行锁，申报时 MERGE 后 SELECT FOR UPDATE 串行化同窑并发申报，保证唯一受理';

CREATE TABLE IF NOT EXISTS emission_reading (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id       BIGINT       NOT NULL,
    kiln_id       VARCHAR(32)  NOT NULL,
    measure_point VARCHAR(32)  NOT NULL,
    sample_time   TIMESTAMP    NOT NULL,
    nox           DECIMAL(10,3),
    so2           DECIMAL(10,3),
    dust          DECIMAL(10,3),
    is_late       BOOLEAN      NOT NULL DEFAULT FALSE,
    received_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_reading_dedup UNIQUE (plan_id, measure_point, sample_time),
    CONSTRAINT fk_reading_plan FOREIGN KEY (plan_id) REFERENCES co_firing_plan (id)
);
COMMENT ON TABLE emission_reading IS '排放读数，按 方案+测点+采样时刻 去重，乱序数据按采样时刻归位评估';
COMMENT ON COLUMN emission_reading.nox IS 'NOx小时内瞬时浓度 mg/Nm3，NULL表示该测点未提供该污染物';

CREATE TABLE IF NOT EXISTS hourly_assessment (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id           BIGINT       NOT NULL,
    hour_start        TIMESTAMP    NOT NULL,
    nox_avg           DECIMAL(10,3),
    so2_avg           DECIMAL(10,3),
    dust_avg          DECIMAL(10,3),
    reading_count     INT          NOT NULL DEFAULT 0,
    exceeded          BOOLEAN      NOT NULL DEFAULT FALSE,
    pollutants        VARCHAR(64),
    consecutive_no    INT          NOT NULL DEFAULT 0,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_hour_plan UNIQUE (plan_id, hour_start),
    CONSTRAINT fk_hour_plan FOREIGN KEY (plan_id) REFERENCES co_firing_plan (id)
);
COMMENT ON TABLE hourly_assessment IS '小时评估结果，均值恰等于排放限值判达标(超标仅为严格大于)';
COMMENT ON COLUMN hourly_assessment.consecutive_no IS '截至该小时连续超标序号，达3触发当日禁烧';

CREATE TABLE IF NOT EXISTS reduction_command (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id            BIGINT       NOT NULL,
    hour_start         TIMESTAMP    NOT NULL,
    pollutant          VARCHAR(64),
    hour_avg           DECIMAL(10,3),
    status             VARCHAR(16)  NOT NULL,
    target_rate        DECIMAL(6,4) NOT NULL,
    triggered_at       TIMESTAMP    NOT NULL,
    issued_at          TIMESTAMP,
    confirmed_at       TIMESTAMP,
    escalated_at       TIMESTAMP,
    confirm_request_id VARCHAR(64),
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_command_hour UNIQUE (plan_id, hour_start),
    CONSTRAINT fk_command_plan FOREIGN KEY (plan_id) REFERENCES co_firing_plan (id)
);
COMMENT ON TABLE reduction_command IS '降掺指令链 TRIGGERED→ISSUED→CONFIRMED，超时未确认置ESCALATED并强制降至保底气比例';
COMMENT ON COLUMN reduction_command.status IS 'TRIGGERED已触发 ISSUED已下发 CONFIRMED已执行确认 ESCALATED恰满30分钟未确认自动升级';

CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id    VARCHAR(64) PRIMARY KEY,
    request_type  VARCHAR(32) NOT NULL,
    fingerprint   VARCHAR(64) NOT NULL,
    http_status   INT         NOT NULL,
    response_body CLOB,
    plan_id       BIGINT,
    created_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE idempotent_request IS '申报/确认/切换/封存幂等记录，指纹覆盖requestId与全部业务字段，同键异参冲突';

CREATE TABLE IF NOT EXISTS late_reading_event (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id       BIGINT       NOT NULL,
    measure_point VARCHAR(32)  NOT NULL,
    sample_time   TIMESTAMP    NOT NULL,
    payload       CLOB,
    received_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE late_reading_event IS '方案日结封存后到达的迟到读数，仅登记事件，不再参与评估与日结';

CREATE TABLE IF NOT EXISTS ban_record (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id    BIGINT       NOT NULL,
    kiln_id    VARCHAR(32)  NOT NULL,
    ban_date   DATE         NOT NULL,
    reason     VARCHAR(500) NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ban_plan FOREIGN KEY (plan_id) REFERENCES co_firing_plan (id)
);
COMMENT ON TABLE ban_record IS '连续3个小时均值超标触发的当日禁烧记录，按窑+日期拦截当日新执行方案';

CREATE TABLE IF NOT EXISTS daily_summary (
    id                        BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id                   BIGINT      NOT NULL,
    summary_date              DATE        NOT NULL,
    actual_substitution_rate  DECIMAL(6,4),
    exceeded_periods          CLOB,
    reduction_records         CLOB,
    effective_hours           CLOB,
    sealed                    BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at                TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sealed_at                 TIMESTAMP,
    CONSTRAINT uk_summary_plan_day UNIQUE (plan_id, summary_date),
    CONSTRAINT fk_summary_plan FOREIGN KEY (plan_id) REFERENCES co_firing_plan (id)
);
COMMENT ON TABLE daily_summary IS '日结输出，实际替代率/超标时段/降掺记录三项勾稽可复算，sealed=TRUE为封存终态';

CREATE TABLE IF NOT EXISTS warm_up_ticket (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id       VARCHAR(64) NOT NULL,
    ticket_no        VARCHAR(64) NOT NULL,
    kiln_id          VARCHAR(32) NOT NULL,
    curve_version    VARCHAR(32) NOT NULL,
    status           VARCHAR(16) NOT NULL,
    current_stage_no INT         NOT NULL,
    hold_started_at  TIMESTAMP,
    created_at       TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at     TIMESTAMP,
    updated_at       TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_ticket_request_id UNIQUE (request_id),
    CONSTRAINT uk_ticket_ticket_no UNIQUE (ticket_no)
);
COMMENT ON TABLE warm_up_ticket IS '冷修后升温操作票，按合成曲线分阶段执行，曲线走完才允许投料';
COMMENT ON COLUMN warm_up_ticket.status IS 'EXECUTING升温中 HOLDING保温中 COMPLETED曲线走完(终态，允许投料)';
COMMENT ON COLUMN warm_up_ticket.current_stage_no IS '当前执行阶段序号，与阶段推进同事务更新';
COMMENT ON COLUMN warm_up_ticket.hold_started_at IS '进入保温段时刻(Asia/Shanghai)，恰满4小时未结束即升级告警';

CREATE TABLE IF NOT EXISTS kiln_active_warmup (
    kiln_id     VARCHAR(32) PRIMARY KEY,
    ticket_id   BIGINT      NOT NULL,
    acquired_at TIMESTAMP   NOT NULL,
    CONSTRAINT uk_active_warmup_ticket UNIQUE (ticket_id)
);
COMMENT ON TABLE kiln_active_warmup IS '窑当前执行中升温票占用槽，同一窑同时刻只允许一张执行中/保温中升温票，靠主键唯一约束并发受理';

CREATE TABLE IF NOT EXISTS warm_up_stage (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    ticket_id            BIGINT       NOT NULL,
    stage_no             INT          NOT NULL,
    stage_type           VARCHAR(8)   NOT NULL,
    curve_stage_no       INT          NOT NULL,
    curve_version        VARCHAR(32)  NOT NULL,
    target_temp          DECIMAL(8,1),
    max_duration_minutes INT,
    started_at           TIMESTAMP    NOT NULL,
    confirmed_at         TIMESTAMP,
    confirm_operator_a   VARCHAR(64),
    confirm_operator_b   VARCHAR(64),
    CONSTRAINT uk_stage_ticket_seq UNIQUE (ticket_id, stage_no),
    CONSTRAINT fk_stage_ticket FOREIGN KEY (ticket_id) REFERENCES warm_up_ticket (id)
);
COMMENT ON TABLE warm_up_stage IS '升温票执行阶段记录，与票的阶段推进在同一事务写入，curve_version 随阶段落库保证原子可溯';
COMMENT ON COLUMN warm_up_stage.stage_type IS 'RAMP升温段(按曲线定义) HOLD保温段(恰满最长用时未达目标自动转入)';
COMMENT ON COLUMN warm_up_stage.curve_stage_no IS '对应合成曲线中的阶段序号，保温段记录其承接的升温段序号';
COMMENT ON COLUMN warm_up_stage.target_temp IS '阶段目标温度，单位摄氏度，恰等于判达标';
COMMENT ON COLUMN warm_up_stage.max_duration_minutes IS '升温段最长用时(分钟)，恰满未达目标自动转保温段';

CREATE TABLE IF NOT EXISTS warm_up_stage_confirm (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    ticket_id    BIGINT      NOT NULL,
    stage_no     INT         NOT NULL,
    operator     VARCHAR(64) NOT NULL,
    confirmed_at TIMESTAMP   NOT NULL,
    CONSTRAINT uk_stage_operator UNIQUE (ticket_id, stage_no, operator),
    CONSTRAINT fk_confirm_ticket FOREIGN KEY (ticket_id) REFERENCES warm_up_ticket (id)
);
COMMENT ON TABLE warm_up_stage_confirm IS '阶段确认双人复核记录，同阶段须两名不同操作人确认方可推进，单人确认不生效';

CREATE TABLE IF NOT EXISTS warm_up_alarm (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    ticket_id    BIGINT      NOT NULL,
    stage_no     INT         NOT NULL,
    alarm_type   VARCHAR(32) NOT NULL,
    triggered_at TIMESTAMP   NOT NULL,
    CONSTRAINT uk_alarm_ticket_stage UNIQUE (ticket_id, stage_no),
    CONSTRAINT fk_alarm_ticket FOREIGN KEY (ticket_id) REFERENCES warm_up_ticket (id)
);
COMMENT ON TABLE warm_up_alarm IS '保温段超时升级告警，保温恰满4小时未结束触发，每票每阶段至多一条';

CREATE TABLE IF NOT EXISTS feed_record (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    kiln_id    VARCHAR(32) NOT NULL,
    ticket_id  BIGINT      NOT NULL,
    fed_at     TIMESTAMP   NOT NULL,
    created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_feed_request UNIQUE (request_id),
    CONSTRAINT fk_feed_ticket FOREIGN KEY (ticket_id) REFERENCES warm_up_ticket (id)
);
COMMENT ON TABLE feed_record IS '恢复投料记录，投料请求与升温曲线状态在同一事务校验，曲线未走完禁止投料';
