-- 取水监管服务建表脚本（目标：H2 MySQL 兼容模式；未在真实 MySQL 上验证）
-- 水量单位统一为立方米（m³）；时刻字段统一为 UTC。

-- 取水户
CREATE TABLE IF NOT EXISTS water_user (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(128) NOT NULL,
    river_section VARCHAR(128) NOT NULL,
    annual_quota  DECIMAL(18,3) NOT NULL,
    created_at    TIMESTAMP(3) NOT NULL
);
COMMENT ON TABLE water_user IS '取水户表（合成数据）';
COMMENT ON COLUMN water_user.id IS '取水户ID';
COMMENT ON COLUMN water_user.name IS '取水户名称（合成数据）';
COMMENT ON COLUMN water_user.river_section IS '所属河段标识';
COMMENT ON COLUMN water_user.annual_quota IS '年度许可总量，单位m³';
COMMENT ON COLUMN water_user.created_at IS '创建时刻（UTC）';

-- 计量点
CREATE TABLE IF NOT EXISTS meter_point (
    id      BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    name    VARCHAR(128) NOT NULL,
    CONSTRAINT fk_meter_user FOREIGN KEY (user_id) REFERENCES water_user (id)
);
COMMENT ON TABLE meter_point IS '计量点表';
COMMENT ON COLUMN meter_point.id IS '计量点ID';
COMMENT ON COLUMN meter_point.user_id IS '所属取水户ID';
COMMENT ON COLUMN meter_point.name IS '计量点名称（合成数据）';

-- 河流生态流量预警（限取）事件
CREATE TABLE IF NOT EXISTS restriction_event (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    river_section VARCHAR(128) NOT NULL,
    effective_at  TIMESTAMP(3) NOT NULL,
    reason        VARCHAR(255),
    active        BOOLEAN NOT NULL,
    created_at    TIMESTAMP(3) NOT NULL
);
COMMENT ON TABLE restriction_event IS '生态流量限取事件表';
COMMENT ON COLUMN restriction_event.id IS '限取事件ID';
COMMENT ON COLUMN restriction_event.river_section IS '受影响河段标识';
COMMENT ON COLUMN restriction_event.effective_at IS '限取生效时刻（UTC），与申报并发时按此时刻排序';
COMMENT ON COLUMN restriction_event.reason IS '预警原因，空值表示无说明';
COMMENT ON COLUMN restriction_event.active IS '是否为河段当前激活事件：TRUE是 FALSE否';
COMMENT ON COLUMN restriction_event.created_at IS '入库时刻（UTC）';

-- 年度额度
CREATE TABLE IF NOT EXISTS annual_quota (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id              BIGINT NOT NULL,
    quota_year           INT NOT NULL,
    permitted_quota      DECIMAL(18,3) NOT NULL,
    carryover_deduction  DECIMAL(18,3) NOT NULL DEFAULT 0,
    sealed               BOOLEAN NOT NULL DEFAULT FALSE,
    sealed_at            TIMESTAMP(3) NULL,
    actual_total         DECIMAL(18,3) NULL,
    excess               DECIMAL(18,3) NULL,
    CONSTRAINT uq_annual_user_year UNIQUE (user_id, quota_year),
    CONSTRAINT fk_annual_user FOREIGN KEY (user_id) REFERENCES water_user (id)
);
COMMENT ON TABLE annual_quota IS '年度许可额度与封存表';
COMMENT ON COLUMN annual_quota.id IS '年度额度ID';
COMMENT ON COLUMN annual_quota.user_id IS '取水户ID';
COMMENT ON COLUMN annual_quota.quota_year IS '年度（UTC自然年）';
COMMENT ON COLUMN annual_quota.permitted_quota IS '年度许可总量快照，单位m³';
COMMENT ON COLUMN annual_quota.carryover_deduction IS '上年超许可按1:1结转的扣减额度，单位m³，无结转为0';
COMMENT ON COLUMN annual_quota.sealed IS '年度计划封存终态：TRUE已封存 FALSE未封存';
COMMENT ON COLUMN annual_quota.sealed_at IS '封存时刻（UTC），未封存为空';
COMMENT ON COLUMN annual_quota.actual_total IS '封存时冻结的当年实际取水总量，单位m³，未封存为空';
COMMENT ON COLUMN annual_quota.excess IS '封存结算的超许可部分，单位m³，未封存为空';

