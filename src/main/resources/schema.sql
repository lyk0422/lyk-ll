-- 本地港区靠泊窗口服务建表脚本（H2，MODE=MySQL），数据仅在 JVM 生命周期内保留。

CREATE TABLE IF NOT EXISTS berth (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(64) NOT NULL COMMENT '泊位名称（合成数据）',
    depth_limit DECIMAL(6,2) NOT NULL COMMENT '泊位限深，单位米；申请吃水超过该值直接拒绝'
) COMMENT '泊位表';

CREATE TABLE IF NOT EXISTS pilot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(64) NOT NULL COMMENT '引航员姓名（合成数据）'
) COMMENT '引航员表';

CREATE TABLE IF NOT EXISTS allowed_window (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    start_time TIMESTAMP NOT NULL COMMENT '允许靠泊时段起始时刻（UTC，含端点）',
    end_time TIMESTAMP NOT NULL COMMENT '允许靠泊时段结束时刻（UTC，含端点）；申请起止须完整落在某段内'
) COMMENT '每日允许靠泊时段表（合成数据，按日逐行初始化）';

CREATE TABLE IF NOT EXISTS berth_window (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL COMMENT '幂等键：客户端请求编号',
    fingerprint VARCHAR(512) NOT NULL COMMENT '幂等指纹：requestId 与全部业务字段的无歧义序列化',
    vessel_name VARCHAR(128) NOT NULL COMMENT '船名',
    berth_id BIGINT NOT NULL COMMENT '泊位ID，关联 berth.id',
    start_time TIMESTAMP NOT NULL COMMENT '预计靠泊起始时刻（UTC），半开区间左端点',
    end_time TIMESTAMP NOT NULL COMMENT '预计靠泊结束时刻（UTC），半开区间右端点（不含）',
    draft DECIMAL(6,2) NOT NULL COMMENT '船舶吃水，单位米',
    pilots_required INT NOT NULL COMMENT '所需引航员数',
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING待审批/APPROVED已批准/ACTIVE已生效/COMPLETED已完成/REJECTED已驳回/CANCELLED已取消',
    created_at TIMESTAMP NOT NULL COMMENT '创建时刻（UTC）',
    updated_at TIMESTAMP NOT NULL COMMENT '最近状态变更时刻（UTC）',
    CONSTRAINT uk_berth_window_request_id UNIQUE (request_id),
    CONSTRAINT fk_berth_window_berth FOREIGN KEY (berth_id) REFERENCES berth (id)
) COMMENT '靠泊窗口申请表';

CREATE TABLE IF NOT EXISTS window_pilot (
    window_id BIGINT NOT NULL COMMENT '靠泊窗口ID，关联 berth_window.id',
    pilot_id BIGINT NOT NULL COMMENT '引航员ID，关联 pilot.id',
    PRIMARY KEY (window_id, pilot_id),
    CONSTRAINT fk_window_pilot_window FOREIGN KEY (window_id) REFERENCES berth_window (id),
    CONSTRAINT fk_window_pilot_pilot FOREIGN KEY (pilot_id) REFERENCES pilot (id)
) COMMENT '窗口-引航员分配表：每名引航员同一时段最多服务一艘船';
