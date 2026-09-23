-- MoveIt 2 Planning Scene 本地服务建表脚本（H2 内存库，MODE=MySQL）
-- 关节模型定义，来自机器人 URDF/SRDF，静态不变；单位：弧度，时区不涉及。
CREATE TABLE IF NOT EXISTS ps_joint (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    joint_name VARCHAR(128) NOT NULL UNIQUE,
    lower_limit DOUBLE NOT NULL,
    upper_limit DOUBLE NOT NULL
);
COMMENT ON COLUMN ps_joint.joint_name IS '关节唯一名称';
COMMENT ON COLUMN ps_joint.lower_limit IS '关节位置下限（弧度）';
COMMENT ON COLUMN ps_joint.upper_limit IS '关节位置上限（弧度）';

-- 机器人 Link 模型定义，附着物与坐标系引用的合法性来源。
CREATE TABLE IF NOT EXISTS ps_link (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    link_name VARCHAR(128) NOT NULL UNIQUE
);
COMMENT ON COLUMN ps_link.link_name IS '机器人 Link 唯一名称';

-- 场景单例元数据：当前单调递增版本号与机器人基座在 world 下的姿态。
CREATE TABLE IF NOT EXISTS scene_meta (
    id INT PRIMARY KEY,
    current_version BIGINT NOT NULL,
    pose_x DOUBLE NOT NULL,
    pose_y DOUBLE NOT NULL,
    pose_z DOUBLE NOT NULL,
    pose_qx DOUBLE NOT NULL,
    pose_qy DOUBLE NOT NULL,
    pose_qz DOUBLE NOT NULL,
    pose_qw DOUBLE NOT NULL
);
COMMENT ON COLUMN scene_meta.current_version IS '场景当前最新版本号，从 0 单调递增';
COMMENT ON COLUMN scene_meta.pose_x IS '机器人基座位置 x（米，world 坐标系）';

-- 当前各关节位置（弧度）；关节集合由 ps_joint 固定。
CREATE TABLE IF NOT EXISTS joint_state (
    joint_name VARCHAR(128) NOT NULL PRIMARY KEY,
    position DOUBLE NOT NULL,
    CONSTRAINT fk_joint_state_model FOREIGN KEY (joint_name) REFERENCES ps_joint (joint_name)
);
COMMENT ON COLUMN joint_state.position IS '关节当前位置（弧度）';

-- 当前碰撞对象（无论在世界中还是被附着均保留一行）。
CREATE TABLE IF NOT EXISTS collision_object (
    object_id VARCHAR(128) NOT NULL PRIMARY KEY,
    frame_id VARCHAR(128),
    payload CLOB,
    updated_version BIGINT NOT NULL
);
COMMENT ON COLUMN collision_object.object_id IS '碰撞对象唯一 ID';
COMMENT ON COLUMN collision_object.frame_id IS '对象位于世界中时的参考坐标系（Link 名）；附着期间为 NULL';
COMMENT ON COLUMN collision_object.payload IS '对象几何等属性的 JSON 表示，无几何时为 NULL';
COMMENT ON COLUMN collision_object.updated_version IS '该对象最后一次变化对应的版本号';

-- 附着物引用表：存在一行表示对象被附着到机器人 Link；与世界集合互斥。
CREATE TABLE IF NOT EXISTS attachment (
    object_id VARCHAR(128) NOT NULL PRIMARY KEY,
    link_name VARCHAR(128) NOT NULL,
    CONSTRAINT fk_attachment_object FOREIGN KEY (object_id) REFERENCES collision_object (object_id),
    CONSTRAINT fk_attachment_link FOREIGN KEY (link_name) REFERENCES ps_link (link_name)
);
COMMENT ON COLUMN attachment.object_id IS '被附着的碰撞对象 ID，引用 collision_object.object_id';
COMMENT ON COLUMN attachment.link_name IS '附着目标机器人 Link 名称';

-- 每个已提交版本一行，request_id 唯一以支撑幂等。
CREATE TABLE IF NOT EXISTS scene_version (
    version BIGINT NOT NULL PRIMARY KEY,
    request_id VARCHAR(128) NOT NULL UNIQUE,
    created_at BIGINT NOT NULL,
    pose_x DOUBLE NOT NULL,
    pose_y DOUBLE NOT NULL,
    pose_z DOUBLE NOT NULL,
    pose_qx DOUBLE NOT NULL,
    pose_qy DOUBLE NOT NULL,
    pose_qz DOUBLE NOT NULL,
    pose_qw DOUBLE NOT NULL
);
COMMENT ON COLUMN scene_version.version IS '版本号，0 为初始场景';
COMMENT ON COLUMN scene_version.request_id IS '产生该版本的请求 ID，全局唯一';
COMMENT ON COLUMN scene_version.created_at IS '提交时间（Unix 毫秒，UTC）';

-- 幂等请求台账：同 requestId 同参数返回原结果，异参数冲突。
CREATE TABLE IF NOT EXISTS request_log (
    request_id VARCHAR(128) NOT NULL PRIMARY KEY,
    request_hash CHAR(64) NOT NULL,
    version BIGINT NOT NULL,
    created_at BIGINT NOT NULL
);
COMMENT ON COLUMN request_log.request_hash IS '请求业务参数（不含 requestId/expectedVersion）的 SHA-256';
COMMENT ON COLUMN request_log.version IS '该请求首次成功提交产生的版本号';

-- 关节状态的每版本全量不可变快照。
CREATE TABLE IF NOT EXISTS joint_state_history (
    version BIGINT NOT NULL,
    joint_name VARCHAR(128) NOT NULL,
    position DOUBLE NOT NULL,
    PRIMARY KEY (version, joint_name)
);
COMMENT ON COLUMN joint_state_history.version IS '快照所属版本号';

-- 碰撞对象的每版本全量不可变快照。
CREATE TABLE IF NOT EXISTS object_history (
    version BIGINT NOT NULL,
    object_id VARCHAR(128) NOT NULL,
    frame_id VARCHAR(128),
    payload CLOB,
    PRIMARY KEY (version, object_id)
);

-- 附着物的每版本全量不可变快照。
CREATE TABLE IF NOT EXISTS attachment_history (
    version BIGINT NOT NULL,
    object_id VARCHAR(128) NOT NULL,
    link_name VARCHAR(128) NOT NULL,
    PRIMARY KEY (version, object_id)
);
