-- 卫星测控过境窗口编排服务建表脚本（H2 MySQL 兼容模式，语法兼容 MySQL 8）
-- 时间列均为 UTC；窗口为半开区间 [start_utc, end_utc)。

CREATE TABLE IF NOT EXISTS satellite (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(32) NOT NULL COMMENT '卫星业务编码',
    name VARCHAR(64) NOT NULL COMMENT '卫星名称',
    CONSTRAINT uk_satellite_code UNIQUE (code)
) COMMENT='卫星（合成数据）';

CREATE TABLE IF NOT EXISTS ground_station (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(32) NOT NULL COMMENT '地面站业务编码',
    name VARCHAR(64) NOT NULL COMMENT '地面站名称',
    CONSTRAINT uk_ground_station_code UNIQUE (code)
) COMMENT='地面站（合成数据）';

CREATE TABLE IF NOT EXISTS pass_window (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    window_code VARCHAR(32) NOT NULL COMMENT '过境窗口业务编码',
    satellite_id BIGINT NOT NULL COMMENT '卫星ID',
    station_id BIGINT NOT NULL COMMENT '地面站ID',
    start_utc TIMESTAMP NOT NULL COMMENT '窗口开始时刻（UTC，含端点）',
    end_utc TIMESTAMP NOT NULL COMMENT '窗口结束时刻（UTC，不含端点）',
    CONSTRAINT uk_pass_window_code UNIQUE (window_code),
    CONSTRAINT fk_window_sat FOREIGN KEY (satellite_id) REFERENCES satellite (id),
    CONSTRAINT fk_window_station FOREIGN KEY (station_id) REFERENCES ground_station (id)
) COMMENT='卫星过境窗口预报（合成数据）';

CREATE TABLE IF NOT EXISTS schedule_request (
    request_id VARCHAR(64) PRIMARY KEY COMMENT '编排申请幂等键',
    fingerprint VARCHAR(64) NOT NULL COMMENT '请求指纹：requestId+卫星+地面站+窗口+优先级 的 SHA-256',
    satellite_id BIGINT NOT NULL COMMENT '卫星ID',
    station_id BIGINT NOT NULL COMMENT '地面站ID',
    window_id BIGINT NOT NULL COMMENT '窗口ID',
    priority INT NOT NULL COMMENT '任务优先级 1-5，5 最高',
    http_status INT NOT NULL COMMENT '首次处理返回的 HTTP 状态码',
    response_body CLOB NOT NULL COMMENT '首次处理响应体 JSON，同键同参重放时原样返回',
    created_at TIMESTAMP NOT NULL COMMENT '创建时间（UTC）'
) COMMENT='编排申请幂等记录';

CREATE TABLE IF NOT EXISTS schedule_entry (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '来源申请的 requestId',
    satellite_id BIGINT NOT NULL COMMENT '卫星ID',
    station_id BIGINT NOT NULL COMMENT '地面站ID',
    window_id BIGINT NOT NULL COMMENT '窗口ID',
    priority INT NOT NULL COMMENT '任务优先级 1-5，5 最高',
    schedule_day DATE NOT NULL COMMENT '编排日（UTC，取窗口开始日期）',
    status VARCHAR(16) NOT NULL COMMENT 'SCHEDULED=已编排 / PREEMPTED=被挤占 / CANCELLED=已撤销',
    published_version INT NULL COMMENT '发布版本号；NULL 表示未发布草稿',
    preempted_by_request_id VARCHAR(64) NULL COMMENT '挤占本条目的申请 requestId；未被挤占为 NULL',
    active_slot VARCHAR(100) NULL COMMENT '生效占用键（站ID:窗口ID），仅 SCHEDULED 状态非空，用于同站同窗并发唯一约束',
    created_at TIMESTAMP NOT NULL COMMENT '创建时间（UTC）',
    CONSTRAINT uk_entry_active_slot UNIQUE (active_slot),
    CONSTRAINT uk_entry_request UNIQUE (request_id)
) COMMENT='编排表';

CREATE TABLE IF NOT EXISTS replan_suggestion (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    entry_id BIGINT NOT NULL COMMENT '被挤占的编排条目ID',
    satellite_id BIGINT NOT NULL COMMENT '需重排的卫星ID',
    station_id BIGINT NOT NULL COMMENT '需重排的地面站ID',
    suggested_window_codes VARCHAR(512) NOT NULL COMMENT '建议重排窗口编码（逗号分隔，无建议时为空串）',
    created_at TIMESTAMP NOT NULL COMMENT '生成时间（UTC）',
    CONSTRAINT fk_suggestion_entry FOREIGN KEY (entry_id) REFERENCES schedule_entry (id)
) COMMENT='被挤占窗口的重排建议';

CREATE TABLE IF NOT EXISTS publication (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    schedule_day DATE NOT NULL COMMENT '发布日（UTC）',
    version INT NOT NULL COMMENT '版本号，自 1 递增',
    entry_count INT NOT NULL COMMENT '本版本新纳入发布的条目数',
    published_at TIMESTAMP NOT NULL COMMENT '发布时间（UTC）',
    CONSTRAINT uk_publication_day_version UNIQUE (schedule_day, version)
) COMMENT='编排日版本发布记录';

CREATE TABLE IF NOT EXISTS window_outcome (
    window_id BIGINT NOT NULL COMMENT '窗口ID',
    station_id BIGINT NOT NULL COMMENT '地面站ID',
    state VARCHAR(16) NOT NULL COMMENT 'REPORTED=已回报 / MISSED=漏报',
    report_status VARCHAR(16) NULL COMMENT '回报状态 SUCCESS/FAILED/PARTIAL；漏报时为 NULL',
    reported_at TIMESTAMP NULL COMMENT '回报时间（UTC）；漏报时为 NULL',
    missed_at TIMESTAMP NULL COMMENT '漏报标记时间（UTC）；已回报时为 NULL',
    PRIMARY KEY (window_id, station_id)
) COMMENT='窗口执行终态（每窗口每站唯一一行，漏报与回报竞态下保证唯一终态）';

CREATE TABLE IF NOT EXISTS late_report_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    window_id BIGINT NOT NULL COMMENT '窗口ID',
    station_id BIGINT NOT NULL COMMENT '地面站ID',
    report_status VARCHAR(16) NOT NULL COMMENT '迟到回报提交的状态（仅记录，不生效）',
    reported_at TIMESTAMP NOT NULL COMMENT '迟到回报时间（UTC）',
    CONSTRAINT uk_late_event UNIQUE (window_id, station_id)
) COMMENT='迟到回报事件（只记录事件，不改变漏报事实）';
