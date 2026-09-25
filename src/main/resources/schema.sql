-- 林木采伐监管服务建表脚本（H2 MySQL 兼容模式；日期均为 UTC 日界）。

-- 小班：合成基础数据
CREATE TABLE IF NOT EXISTS forest_subplot (
    id              VARCHAR(64)  NOT NULL,
    subplot_code    VARCHAR(64)  NOT NULL,           -- 小班编号
    species_code    VARCHAR(32)  NOT NULL,           -- 树种编码（同时为年度限额档位键）
    forest_category VARCHAR(16)  NOT NULL,           -- 林种：COMMERCIAL 商品林 / PUBLIC_WELFARE 生态公益林
    protected_zone  TINYINT(1)   NOT NULL DEFAULT 0, -- 是否禁伐区：1 是 0 否；禁伐区一律拒绝
    stand_age       INT          NOT NULL,           -- 当前林龄，单位：年
    mature_age      INT          NOT NULL,           -- 成熟龄，单位：年；主伐林龄须不低于此值
    area            DECIMAL(12,2) NOT NULL,          -- 小班面积，单位：公顷
    PRIMARY KEY (id),
    CONSTRAINT uk_subplot_code UNIQUE (subplot_code)
);

-- 年度采伐限额（按树种分档）
CREATE TABLE IF NOT EXISTS annual_quota (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    species_code VARCHAR(32)  NOT NULL,              -- 树种编码（限额档位）
    quota_year   INT          NOT NULL,              -- 限额年度
    total_volume DECIMAL(14,2) NOT NULL,             -- 年度采伐限额，单位：立方米
    PRIMARY KEY (id),
    CONSTRAINT uk_quota_tier UNIQUE (species_code, quota_year)
);

