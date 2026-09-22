-- H2 (MODE=MySQL) 自动建表脚本；数据仅保留在 JVM 生命周期内。
-- 所有时间字段为 Asia/Shanghai 本地时间戳（TIMESTAMP），由应用写入。

CREATE TABLE IF NOT EXISTS map_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键，自增',
    version_id VARCHAR(64) NOT NULL COMMENT '地图版本业务标识，发布时指定，全局唯一',
    status VARCHAR(16) NOT NULL COMMENT '版本状态：ACTIVE=可服务，DEPRECATED=已废弃（不再接受新请求，已绑定请求仍可回放）',
    created_at TIMESTAMP NOT NULL COMMENT '发布时间（Asia/Shanghai）',
    CONSTRAINT uk_map_version_version_id UNIQUE (version_id)
) COMMENT='HD 地图版本；版本发布后内容不可变，热更新只能发布新版本';

CREATE TABLE IF NOT EXISTS map_segment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键，自增',
    version_id VARCHAR(64) NOT NULL COMMENT '所属地图版本业务标识',
    segment_id VARCHAR(64) NOT NULL COMMENT '路段业务标识，版本内唯一',
    start_node VARCHAR(64) NOT NULL COMMENT '起点拓扑节点标识',
    end_node VARCHAR(64) NOT NULL COMMENT '终点拓扑节点标识',
    start_x DOUBLE NOT NULL COMMENT '起点平面坐标 X，单位米',
    start_y DOUBLE NOT NULL COMMENT '起点平面坐标 Y，单位米',
    end_x DOUBLE NOT NULL COMMENT '终点平面坐标 X，单位米',
    end_y DOUBLE NOT NULL COMMENT '终点平面坐标 Y，单位米',
    active BOOLEAN NOT NULL COMMENT '路段有效状态：TRUE=可参与匹配，FALSE=已失效',
    CONSTRAINT uk_map_segment_version_segment UNIQUE (version_id, segment_id)
) COMMENT='地图路段：含端点拓扑与直线几何，随版本整体发布、不可变';

CREATE TABLE IF NOT EXISTS route_request (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键，自增',
    request_id VARCHAR(64) NOT NULL COMMENT '路线请求业务标识，幂等键，全局唯一',
    map_version_id VARCHAR(64) NOT NULL COMMENT '请求固定绑定的地图版本业务标识，执行后不可变',
    origin_x DOUBLE NOT NULL COMMENT '起点匹配输入坐标 X，单位米',
    origin_y DOUBLE NOT NULL COMMENT '起点匹配输入坐标 Y，单位米',
    origin_heading DOUBLE NOT NULL COMMENT '起点航向角，单位度，范围 [0,360)',
    dest_x DOUBLE NOT NULL COMMENT '终点匹配输入坐标 X，单位米',
    dest_y DOUBLE NOT NULL COMMENT '终点匹配输入坐标 Y，单位米',
    dest_heading DOUBLE NOT NULL COMMENT '终点航向角，单位度，范围 [0,360)',
    status VARCHAR(16) NOT NULL COMMENT '请求状态：SUCCESS=匹配成功，FAILED=匹配失败（error_code 非空）',
    error_code VARCHAR(64) NULL COMMENT '失败错误码；成功时为 NULL',
    result_json CLOB NULL COMMENT '匹配结果快照（候选评分、排序、拒绝原因、路径），JSON；失败时为 NULL',
    created_at TIMESTAMP NOT NULL COMMENT '请求创建时间（Asia/Shanghai）',
    CONSTRAINT uk_route_request_request_id UNIQUE (request_id)
) COMMENT='路线请求：按 requestId 幂等；同参重放返回原结果，异参重放返回 409';
