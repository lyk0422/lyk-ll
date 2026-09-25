-- 卫星测控过境窗口编排服务：H2（MODE=MySQL）建表脚本
-- 时间统一存储 UTC；窗口为半开区间 [start_time, end_time)
CREATE TABLE IF NOT EXISTS satellite (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    code       VARCHAR(64)  NOT NULL,
    name       VARCHAR(128) NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_satellite_code ON satellite (code);
COMMENT ON TABLE satellite IS '卫星主数据（合成数据，仅存于 JVM 内存）';
COMMENT ON COLUMN satellite.code IS '卫星业务编码，全局唯一';

CREATE TABLE IF NOT EXISTS ground_station (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    code       VARCHAR(64)  NOT NULL,
    name       VARCHAR(128) NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_station_code ON ground_station (code);
COMMENT ON TABLE ground_station IS '地面站主数据（合成数据，仅存于 JVM 内存）';
COMMENT ON COLUMN ground_station.code IS '地面站业务编码，全局唯一';

CREATE TABLE IF NOT EXISTS contact_window (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    satellite_id BIGINT NOT NULL,
    station_id   BIGINT NOT NULL,
    start_time   TIMESTAMP NOT NULL,
    end_time     TIMESTAMP NOT NULL,
    day_key      VARCHAR(10) NOT NULL,
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_window_natural
    ON contact_window (satellite_id, station_id, start_time);
CREATE INDEX IF NOT EXISTS idx_window_query
    ON contact_window (station_id, satellite_id, day_key);
COMMENT ON TABLE contact_window IS '过境预报窗口（合成数据），半开区间 [start_time,end_time)，UTC';
COMMENT ON COLUMN contact_window.day_key IS '窗口起始时刻对应的 UTC 日期 yyyy-MM-dd，编排按日发布';
COMMENT ON COLUMN contact_window.start_time IS '窗口开始时刻（含端点，UTC）';
COMMENT ON COLUMN contact_window.end_time IS '窗口结束时刻（不含端点，UTC），与下一窗口端点相接不算冲突';

CREATE TABLE IF NOT EXISTS schedule_version (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    station_id   BIGINT NOT NULL,
    day_key      VARCHAR(10) NOT NULL,
    version_no   INT NOT NULL,
    status       VARCHAR(16) NOT NULL,
    published_at TIMESTAMP,
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_version_natural
    ON schedule_version (station_id, day_key, version_no);
COMMENT ON TABLE schedule_version IS '编排表按日版本；DRAFT 可编排，PUBLISHED 即冻结，紧急插入走新版本，旧版本可查';
COMMENT ON COLUMN schedule_version.status IS '版本状态：DRAFT-编排中草稿，PUBLISHED-已发布冻结';
COMMENT ON COLUMN schedule_version.version_no IS '同站同日版本号，从 1 递增';

CREATE TABLE IF NOT EXISTS schedule_request (
    request_id       VARCHAR(64) NOT NULL,
    fingerprint      VARCHAR(64) NOT NULL,
    satellite_id     BIGINT NOT NULL,
    station_id       BIGINT NOT NULL,
    window_id        BIGINT NOT NULL,
    priority         INT NOT NULL,
    outcome          VARCHAR(16) NOT NULL,
    result_entry_id  BIGINT,
    conflict_detail  CLOB,
    completed        BOOLEAN NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_schedule_request PRIMARY KEY (request_id)
);
COMMENT ON TABLE schedule_request IS '编排申请幂等记录；request_id 同键同参返回首次结果，同键异参 409';
COMMENT ON COLUMN schedule_request.fingerprint IS '指纹=SHA-256(requestId|卫星|地面站|窗口|优先级)';
COMMENT ON COLUMN schedule_request.priority IS '任务优先级 1-5，5 最高';
COMMENT ON COLUMN schedule_request.outcome IS '首次处理结果：SCHEDULED-已编排，REJECTED-冲突拒绝';
COMMENT ON COLUMN schedule_request.completed IS '是否已完成首次处理；FALSE 表示并发处理中占位，重放需等待行锁';
COMMENT ON COLUMN schedule_request.conflict_detail IS '拒绝时的冲突详情 JSON（冲突条目与窗口）';

CREATE TABLE IF NOT EXISTS schedule_entry (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id              VARCHAR(64) NOT NULL,
    satellite_id            BIGINT NOT NULL,
    station_id              BIGINT NOT NULL,
    window_id               BIGINT NOT NULL,
    version_id              BIGINT NOT NULL,
    priority                INT NOT NULL,
    status                  VARCHAR(16) NOT NULL,
    start_time              TIMESTAMP NOT NULL,
    end_time                TIMESTAMP NOT NULL,
    preempted_by_entry_id   BIGINT,
    suggestion_window_id    BIGINT,
    suggestion_start_time   TIMESTAMP,
    suggestion_end_time     TIMESTAMP,
    created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_entry_request ON schedule_entry (request_id);
CREATE INDEX IF NOT EXISTS idx_entry_station_time
    ON schedule_entry (station_id, start_time, end_time);
CREATE INDEX IF NOT EXISTS idx_entry_version ON schedule_entry (version_id);
CREATE INDEX IF NOT EXISTS idx_entry_window ON schedule_entry (station_id, window_id);
COMMENT ON TABLE schedule_entry IS '编排条目（同站同时刻仅一颗卫星，半开区间端点相接不冲突）';
COMMENT ON COLUMN schedule_entry.priority IS '任务优先级 1-5，5 最高；高优先级抢占低优先级';
COMMENT ON COLUMN schedule_entry.status IS '状态：SCHEDULED-已编排，PREEMPTED-被挤占，CANCELLED-已撤销，MISSED-漏报';
COMMENT ON COLUMN schedule_entry.preempted_by_entry_id IS '被挤占时指向抢占方条目，空值表示未发生抢占';
COMMENT ON COLUMN schedule_entry.suggestion_window_id IS '被挤占后的重排建议窗口，空值表示暂无可用替代窗口';

CREATE TABLE IF NOT EXISTS contact_report (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    station_id  BIGINT NOT NULL,
    window_id   BIGINT NOT NULL,
    entry_id    BIGINT NOT NULL,
    status      VARCHAR(16) NOT NULL,
    remark      VARCHAR(512),
    reported_at TIMESTAMP NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_report_window
    ON contact_report (station_id, window_id);
COMMENT ON TABLE contact_report IS '执行回报，按 窗口+站 去重；状态提交后不可改，补救须申请新窗口';
COMMENT ON COLUMN contact_report.status IS '回报状态终态：SUCCESS-成功，FAILURE-失败，PARTIAL-部分成功';

CREATE TABLE IF NOT EXISTS contact_report_event (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    station_id  BIGINT NOT NULL,
    window_id   BIGINT NOT NULL,
    event_type  VARCHAR(24) NOT NULL,
    payload     CLOB,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_event_window
    ON contact_report_event (station_id, window_id);
COMMENT ON TABLE contact_report_event IS '回报相关事件流水；漏报后迟到回报只记 LATE_REPORT 事件，不改漏报事实';
COMMENT ON COLUMN contact_report_event.event_type IS '事件类型：LATE_REPORT-漏报后的迟到回报';

CREATE TABLE IF NOT EXISTS station_day_lock (
    station_id BIGINT NOT NULL,
    day_key    VARCHAR(10) NOT NULL,
    CONSTRAINT pk_station_day_lock PRIMARY KEY (station_id, day_key)
);
COMMENT ON TABLE station_day_lock IS '同站同日编排串行化锁表；事务内对该行 FOR UPDATE，提交即释放';
