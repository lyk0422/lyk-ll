-- 轨迹安全校验服务建表脚本（H2 MODE=MySQL；仅嵌入式本地库启动时自动执行）
-- 进程退出后内存数据不保留，无需跨进程恢复。

-- 不可变规则版本：阈值单位见 RuleConfig 的 Javadoc
CREATE TABLE IF NOT EXISTS rule_version (
    rule_version      VARCHAR(64) NOT NULL,
    max_time_interval DOUBLE      NOT NULL,
    max_speed         DOUBLE      NOT NULL,
    max_acceleration  DOUBLE      NOT NULL,
    max_curvature     DOUBLE      NOT NULL,
    road_tolerance    DOUBLE      NOT NULL,
    safety_distance   DOUBLE      NOT NULL,
    created_at        TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_rule_version PRIMARY KEY (rule_version)
);
COMMENT ON TABLE rule_version IS '不可变规则版本，发布后不可原地修改';
COMMENT ON COLUMN rule_version.rule_version IS '规则版本号，全局唯一，发布后不可变';
COMMENT ON COLUMN rule_version.max_time_interval IS '相邻轨迹点最大时间间隔，单位秒';
COMMENT ON COLUMN rule_version.max_speed IS '最大允许速度，单位米每秒';
COMMENT ON COLUMN rule_version.max_acceleration IS '最大允许加速度绝对值，单位米每二次方秒';
COMMENT ON COLUMN rule_version.max_curvature IS '最大允许曲率绝对值，单位 1/米';
COMMENT ON COLUMN rule_version.road_tolerance IS '道路容差，单位米；多边形外但距离不超过该值仍合法';
COMMENT ON COLUMN rule_version.safety_distance IS '碰撞安全距离，单位米；距离小于或等于该值即碰撞风险';
COMMENT ON COLUMN rule_version.created_at IS '发布时间，Asia/Shanghai';

-- 幂等校验记录：request_id 为幂等键，payload_hash 用于同键异参检测
CREATE TABLE IF NOT EXISTS validation_record (
    request_id    VARCHAR(128) NOT NULL,
    rule_version  VARCHAR(64)  NOT NULL,
    payload_hash  VARCHAR(64)  NOT NULL,
    request_json  CLOB         NOT NULL,
    result_json   CLOB         NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_validation_record PRIMARY KEY (request_id)
);
COMMENT ON TABLE validation_record IS '轨迹校验请求与确定性结果，按 request_id 幂等';
COMMENT ON COLUMN validation_record.request_id IS '幂等键；同键同参返回原结果，同键异参返回 409';
COMMENT ON COLUMN validation_record.rule_version IS '本次复核使用的规则版本';
COMMENT ON COLUMN validation_record.payload_hash IS '除 requestId 外请求参数的 SHA-256，用于冲突判定';
COMMENT ON COLUMN validation_record.request_json IS '规范化后的完整请求载荷 JSON，用于审计复核';
COMMENT ON COLUMN validation_record.result_json IS '确定性排序后的完整校验结果 JSON';
COMMENT ON COLUMN validation_record.created_at IS '首次提交时间，Asia/Shanghai';
