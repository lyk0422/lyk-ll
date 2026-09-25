-- 林木采伐许可与迹地更新联动服务建表脚本（H2 MySQL 兼容模式）
-- 所有日期均为 UTC 自然日；金额/蓄积单位见各列注释。

CREATE TABLE IF NOT EXISTS forest_plot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(32) NOT NULL,
    forest_type VARCHAR(32) NOT NULL,
    in_no_harvest_zone BOOLEAN NOT NULL,
    stand_age INT NOT NULL,
    mature_age INT NOT NULL,
    species_tier VARCHAR(32) NOT NULL,
    area_ha DECIMAL(10,2) NOT NULL,
    CONSTRAINT uk_forest_plot_code UNIQUE (code)
);
COMMENT ON TABLE forest_plot IS '小班档案（合成数据）';
COMMENT ON COLUMN forest_plot.code IS '小班编号，业务唯一';
COMMENT ON COLUMN forest_plot.forest_type IS '林种：COMMERCIAL=商品林，ECOLOGICAL=生态公益林（一律拒绝采伐）';
COMMENT ON COLUMN forest_plot.in_no_harvest_zone IS '是否位于禁伐区：TRUE=禁伐区（一律拒绝）';
COMMENT ON COLUMN forest_plot.stand_age IS '林分林龄（年），主伐须不低于成熟龄';
COMMENT ON COLUMN forest_plot.mature_age IS '成熟龄（年），恰达成熟龄合法';
COMMENT ON COLUMN forest_plot.species_tier IS '树种分档，年度限额按该档位控制';
COMMENT ON COLUMN forest_plot.area_ha IS '小班面积（公顷）';

CREATE TABLE IF NOT EXISTS annual_quota (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    quota_year INT NOT NULL,
    species_tier VARCHAR(32) NOT NULL,
    quota_volume DECIMAL(14,2) NOT NULL,
    used_volume DECIMAL(14,2) NOT NULL DEFAULT 0,
    CONSTRAINT uk_annual_quota_year_tier UNIQUE (quota_year, species_tier)
);
COMMENT ON TABLE annual_quota IS '年度采伐限额（按树种分档）';
COMMENT ON COLUMN annual_quota.quota_year IS '限额年度（UTC 日历年）';
COMMENT ON COLUMN annual_quota.species_tier IS '树种分档';
COMMENT ON COLUMN annual_quota.quota_volume IS '年度限额蓄积（立方米）';
COMMENT ON COLUMN annual_quota.used_volume IS '已占用蓄积（立方米），发证占用、证件作废当日释放';

CREATE TABLE IF NOT EXISTS harvest_application (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    plot_id BIGINT NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    apply_year INT NOT NULL,
    volume DECIMAL(14,2) NOT NULL,
    status VARCHAR(16) NOT NULL,
    reject_reason VARCHAR(128),
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_harvest_application_request UNIQUE (request_id)
);
COMMENT ON TABLE harvest_application IS '采伐申请（提交即同步审核）';
COMMENT ON COLUMN harvest_application.request_id IS '幂等键：同一请求编号';
COMMENT ON COLUMN harvest_application.fingerprint IS '幂等指纹：requestId 与全部业务字段的 SHA-256；同键异参返回 409';
COMMENT ON COLUMN harvest_application.plot_id IS '小班 ID，关联 forest_plot.id';
COMMENT ON COLUMN harvest_application.operator_id IS '经营主体标识';
COMMENT ON COLUMN harvest_application.apply_year IS '申请采伐年度（UTC 日历年）';
COMMENT ON COLUMN harvest_application.volume IS '申请采伐蓄积（立方米）';
COMMENT ON COLUMN harvest_application.status IS '状态：APPROVED=审核通过，REJECTED=审核拒绝';
COMMENT ON COLUMN harvest_application.reject_reason IS '拒绝原因码；通过时为空';
COMMENT ON COLUMN harvest_application.created_at IS '创建时间（UTC）';

CREATE TABLE IF NOT EXISTS harvest_license (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    application_id BIGINT NOT NULL,
    plot_id BIGINT NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    quota_year INT NOT NULL,
    species_tier VARCHAR(32) NOT NULL,
    volume DECIMAL(14,2) NOT NULL,
    issue_date DATE NOT NULL,
    expire_date DATE NOT NULL,
    status VARCHAR(16) NOT NULL,
    confirm_date DATE,
    void_date DATE,
    archived_at TIMESTAMP,
    version INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_harvest_license_request UNIQUE (request_id)
);
COMMENT ON TABLE harvest_license IS '采伐许可证';
COMMENT ON COLUMN harvest_license.request_id IS '幂等键：发证请求编号';
COMMENT ON COLUMN harvest_license.fingerprint IS '幂等指纹：requestId 与全部业务字段的 SHA-256；同键异参返回 409 且不占用限额';
COMMENT ON COLUMN harvest_license.application_id IS '来源申请 ID，关联 harvest_application.id';
COMMENT ON COLUMN harvest_license.plot_id IS '小班 ID；同一小班同时刻只允许一张有效证';
COMMENT ON COLUMN harvest_license.operator_id IS '经营主体标识';
COMMENT ON COLUMN harvest_license.quota_year IS '占用限额年度';
COMMENT ON COLUMN harvest_license.species_tier IS '占用限额树种分档';
COMMENT ON COLUMN harvest_license.volume IS '占用蓄积（立方米）';
COMMENT ON COLUMN harvest_license.issue_date IS '发证日期（UTC）';
COMMENT ON COLUMN harvest_license.expire_date IS '最后有效采伐日（UTC，含当日）= 发证日 + 89 天，共 90 个自然日';
COMMENT ON COLUMN harvest_license.status IS '状态：VALID=有效，CONFIRMED=已采伐确认，VOID=已作废，ARCHIVED=已归档（终态）';
COMMENT ON COLUMN harvest_license.confirm_date IS '采伐确认日期（UTC）；未确认为空';
COMMENT ON COLUMN harvest_license.void_date IS '作废日期（UTC），限额当日释放；未作废为空';
COMMENT ON COLUMN harvest_license.archived_at IS '归档时间（UTC）；归档为终态，之后迟到数据只记迟到事件';
COMMENT ON COLUMN harvest_license.version IS '乐观锁版本号';

