-- 动物调运检疫出证与疫点封锁联动服务建表脚本（H2，MODE=MySQL）
-- 所有时间列均为 UTC 时间（TIMESTAMP，不含时区），由应用层 Clock 统一写入。

-- 养殖场登记表：每个养殖场一行，用于封锁发布与出证之间的行级锁（SELECT ... FOR UPDATE）串行化。
CREATE TABLE IF NOT EXISTS farm_registry (
    farm_id     VARCHAR(64)  NOT NULL COMMENT '养殖场编号',
    created_at  TIMESTAMP    NOT NULL COMMENT '登记时间（UTC）',
    PRIMARY KEY (farm_id)
);

-- 疫点封锁区表：封锁即刻生效，同一养殖场仅允许一条封锁记录。
CREATE TABLE IF NOT EXISTS lockdown_zone (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    request_id  VARCHAR(64)  NOT NULL COMMENT '封锁发布请求号（幂等键）',
    fingerprint VARCHAR(64)  NOT NULL COMMENT '幂等指纹：requestId 与全部业务字段的 SHA-256',
    farm_id     VARCHAR(64)  NOT NULL COMMENT '被封锁养殖场编号',
    reason      VARCHAR(255) NOT NULL COMMENT '封锁原因',
    status      VARCHAR(16)  NOT NULL COMMENT '封锁状态：ACTIVE=生效中',
    created_at  TIMESTAMP    NOT NULL COMMENT '封锁生效时间（UTC）',
    PRIMARY KEY (id),
    CONSTRAINT uk_lockdown_request UNIQUE (request_id),
    CONSTRAINT uk_lockdown_farm UNIQUE (farm_id)
);

-- 检疫申报表：申报→产地检疫→出证流程的入口单据。
CREATE TABLE IF NOT EXISTS quarantine_declaration (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    request_id    VARCHAR(64)  NOT NULL COMMENT '申报请求号（幂等键）',
    fingerprint   VARCHAR(64)  NOT NULL COMMENT '幂等指纹：requestId 与全部业务字段的 SHA-256',
    farm_id       VARCHAR(64)  NOT NULL COMMENT '启运养殖场编号',
    destination   VARCHAR(128) NOT NULL COMMENT '目的地',
    species       VARCHAR(16)  NOT NULL COMMENT '动物种类：PIG/CATTLE/SHEEP/POULTRY',
    quantity      INT          NOT NULL COMMENT '申报数量（只/头）',
    ear_tag_range VARCHAR(64)  NOT NULL COMMENT '耳标段，标识同批动物',
    status        VARCHAR(16)  NOT NULL COMMENT '状态：DECLARED=已申报，REJECTED=已驳回，CERTIFIED=已出证',
    reject_reason VARCHAR(255) NULL COMMENT '驳回原因；未驳回为 NULL',
    transport_type VARCHAR(16) NOT NULL DEFAULT 'LOCAL' COMMENT '运输类型：LOCAL=本地调运，TRANSIT=过境（途经本地口岸不停留）',
    transit_hours INT          NULL COMMENT '过境时限（小时），自启运时刻起算；仅 TRANSIT 有值，LOCAL 为 NULL',
    route_regions VARCHAR(512) NULL COMMENT '途经区域编码清单（逗号分隔）；仅 TRANSIT 有值，LOCAL 为 NULL',
    created_at    TIMESTAMP    NOT NULL COMMENT '创建时间（UTC）',
    updated_at    TIMESTAMP    NOT NULL COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    CONSTRAINT uk_declaration_request UNIQUE (request_id)
);