-- 月度计划确认版本
CREATE TABLE IF NOT EXISTS plan_version (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id          BIGINT NOT NULL,
    plan_month       CHAR(7) NOT NULL,
    version_no       INT NOT NULL,
    declared_volume  DECIMAL(18,3) NOT NULL,
    accepted_volume  DECIMAL(18,3) NOT NULL,
    restricted       BOOLEAN NOT NULL,
    restriction_id   BIGINT NULL,
    request_id       VARCHAR(64) NOT NULL,
    confirmed_at     TIMESTAMP(3) NOT NULL,
    CONSTRAINT uq_plan_user_month_version UNIQUE (user_id, plan_month, version_no),
    CONSTRAINT fk_plan_user FOREIGN KEY (user_id) REFERENCES water_user (id)
);
COMMENT ON TABLE plan_version IS '月度取水计划确认版本表';
COMMENT ON COLUMN plan_version.id IS '计划版本ID';
COMMENT ON COLUMN plan_version.user_id IS '取水户ID';
COMMENT ON COLUMN plan_version.plan_month IS '自然月（UTC），yyyy-MM';
COMMENT ON COLUMN plan_version.version_no IS '该用户该月确认版本号，从1递增';
COMMENT ON COLUMN plan_version.declared_volume IS '原始申报水量，单位m³';
COMMENT ON COLUMN plan_version.accepted_volume IS '受理水量，单位m³，限取减半时为申报量一半';
COMMENT ON COLUMN plan_version.restricted IS '受理判定时是否处于限取：TRUE是 FALSE否';
COMMENT ON COLUMN plan_version.restriction_id IS '触发减半的限取事件ID，未限取为空';
COMMENT ON COLUMN plan_version.request_id IS '产生本版本的申报幂等键';
COMMENT ON COLUMN plan_version.confirmed_at IS '确认时刻（UTC）';

-- 申报幂等记录
CREATE TABLE IF NOT EXISTS declaration_idempotency (
    request_id       VARCHAR(64) PRIMARY KEY,
    user_id          BIGINT NOT NULL,
    idem_month       CHAR(7) NOT NULL,
    declared_volume  DECIMAL(18,3) NOT NULL,
    plan_version_id  BIGINT NULL,
    rejected         BOOLEAN NOT NULL,
    reject_reason    VARCHAR(255) NULL,
    first_seen_at    TIMESTAMP(3) NOT NULL,
    CONSTRAINT fk_idem_version FOREIGN KEY (plan_version_id) REFERENCES plan_version (id)
);
COMMENT ON TABLE declaration_idempotency IS '申报幂等指纹表';
COMMENT ON COLUMN declaration_idempotency.request_id IS '申报幂等键';
COMMENT ON COLUMN declaration_idempotency.user_id IS '指纹：取水户ID';
COMMENT ON COLUMN declaration_idempotency.idem_month IS '指纹：自然月（UTC）yyyy-MM';
COMMENT ON COLUMN declaration_idempotency.declared_volume IS '指纹：申报水量，单位m³';
COMMENT ON COLUMN declaration_idempotency.plan_version_id IS '首次受理产生的版本ID；首次被拒绝时为空';
COMMENT ON COLUMN declaration_idempotency.rejected IS '首次请求是否被规则拒绝：TRUE是 FALSE否';
COMMENT ON COLUMN declaration_idempotency.reject_reason IS '拒绝原因，受理成功为空';
COMMENT ON COLUMN declaration_idempotency.first_seen_at IS '首次到达时刻（UTC）';

-- 计量读数
CREATE TABLE IF NOT EXISTS meter_reading (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    meter_point_id  BIGINT NOT NULL,
    reading_time    TIMESTAMP(3) NOT NULL,
    reading_month   CHAR(7) NOT NULL,
    cumulative      DECIMAL(18,3) NOT NULL,
    delta           DECIMAL(18,3) NOT NULL,
    received_at     TIMESTAMP(3) NOT NULL,
    CONSTRAINT uq_reading_meter_time UNIQUE (meter_point_id, reading_time),
    CONSTRAINT fk_reading_meter FOREIGN KEY (meter_point_id) REFERENCES meter_point (id)
);
COMMENT ON TABLE meter_reading IS '计量流量读数表（计量点+表底时刻去重）';
COMMENT ON COLUMN meter_reading.id IS '读数ID';
COMMENT ON COLUMN meter_reading.meter_point_id IS '计量点ID';
COMMENT ON COLUMN meter_reading.reading_time IS '表底时刻（UTC），归月依据';
COMMENT ON COLUMN meter_reading.reading_month IS '按表底时刻归入的UTC自然月 yyyy-MM';
COMMENT ON COLUMN meter_reading.cumulative IS '表底累计读数，单位m³';
COMMENT ON COLUMN meter_reading.delta IS '与表底时刻相邻前一读数之差，单位m³';
COMMENT ON COLUMN meter_reading.received_at IS '入库时刻（UTC）';