-- 限额台账：发证占用（HOLD）为正，逾期作废释放（RELEASE）为负
CREATE TABLE IF NOT EXISTS quota_ledger (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    species_code   VARCHAR(32)  NOT NULL,            -- 树种编码（限额档位）
    quota_year     INT          NOT NULL,            -- 限额年度
    application_id VARCHAR(64)  NOT NULL,            -- 关联申请单 ID
    license_id     VARCHAR(64),                      -- 关联采伐证 ID（释放时回填）
    volume         DECIMAL(14,2) NOT NULL,           -- 占用为正、释放为负，单位：立方米
    action         VARCHAR(16)  NOT NULL,            -- HOLD 占用 / RELEASE 释放
    created_date   DATE         NOT NULL,            -- 记账日期（UTC）
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_quota_ledger_tier ON quota_ledger (species_code, quota_year);

-- 采伐申请单
CREATE TABLE IF NOT EXISTS harvest_application (
    id            VARCHAR(64)  NOT NULL,
    request_id    VARCHAR(64)  NOT NULL,             -- 申请幂等键
    operator_id   VARCHAR(64)  NOT NULL,             -- 经营主体 ID
    subplot_id    VARCHAR(64)  NOT NULL,             -- 小班 ID
    species_code  VARCHAR(32)  NOT NULL,             -- 树种编码（限额档位）
    quota_year    INT          NOT NULL,             -- 申请归属限额年度
    harvest_type  VARCHAR(16)  NOT NULL,             -- MAIN 主伐 / TENDING 抚育 / REFORMATION 改造
    volume        DECIMAL(14,2) NOT NULL,            -- 申请采伐蓄积量，单位：立方米
    status        VARCHAR(24)  NOT NULL,             -- 申请状态机状态
    license_id    VARCHAR(64),                       -- 已发采伐证 ID，未发证为空
    reject_reason VARCHAR(255),                      -- 拒绝原因，未拒绝为空
    archived      TINYINT(1)   NOT NULL DEFAULT 0,   -- 是否归档（终态）：1 是 0 否
    created_date  DATE         NOT NULL,             -- 申请日期（UTC）
    reviewed_date DATE,                              -- 审核日期（UTC），未审核为空
    archived_date DATE,                              -- 归档日期（UTC），未归档为空
    PRIMARY KEY (id),
    CONSTRAINT uk_application_request UNIQUE (request_id)
);
CREATE INDEX IF NOT EXISTS idx_app_operator ON harvest_application (operator_id);
CREATE INDEX IF NOT EXISTS idx_app_subplot ON harvest_application (subplot_id);

-- 采伐许可证
CREATE TABLE IF NOT EXISTS harvest_license (
    id             VARCHAR(64)  NOT NULL,
    application_id VARCHAR(64)  NOT NULL,            -- 关联申请单 ID
    operator_id    VARCHAR(64)  NOT NULL,            -- 经营主体 ID
    subplot_id     VARCHAR(64)  NOT NULL,            -- 小班 ID
    species_code   VARCHAR(32)  NOT NULL,            -- 树种编码（限额档位）
    quota_year     INT          NOT NULL,            -- 限额年度
    volume         DECIMAL(14,2) NOT NULL,            -- 证载采伐蓄积量，单位：立方米
    status         VARCHAR(16)  NOT NULL,            -- ISSUED 有效 / HARVESTED 已采伐 / VOID 逾期作废
    issue_date     DATE         NOT NULL,            -- 发证日期（UTC）
    expire_date    DATE         NOT NULL,            -- 采伐期限截止日（发证日 + 90 天，含当日）
    harvest_date   DATE,                             -- 采伐确认日期（UTC），未确认为空
    void_date      DATE,                             -- 作废日期（UTC），未作废为空
    PRIMARY KEY (id),
    CONSTRAINT uk_license_application UNIQUE (application_id)
);
CREATE INDEX IF NOT EXISTS idx_license_subplot_status ON harvest_license (subplot_id, status);

-- 迹地更新义务
CREATE TABLE IF NOT EXISTS renewal_obligation (
    id                   VARCHAR(64)  NOT NULL,
    application_id       VARCHAR(64)  NOT NULL,      -- 关联申请单 ID
    operator_id          VARCHAR(64)  NOT NULL,      -- 经营主体 ID
    status               VARCHAR(16)  NOT NULL,      -- PENDING / REPLANTING / PASSED / FAILED
    deadline             DATE         NOT NULL,      -- 首次更新期限：翌年 12 月 31 日（UTC）
    replant_deadline     DATE,                       -- 补植期限：首次不合格日 + 6 个月所在月月末（含当日）
    qualified_rate       DECIMAL(6,4),               -- 最近一次验收合格率（0~1），未验收为空
    accepted_date        DATE,                       -- 首次验收日期（UTC）
    replant_accepted_date DATE,                      -- 补植验收日期（UTC）
    created_date         DATE         NOT NULL,      -- 义务生成日期（采伐确认日，UTC）
    PRIMARY KEY (id),
    CONSTRAINT uk_renewal_application UNIQUE (application_id)
);

-- 经营主体限批名单
CREATE TABLE IF NOT EXISTS restriction_list (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    operator_id VARCHAR(64) NOT NULL,                -- 被限批经营主体 ID
    reason     VARCHAR(255),                         -- 列入原因
    start_date DATE         NOT NULL,                -- 限批起始日（UTC）
    end_date   DATE         NOT NULL,                -- 限批截止日（UTC，含当日）
    active     TINYINT(1)   NOT NULL DEFAULT 1,      -- 是否生效：1 是 0 否
    PRIMARY KEY (id),
    CONSTRAINT uk_restriction_start UNIQUE (operator_id, start_date)
);

-- 幂等请求记录：指纹覆盖 requestId 与全部业务字段，保存首次响应
CREATE TABLE IF NOT EXISTS idempotent_request (
    operation     VARCHAR(24) NOT NULL,              -- APPLY / ISSUE / CONFIRM / ACCEPTANCE
    request_id    VARCHAR(64) NOT NULL,              -- 幂等键
    fingerprint   VARCHAR(128) NOT NULL,             -- 业务字段指纹（SHA-256 摘要）
    status        VARCHAR(16) NOT NULL,              -- PENDING 处理中 / DONE 已完成（并发同键等待 PENDING）
    response_type VARCHAR(24),                       -- 首次结果类型：APPLICATION/LICENSE/RENEWAL/ERROR
    response_status INT,                             -- ERROR 结果的 HTTP 状态码；成功结果为空
    ref_id        VARCHAR(64),                       -- 首次操作关联业务主键
    response_json CLOB,                              -- 首次响应快照（同参重放原样返回）
    created_date  DATE        NOT NULL,              -- 首次处理日期（UTC）
    PRIMARY KEY (operation, request_id)
);

-- 归档后迟到数据事件（仅记录，不再驱动业务）
CREATE TABLE IF NOT EXISTS late_event (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    application_id VARCHAR(64),                      -- 关联申请单 ID（可解析时）
    license_id     VARCHAR(64),                      -- 关联采伐证 ID（可解析时）
    event_type     VARCHAR(32)  NOT NULL,            -- 事件类型
    payload        CLOB,                             -- 迟到提交原文
    created_date   DATE         NOT NULL,            -- 记录日期（UTC）
    PRIMARY KEY (id)
);

-- 表与字段中文注释（H2 COMMENT 语法；日期字段统一为 UTC 日界）
COMMENT ON TABLE forest_subplot IS '森林小班基础数据（合成数据）';
COMMENT ON COLUMN forest_subplot.forest_category IS '林种：COMMERCIAL 商品林；PUBLIC_WELFARE 生态公益林（禁商业采伐）';
COMMENT ON COLUMN forest_subplot.protected_zone IS '是否禁伐区：1 是（申请一律拒绝）；0 否';
COMMENT ON COLUMN forest_subplot.stand_age IS '当前林龄，单位：年';
COMMENT ON COLUMN forest_subplot.mature_age IS '成熟龄，单位：年；主伐林龄须不低于此值，恰达成熟龄合法';
COMMENT ON TABLE annual_quota IS '年度采伐限额，按树种+年度分档，单位：立方米';
COMMENT ON TABLE quota_ledger IS '限额占用/释放台账：HOLD 发证占用为正，RELEASE 逾期作废释放为负';
COMMENT ON TABLE harvest_application IS '采伐申请单，状态：申请-审核-发证-采伐确认-更新验收';
COMMENT ON COLUMN harvest_application.status IS 'SUBMITTED 已申请；REVIEW_PASSED 审核通过；REVIEW_REJECTED 审核拒绝；LICENSED 已发证；HARVESTED 已采伐；RENEWAL_DONE 更新通过；RENEWAL_FAILED 更新失败限批；LICENSE_VOID 证件逾期作废；ARCHIVED 已归档（终态）';
COMMENT ON COLUMN harvest_application.archived IS '是否归档终态：1 是（迟到数据仅记录事件）；0 否';
COMMENT ON TABLE harvest_license IS '采伐许可证；ISSUED 有效；HARVESTED 已完成采伐确认（唯一成功终态）；VOID 逾期作废';
COMMENT ON COLUMN harvest_license.expire_date IS '采伐期限截止日=发证日+90 个自然日（UTC），恰在第 90 日确认有效';
COMMENT ON TABLE renewal_obligation IS '迹地更新义务；PENDING 待验收；REPLANTING 补植中；PASSED 通过；FAILED 失败（触发限批）';
COMMENT ON COLUMN renewal_obligation.deadline IS '首次更新期限：采伐确认翌年 12 月 31 日（UTC）';
COMMENT ON COLUMN renewal_obligation.replant_deadline IS '补植期限：首次验收不合格日+6 个自然月所在月月末（含当日）';
COMMENT ON COLUMN renewal_obligation.qualified_rate IS '最近一次验收合格率（0~1）；通过线 0.85，恰达 0.85 合格';
COMMENT ON TABLE restriction_list IS '经营主体限批名单；期限内新采伐申请一律拒绝';
COMMENT ON TABLE idempotent_request IS '幂等请求记录；指纹覆盖 requestId 与全部业务字段';
COMMENT ON TABLE late_event IS '归档后迟到数据事件，仅记录不驱动业务';