-- 检疫合格证表：出证后生成，有效期自出证时刻起算（猪牛羊 24 小时、禽 48 小时，UTC）。
CREATE TABLE IF NOT EXISTS quarantine_certificate (
    id                     BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    cert_no                VARCHAR(32)  NOT NULL COMMENT '证号，出证事务内生成',
    issue_request_id       VARCHAR(64)  NOT NULL COMMENT '出证请求号（幂等键）',
    fingerprint            VARCHAR(64)  NOT NULL COMMENT '幂等指纹：requestId 与全部业务字段的 SHA-256',
    declaration_request_id VARCHAR(64)  NOT NULL COMMENT '来源申报请求号',
    farm_id                VARCHAR(64)  NOT NULL COMMENT '启运养殖场编号',
    destination            VARCHAR(128) NOT NULL COMMENT '目的地',
    species                VARCHAR(16)  NOT NULL COMMENT '动物种类',
    quantity               INT          NOT NULL COMMENT '证载数量（只/头）',
    ear_tag_range          VARCHAR(64)  NOT NULL COMMENT '耳标段，标识同批动物',
    status                 VARCHAR(16)  NOT NULL COMMENT '状态：ISSUED=已出证未启运，IN_TRANSIT=调运在途，VOIDED=已作废，EXPIRED=证失效，CLOSED=已结案',
    quarantine_observation BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '强制隔离观察标记：在途遇疫点封锁时置真',
    reported_qty           INT          NOT NULL DEFAULT 0 COMMENT '累计已落地报告数量（只/头）',
    transport_type         VARCHAR(16)  NOT NULL DEFAULT 'LOCAL' COMMENT '运输类型：LOCAL=本地调运，TRANSIT=过境；过境证不得用于本地销售或饲养流向登记',
    transit_hours          INT          NULL COMMENT '过境时限（小时），自启运时刻起算；仅 TRANSIT 有值',
    transit_deadline_at    TIMESTAMP    NULL COMMENT '过境核销时限终点（UTC）= 启运时间 + 过境时限，恰满时限视为按时；未启运或非 TRANSIT 为 NULL',
    route_regions          VARCHAR(512) NULL COMMENT '途经区域编码清单（逗号分隔），自申报单复制；仅 TRANSIT 有值',
    enhanced_monitoring    BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '加强监测标记：过境车辆途经疫区清单区域时置真，与抵达记录同事务写入',
    issued_at              TIMESTAMP    NOT NULL COMMENT '出证时间（UTC）',
    expires_at             TIMESTAMP    NOT NULL COMMENT '有效期终点（UTC），落地报告不晚于该时刻有效',
    departed_at            TIMESTAMP    NULL COMMENT '启运时间（UTC）；未启运为 NULL',
    closed_at              TIMESTAMP    NULL COMMENT '结案时间（UTC）；未结案为 NULL',
    created_at             TIMESTAMP    NOT NULL COMMENT '创建时间（UTC）',
    updated_at             TIMESTAMP    NOT NULL COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    CONSTRAINT uk_certificate_no UNIQUE (cert_no),
    CONSTRAINT uk_certificate_issue_request UNIQUE (issue_request_id)
);

-- 同批动物有效证占用表：同一养殖场同一耳标段仅允许一张有效证，证失效/作废/结案后释放。
CREATE TABLE IF NOT EXISTS cert_batch_hold (
    farm_id       VARCHAR(64) NOT NULL COMMENT '养殖场编号',
    ear_tag_range VARCHAR(64) NOT NULL COMMENT '耳标段',
    cert_no       VARCHAR(32) NOT NULL COMMENT '持有该批次有效证的证号',
    created_at    TIMESTAMP   NOT NULL COMMENT '占用时间（UTC）',
    PRIMARY KEY (farm_id, ear_tag_range),
    CONSTRAINT uk_hold_cert UNIQUE (cert_no)
);

-- 养殖场可售存栏表：出证时按证载数量原子核减。
CREATE TABLE IF NOT EXISTS farm_stock (
    id            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    farm_id       VARCHAR(64) NOT NULL COMMENT '养殖场编号',
    species       VARCHAR(16) NOT NULL COMMENT '动物种类',
    ear_tag_range VARCHAR(64) NOT NULL COMMENT '耳标段',
    sellable_qty  INT         NOT NULL COMMENT '可售存栏数量（只/头）',
    created_at    TIMESTAMP   NOT NULL COMMENT '创建时间（UTC）',
    updated_at    TIMESTAMP   NOT NULL COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    CONSTRAINT uk_stock_batch UNIQUE (farm_id, species, ear_tag_range)
);

