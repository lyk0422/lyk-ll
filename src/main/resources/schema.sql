-- Planning Scene 版本服务建表脚本（H2，MODE=MySQL）。
-- 进程退出后数据不保留，无需跨进程恢复。

CREATE TABLE IF NOT EXISTS scene_meta (
    id TINYINT NOT NULL COMMENT '单行元数据主键，固定为 0',
    version BIGINT NOT NULL COMMENT '场景单调递增版本号，初始为 0',
    PRIMARY KEY (id)
);
COMMENT ON TABLE scene_meta IS 'Planning Scene 全局元数据（单行）';

CREATE TABLE IF NOT EXISTS robot_link (
    name VARCHAR(64) NOT NULL COMMENT '机器人 Link 名称，全局唯一',
    PRIMARY KEY (name)
);
COMMENT ON TABLE robot_link IS '机器人模型 Link 表';

CREATE TABLE IF NOT EXISTS scene_frame (
    name VARCHAR(64) NOT NULL COMMENT '坐标系名称，world 及各 Link 坐标系',
    PRIMARY KEY (name)
);
COMMENT ON TABLE scene_frame IS '可用坐标系表';

CREATE TABLE IF NOT EXISTS robot_joint (
    name VARCHAR(64) NOT NULL COMMENT '关节名称，全局唯一',
    position DOUBLE NOT NULL COMMENT '当前关节位置，单位弧度',
    min_limit DOUBLE NOT NULL COMMENT '关节位置下限，单位弧度，含边界',
    max_limit DOUBLE NOT NULL COMMENT '关节位置上限，单位弧度，含边界',
    PRIMARY KEY (name)
);
COMMENT ON TABLE robot_joint IS '机器人关节状态表';

CREATE TABLE IF NOT EXISTS world_object (
    id VARCHAR(64) NOT NULL COMMENT '碰撞对象 ID，世界与附着集合合并后全局唯一',
    frame_id VARCHAR(64) NOT NULL COMMENT '位姿参考坐标系，必须存在于 scene_frame',
    px DOUBLE NOT NULL COMMENT '位置 X，单位米',
    py DOUBLE NOT NULL COMMENT '位置 Y，单位米',
    pz DOUBLE NOT NULL COMMENT '位置 Z，单位米',
    qx DOUBLE NOT NULL COMMENT '姿态四元数 X，无量纲',
    qy DOUBLE NOT NULL COMMENT '姿态四元数 Y，无量纲',
    qz DOUBLE NOT NULL COMMENT '姿态四元数 Z，无量纲',
    qw DOUBLE NOT NULL COMMENT '姿态四元数 W，无量纲',
    sx DOUBLE NOT NULL COMMENT '盒体尺寸 X，单位米，必须为正有限数',
    sy DOUBLE NOT NULL COMMENT '盒体尺寸 Y，单位米，必须为正有限数',
    sz DOUBLE NOT NULL COMMENT '盒体尺寸 Z，单位米，必须为正有限数',
    PRIMARY KEY (id)
);
COMMENT ON TABLE world_object IS '世界碰撞对象表';

CREATE TABLE IF NOT EXISTS attached_object (
    id VARCHAR(64) NOT NULL COMMENT '附着物 ID，与 world_object 合并后全局唯一',
    link_name VARCHAR(64) NOT NULL COMMENT '附着到的机器人 Link，必须存在于 robot_link',
    px DOUBLE NOT NULL COMMENT '相对 Link 位置 X，单位米',
    py DOUBLE NOT NULL COMMENT '相对 Link 位置 Y，单位米',
    pz DOUBLE NOT NULL COMMENT '相对 Link 位置 Z，单位米',
    qx DOUBLE NOT NULL COMMENT '相对姿态四元数 X，无量纲',
    qy DOUBLE NOT NULL COMMENT '相对姿态四元数 Y，无量纲',
    qz DOUBLE NOT NULL COMMENT '相对姿态四元数 Z，无量纲',
    qw DOUBLE NOT NULL COMMENT '相对姿态四元数 W，无量纲',
    sx DOUBLE NOT NULL COMMENT '盒体尺寸 X，单位米，必须为正有限数',
    sy DOUBLE NOT NULL COMMENT '盒体尺寸 Y，单位米，必须为正有限数',
    sz DOUBLE NOT NULL COMMENT '盒体尺寸 Z，单位米，必须为正有限数',
    PRIMARY KEY (id)
);
COMMENT ON TABLE attached_object IS '附着到机器人 Link 的碰撞对象表';

CREATE TABLE IF NOT EXISTS applied_request (
    request_id VARCHAR(128) NOT NULL COMMENT '客户端幂等请求 ID',
    request_hash VARCHAR(64) NOT NULL COMMENT '规范化请求体 SHA-256，用于同键异参检测',
    scene_version BIGINT NOT NULL COMMENT '该请求提交后产生的场景版本号',
    response_json CLOB NOT NULL COMMENT '首次成功响应的 JSON，幂等重放时原样返回',
    PRIMARY KEY (request_id)
);
COMMENT ON TABLE applied_request IS '幂等请求记录表';

CREATE TABLE IF NOT EXISTS scene_event (
    seq BIGINT NOT NULL AUTO_INCREMENT COMMENT '事件自增序号',
    version BIGINT NOT NULL COMMENT '产生该变化的场景版本号（变更后的新版本）',
    kind VARCHAR(8) NOT NULL COMMENT '变化对象类别：OBJECT 或 JOINT',
    target_id VARCHAR(64) NOT NULL COMMENT '发生变化的对象 ID 或关节名',
    PRIMARY KEY (seq)
);
COMMENT ON TABLE scene_event IS '场景变更事件表，用于版本差异查询';

CREATE TABLE IF NOT EXISTS scene_snapshot (
    version BIGINT NOT NULL COMMENT '场景版本号',
    payload CLOB NOT NULL COMMENT '该版本不可变快照的 JSON 内容',
    PRIMARY KEY (version)
);
COMMENT ON TABLE scene_snapshot IS '历史版本不可变快照表';