CREATE TABLE IF NOT EXISTS regeneration_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    license_id BIGINT NOT NULL,
    plot_id BIGINT NOT NULL,
    operator_id VARCHAR(64) NOT NULL,
    deadline DATE NOT NULL,
    status VARCHAR(16) NOT NULL,
    round INT NOT NULL DEFAULT 0,
    replant_deadline DATE,
    final_rate DECIMAL(5,2),
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_regeneration_task_license UNIQUE (license_id)
);
COMMENT ON TABLE regeneration_task IS '迹地更新义务（采伐确认后生成）';
COMMENT ON COLUMN regeneration_task.license_id IS '来源许可证 ID，一证一任务';
COMMENT ON COLUMN regeneration_task.plot_id IS '小班 ID';
COMMENT ON COLUMN regeneration_task.operator_id IS '经营主体标识';
COMMENT ON COLUMN regeneration_task.deadline IS '更新期限（UTC）：采伐确认翌年 12 月 31 日';
COMMENT ON COLUMN regeneration_task.status IS '状态：PENDING=待验收，REPLANT=补植期，PASSED=验收合格，FAILED=最终不合格';
COMMENT ON COLUMN regeneration_task.round IS '已完成的验收轮次（首轮+补植轮最多 2 轮）';
COMMENT ON COLUMN regeneration_task.replant_deadline IS '补植截止日（UTC）：首轮不合格日满 6 个月的当月月末；非补植期为空';
COMMENT ON COLUMN regeneration_task.final_rate IS '最终验收合格率（百分比 0-100）；未定为空';
COMMENT ON COLUMN regeneration_task.created_at IS '创建时间（UTC）';

CREATE TABLE IF NOT EXISTS acceptance_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    round INT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    pass_rate DECIMAL(5,2) NOT NULL,
    result VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_acceptance_record_request UNIQUE (request_id)
);
COMMENT ON TABLE acceptance_record IS '更新验收记录（每轮一条）';
COMMENT ON COLUMN acceptance_record.task_id IS '迹地更新任务 ID，关联 regeneration_task.id';
COMMENT ON COLUMN acceptance_record.round IS '验收轮次：1=首轮，2=补植轮';
COMMENT ON COLUMN acceptance_record.request_id IS '幂等键：验收提交请求编号';
COMMENT ON COLUMN acceptance_record.fingerprint IS '幂等指纹：requestId 与全部业务字段的 SHA-256；同键异参返回 409';
COMMENT ON COLUMN acceptance_record.pass_rate IS '验收合格率（百分比 0-100），不低于 85 为合格，恰达 85 合格';
COMMENT ON COLUMN acceptance_record.result IS '本轮结果：PASSED=合格，FAILED=不合格';
COMMENT ON COLUMN acceptance_record.created_at IS '提交时间（UTC）';

CREATE TABLE IF NOT EXISTS operator_blacklist (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    operator_id VARCHAR(64) NOT NULL,
    banned_until DATE NOT NULL,
    reason VARCHAR(128),
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_operator_blacklist_operator UNIQUE (operator_id)
);
COMMENT ON TABLE operator_blacklist IS '经营主体限批名单';
COMMENT ON COLUMN operator_blacklist.operator_id IS '经营主体标识';
COMMENT ON COLUMN operator_blacklist.banned_until IS '限批截止日（UTC，含当日），期限内新申请一律拒绝';
COMMENT ON COLUMN operator_blacklist.reason IS '列入原因';
COMMENT ON COLUMN operator_blacklist.created_at IS '列入时间（UTC）';

CREATE TABLE IF NOT EXISTS late_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    entity_type VARCHAR(32) NOT NULL,
    entity_id BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    payload VARCHAR(512),
    created_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE late_event IS '迟到事件（归档终态后到达的数据只记录不处理）';
COMMENT ON COLUMN late_event.entity_type IS '实体类型：LICENSE=许可证，REGENERATION_TASK=更新任务';
COMMENT ON COLUMN late_event.entity_id IS '实体 ID';
COMMENT ON COLUMN late_event.event_type IS '事件类型：CONFIRM=采伐确认，ACCEPTANCE=更新验收';
COMMENT ON COLUMN late_event.payload IS '迟到数据摘要';
COMMENT ON COLUMN late_event.created_at IS '记录时间（UTC）';
