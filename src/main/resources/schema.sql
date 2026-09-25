-- 港区靠泊窗口服务建表脚本（H2，MODE=MySQL）。
-- 时间统一以 UTC epoch 秒（BIGINT）存储，避免时区歧义；窗口为半开区间 [start, end)。

CREATE TABLE IF NOT EXISTS berth (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    max_draft_m DECIMAL(6,2) NOT NULL
);

COMMENT ON TABLE berth IS '泊位';
COMMENT ON COLUMN berth.id IS '泊位主键';
COMMENT ON COLUMN berth.name IS '泊位名称，唯一';
COMMENT ON COLUMN berth.max_draft_m IS '泊位限深，单位米；申请吃水超过该值直接拒绝';

CREATE UNIQUE INDEX IF NOT EXISTS uk_berth_name ON berth (name);

CREATE TABLE IF NOT EXISTS pilot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(64) NOT NULL
);

COMMENT ON TABLE pilot IS '引航员';
COMMENT ON COLUMN pilot.id IS '引航员主键';
COMMENT ON COLUMN pilot.name IS '引航员姓名（合成数据），唯一';

CREATE UNIQUE INDEX IF NOT EXISTS uk_pilot_name ON pilot (name);

CREATE TABLE IF NOT EXISTS allowed_window (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    start_epoch BIGINT NOT NULL,
    end_epoch BIGINT NOT NULL
);

COMMENT ON TABLE allowed_window IS '每日允许靠泊时段（合成数据），UTC epoch 秒';
COMMENT ON COLUMN allowed_window.id IS '主键';
COMMENT ON COLUMN allowed_window.start_epoch IS '允许时段开始（UTC epoch 秒，含端点）';
COMMENT ON COLUMN allowed_window.end_epoch IS '允许时段结束（UTC epoch 秒，含端点；申请端点恰等于边界合法）';

CREATE TABLE IF NOT EXISTS berth_window (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    vessel_name VARCHAR(128) NOT NULL,
    berth_id BIGINT NOT NULL,
    start_epoch BIGINT NOT NULL,
    end_epoch BIGINT NOT NULL,
    draft_m DECIMAL(6,2) NOT NULL,
    pilots_required INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_epoch BIGINT NOT NULL,
    updated_epoch BIGINT NOT NULL,
    CONSTRAINT fk_window_berth FOREIGN KEY (berth_id) REFERENCES berth (id)
);

COMMENT ON TABLE berth_window IS '靠泊窗口申请；状态机 PENDING/APPROVED/ACTIVE/REJECTED/CANCELLED/COMPLETED';
COMMENT ON COLUMN berth_window.id IS '窗口主键';
COMMENT ON COLUMN berth_window.request_id IS '幂等请求键，唯一';
COMMENT ON COLUMN berth_window.fingerprint IS '请求指纹（SHA-256 十六进制），覆盖 requestId 与全部业务字段';
COMMENT ON COLUMN berth_window.vessel_name IS '船名';
COMMENT ON COLUMN berth_window.berth_id IS '泊位外键';
COMMENT ON COLUMN berth_window.start_epoch IS '预计靠泊开始（UTC epoch 秒，含）';
COMMENT ON COLUMN berth_window.end_epoch IS '预计靠泊结束（UTC epoch 秒，不含；半开区间）';
COMMENT ON COLUMN berth_window.draft_m IS '船舶吃水，单位米';
COMMENT ON COLUMN berth_window.pilots_required IS '所需引航员数量';
COMMENT ON COLUMN berth_window.status IS '窗口状态：PENDING 待审批 / APPROVED 已批准 / ACTIVE 已生效 / REJECTED 驳回 / CANCELLED 已取消 / COMPLETED 已完成';
COMMENT ON COLUMN berth_window.created_epoch IS '创建时间（UTC epoch 秒）';
COMMENT ON COLUMN berth_window.updated_epoch IS '最近状态变更时间（UTC epoch 秒）';

CREATE UNIQUE INDEX IF NOT EXISTS uk_window_request ON berth_window (request_id);
CREATE INDEX IF NOT EXISTS idx_window_berth_time ON berth_window (berth_id, start_epoch, end_epoch);

CREATE TABLE IF NOT EXISTS window_pilot (
    window_id BIGINT NOT NULL,
    pilot_id BIGINT NOT NULL,
    PRIMARY KEY (window_id, pilot_id),
    CONSTRAINT fk_wp_window FOREIGN KEY (window_id) REFERENCES berth_window (id),
    CONSTRAINT fk_wp_pilot FOREIGN KEY (pilot_id) REFERENCES pilot (id)
);

COMMENT ON TABLE window_pilot IS '窗口-引航员分配；仅已批准/已生效窗口占用引航员';
COMMENT ON COLUMN window_pilot.window_id IS '窗口外键';
COMMENT ON COLUMN window_pilot.pilot_id IS '引航员外键';
