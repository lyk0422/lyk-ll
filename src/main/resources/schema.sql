-- Planning Scene 版本服务表结构（H2 内存库 MODE=MySQL，进程退出后数据不保留）
CREATE TABLE IF NOT EXISTS scene_meta (
    id TINYINT PRIMARY KEY,
    current_version BIGINT NOT NULL COMMENT '当前场景版本号，单调递增，初始为 0'
);

CREATE TABLE IF NOT EXISTS scene_robot_state (
    version BIGINT PRIMARY KEY COMMENT '场景版本号',
    pos_x DOUBLE NOT NULL COMMENT '机器人根姿态 X（米，相对 world）',
    pos_y DOUBLE NOT NULL COMMENT '机器人根姿态 Y（米，相对 world）',
    pos_z DOUBLE NOT NULL COMMENT '机器人根姿态 Z（米，相对 world）',
    qx DOUBLE NOT NULL COMMENT '机器人根姿态四元数 X',
    qy DOUBLE NOT NULL COMMENT '机器人根姿态四元数 Y',
    qz DOUBLE NOT NULL COMMENT '机器人根姿态四元数 Z',
    qw DOUBLE NOT NULL COMMENT '机器人根姿态四元数 W'
);

CREATE TABLE IF NOT EXISTS scene_joint (
    version BIGINT NOT NULL COMMENT '该行状态所属的场景版本',
    joint_name VARCHAR(255) NOT NULL COMMENT '关节名，版本内唯一',
    position DOUBLE NOT NULL COMMENT '关节位置（弧度/米，取决于关节类型）',
    lower_bound DOUBLE NOT NULL COMMENT '关节下限（单位同 position）',
    upper_bound DOUBLE NOT NULL COMMENT '关节上限（单位同 position）',
    PRIMARY KEY (version, joint_name)
);

CREATE TABLE IF NOT EXISTS scene_object (
    version BIGINT NOT NULL COMMENT '该行状态所属的场景版本',
    object_id VARCHAR(255) NOT NULL COMMENT '碰撞对象 ID，版本内唯一',
    object_type VARCHAR(16) NOT NULL COMMENT '对象归属：WORLD=世界碰撞对象，ATTACHED=附着物',
    frame_id VARCHAR(255) COMMENT '世界对象引用的坐标系（world、机器人 Link 或其他对象 ID）；ATTACHED 行为 NULL',
    link_id VARCHAR(255) COMMENT '附着物附着的机器人 Link；WORLD 行为 NULL',
    geometry_json CLOB COMMENT '几何描述（合成 JSON）',
    pose_json CLOB COMMENT '相对引用坐标系的位姿 JSON',
    touch_links_csv VARCHAR(2000) COMMENT '附着物允许接触的 Link 名列表，逗号分隔；NULL 表示空',
    PRIMARY KEY (version, object_id)
);

CREATE TABLE IF NOT EXISTS scene_request (
    request_id VARCHAR(64) PRIMARY KEY COMMENT '客户端幂等键',
    payload_hash VARCHAR(64) NOT NULL COMMENT '首次请求参数摘要，用于检测同键异参',
    http_status INT NOT NULL COMMENT '首次响应 HTTP 状态码',
    response_json CLOB NOT NULL COMMENT '首次响应体快照（同键同参原样返回）',
    created_at BIGINT NOT NULL COMMENT '首次请求时间（Epoch 毫秒）'
);

-- 静态机器人模型（Link 坐标系与关节范围），不属于版本化历史
CREATE TABLE IF NOT EXISTS robot_link (
    link_name VARCHAR(255) PRIMARY KEY COMMENT '机器人 Link（坐标系）名，world 为全局基坐标系'
);

CREATE TABLE IF NOT EXISTS robot_joint (
    joint_name VARCHAR(255) PRIMARY KEY COMMENT '关节名，模型内唯一',
    lower_bound DOUBLE NOT NULL COMMENT '关节下限（弧度或米）',
    upper_bound DOUBLE NOT NULL COMMENT '关节上限（弧度或米）'
);

-- 合成默认机器人模型：3 个关节、4 个 Link（含 world 基坐标系）
MERGE INTO robot_link (link_name) KEY(link_name) VALUES ('world');
MERGE INTO robot_link (link_name) KEY(link_name) VALUES ('base_link');
MERGE INTO robot_link (link_name) KEY(link_name) VALUES ('arm_link');
MERGE INTO robot_link (link_name) KEY(link_name) VALUES ('gripper_link');

MERGE INTO robot_joint (joint_name, lower_bound, upper_bound) KEY(joint_name) VALUES ('joint_1', -3.14159, 3.14159);
MERGE INTO robot_joint (joint_name, lower_bound, upper_bound) KEY(joint_name) VALUES ('joint_2', -1.5708, 1.5708);
MERGE INTO robot_joint (joint_name, lower_bound, upper_bound) KEY(joint_name) VALUES ('joint_3', 0.0, 0.1);

-- 初始场景：版本 0，空世界，关节处于 0 位
INSERT INTO scene_meta (id, current_version) SELECT 0, 0 WHERE NOT EXISTS (SELECT 1 FROM scene_meta);
INSERT INTO scene_robot_state (version, pos_x, pos_y, pos_z, qx, qy, qz, qw)
SELECT 0, 0, 0, 0, 0, 0, 0, 1 WHERE NOT EXISTS (SELECT 1 FROM scene_robot_state WHERE version = 0);
INSERT INTO scene_joint (version, joint_name, position, lower_bound, upper_bound)
SELECT 0, joint_name, 0.0, lower_bound, upper_bound FROM robot_joint
WHERE NOT EXISTS (SELECT 1 FROM scene_joint WHERE version = 0);
