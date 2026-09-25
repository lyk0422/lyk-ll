-- 本地港区靠泊窗口服务建表脚本（H2，MODE=MySQL）。
-- 所有时刻字段均为 UTC；窗口按半开区间 [start, end) 判定冲突。

CREATE TABLE IF NOT EXISTS berth (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    max_draft_m DECIMAL(5,2) NOT NULL
);
COMMENT ON TABLE berth IS '泊位（合成数据）';
COMMENT ON COLUMN berth.id IS '泊位编号';
COMMENT ON COLUMN berth.name IS '泊位名称';
COMMENT ON COLUMN berth.max_draft_m IS '泊位限深（米）；申请吃水超过该值直接拒绝且不产生记录';

CREATE TABLE IF NOT EXISTS pilot (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    name VARCHAR(64) NOT NULL
);
COMMENT ON TABLE pilot IS '引航员（合成数据）';
COMMENT ON COLUMN pilot.id IS '引航员编号';
COMMENT ON COLUMN pilot.name IS '引航员姓名（合成数据）';

CREATE TABLE IF NOT EXISTS allowed_window (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    start_utc TIMESTAMP NOT NULL,
    end_utc TIMESTAMP NOT NULL
);
COMMENT ON TABLE allowed_window IS '每日允许靠泊时段（合成数据初始化）';
COMMENT ON COLUMN allowed_window.id IS '主键';
COMMENT ON COLUMN allowed_window.start_utc IS '允许时段起始（UTC，含边界）';
COMMENT ON COLUMN allowed_window.end_utc IS '允许时段结束（UTC，含边界）';

CREATE TABLE IF NOT EXISTS berth_window (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    vessel_name VARCHAR(128) NOT NULL,
    berth_id VARCHAR(32) NOT NULL,
    start_utc TIMESTAMP NOT NULL,
    end_utc TIMESTAMP NOT NULL,
    draft_m DECIMAL(5,2) NOT NULL,
    pilots_required INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    replaces_request_id VARCHAR(64) NULL,
    created_utc TIMESTAMP NOT NULL,
    updated_utc TIMESTAMP NOT NULL,
    CONSTRAINT uk_berth_window_request UNIQUE (request_id)
);
COMMENT ON TABLE berth_window IS '靠泊窗口申请；状态机 PENDING/APPROVED/ACTIVE/REJECTED/CANCELLED/COMPLETED';
COMMENT ON COLUMN berth_window.id IS '主键';
COMMENT ON COLUMN berth_window.request_id IS '幂等请求编号，全局唯一';
COMMENT ON COLUMN berth_window.fingerprint IS '幂等指纹：requestId 与全部业务字段规范化序列化的 SHA-256（十六进制）';
COMMENT ON COLUMN berth_window.vessel_name IS '船名（合成数据）';
COMMENT ON COLUMN berth_window.berth_id IS '泊位编号';
COMMENT ON COLUMN berth_window.start_utc IS '预计靠泊开始（UTC，半开区间起点，含）';
COMMENT ON COLUMN berth_window.end_utc IS '预计靠泊结束（UTC，半开区间终点，不含）';
COMMENT ON COLUMN berth_window.draft_m IS '船舶吃水（米）';
COMMENT ON COLUMN berth_window.pilots_required IS '所需引航员数量，0 表示无需引航';
COMMENT ON COLUMN berth_window.status IS '窗口状态：PENDING 待审批 / APPROVED 已批准 / ACTIVE 已生效 / REJECTED 已驳回（终态）/ CANCELLED 已取消（终态）/ COMPLETED 已完成（终态）';
COMMENT ON COLUMN berth_window.replaces_request_id IS '改期来源请求编号；NULL 表示非改期申请';
COMMENT ON COLUMN berth_window.created_utc IS '创建时刻（UTC）';
COMMENT ON COLUMN berth_window.updated_utc IS '最近状态变更时刻（UTC）';

CREATE TABLE IF NOT EXISTS window_pilot (
    window_id BIGINT NOT NULL,
    pilot_id VARCHAR(32) NOT NULL,
    PRIMARY KEY (window_id, pilot_id)
);
COMMENT ON TABLE window_pilot IS '窗口-引航员分配关系；审批通过时写入';
COMMENT ON COLUMN window_pilot.window_id IS '靠泊窗口主键';
COMMENT ON COLUMN window_pilot.pilot_id IS '引航员编号';

-- 审批串行化锁（单行），保证泊位冲突与引航员分配检查的原子性
CREATE TABLE IF NOT EXISTS approval_lock (
    id INT NOT NULL PRIMARY KEY
);
COMMENT ON TABLE approval_lock IS '审批串行化锁表（单行），SELECT ... FOR UPDATE 使用';
COMMENT ON COLUMN approval_lock.id IS '锁行主键，固定为 1';