-- 落地报告表：每次报告（含被拒与迟到事件）均留痕；request_id 为幂等键。
CREATE TABLE IF NOT EXISTS arrival_report (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    request_id  VARCHAR(64)  NOT NULL COMMENT '落地报告请求号（幂等键）',
    fingerprint VARCHAR(64)  NOT NULL COMMENT '幂等指纹：requestId 与全部业务字段的 SHA-256',
    cert_no     VARCHAR(32)  NOT NULL COMMENT '目标证号',
    quantity    INT          NOT NULL COMMENT '本次报告到达数量（只/头）',
    result      VARCHAR(32)  NOT NULL COMMENT '结果：ACCEPTED_PARTIAL/ACCEPTED_FINAL/REJECTED_EXPIRED/REJECTED_OVERFLOW/REJECTED_NOT_IN_TRANSIT/REJECTED_CERT_INVALID/LATE_IGNORED',
    note        VARCHAR(255) NULL COMMENT '结果说明；无说明为 NULL',
    created_at  TIMESTAMP    NOT NULL COMMENT '报告时间（UTC）',
    PRIMARY KEY (id),
    CONSTRAINT uk_arrival_request UNIQUE (request_id)
);

-- 合成疫区清单：应用初始化时写入的合成数据，用于过境车辆途经区域比对，非真实疫情数据。
CREATE TABLE IF NOT EXISTS epidemic_area (
    region_code VARCHAR(64)  NOT NULL COMMENT '区域编码（合成数据）',
    region_name VARCHAR(128) NOT NULL COMMENT '区域名称（合成数据）',
    created_at  TIMESTAMP    NOT NULL COMMENT '清单录入时间（UTC）',
    PRIMARY KEY (region_code)
);

-- 合成疫区清单初始化（幂等）：仅合成数据。
MERGE INTO epidemic_area (region_code, region_name, created_at) KEY(region_code) VALUES
    ('SYN-EP-001', '合成疫区-阿尔法', TIMESTAMP '2026-01-01 00:00:00'),
    ('SYN-EP-002', '合成疫区-贝塔', TIMESTAMP '2026-01-01 00:00:00');

-- 流向登记表：按证书类型拦截不符用途的流向（过境证仅允许 TRANSIT 用途，本地证反之）；request_id 为幂等键。
CREATE TABLE IF NOT EXISTS flow_registration (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    request_id  VARCHAR(64) NOT NULL COMMENT '流向登记请求号（幂等键）',
    fingerprint VARCHAR(64) NOT NULL COMMENT '幂等指纹：requestId 与全部业务字段的 SHA-256',
    cert_no     VARCHAR(32) NOT NULL COMMENT '目标证号',
    purpose     VARCHAR(16) NOT NULL COMMENT '流向用途：LOCAL_SALE=本地销售，BREEDING=饲养，TRANSIT=过境通行',
    created_at  TIMESTAMP   NOT NULL COMMENT '登记时间（UTC）',
    PRIMARY KEY (id),
    CONSTRAINT uk_flow_request UNIQUE (request_id)
);

-- 过境超时预警事件表：每张过境证至多一条预警；处理闭环（OPEN→RESOLVED）留痕，纳入审计口径。
CREATE TABLE IF NOT EXISTS transit_alert (
    id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    alert_key           VARCHAR(64)  NOT NULL COMMENT '预警键：TA-{证号}，每张过境证唯一',
    cert_no             VARCHAR(32)  NOT NULL COMMENT '过境证号',
    status              VARCHAR(16)  NOT NULL COMMENT '状态：OPEN=待处理，RESOLVED=已办结',
    triggered_at        TIMESTAMP    NOT NULL COMMENT '预警生成时间（UTC）',
    resolve_request_id  VARCHAR(64)  NULL COMMENT '办结请求号（幂等键）；未办结为 NULL',
    resolve_fingerprint VARCHAR(64)  NULL COMMENT '办结幂等指纹：requestId 与全部业务字段的 SHA-256；未办结为 NULL',
    resolve_note        VARCHAR(255) NULL COMMENT '办结说明；未办结为 NULL',
    resolved_at         TIMESTAMP    NULL COMMENT '办结时间（UTC）；未办结为 NULL',
    created_at          TIMESTAMP    NOT NULL COMMENT '创建时间（UTC）',
    updated_at          TIMESTAMP    NOT NULL COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    CONSTRAINT uk_transit_alert_key UNIQUE (alert_key),
    CONSTRAINT uk_transit_alert_resolve_request UNIQUE (resolve_request_id)
);
