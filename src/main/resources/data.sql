-- 合成机器人模型与初始场景数据（H2 MERGE，重复初始化不产生重复行）。
MERGE INTO ps_joint (joint_name, lower_limit, upper_limit) KEY (joint_name) VALUES
  ('joint_1', -3.14159265, 3.14159265),
  ('joint_2', -1.57079633, 1.57079633),
  ('joint_3', -2.35619449, 2.35619449),
  ('joint_4', -3.14159265, 3.14159265),
  ('joint_5', -1.74532925, 1.74532925),
  ('joint_6', -6.28318531, 6.28318531);

MERGE INTO ps_link (link_name) KEY (link_name) VALUES
  ('world'),
  ('base_link'),
  ('link_1'),
  ('link_2'),
  ('link_3'),
  ('link_4'),
  ('link_5'),
  ('link_6'),
  ('tool0');

-- 场景单例：版本 0，机器人姿态为原点单位四元数。
MERGE INTO scene_meta (id, current_version,
  pose_x, pose_y, pose_z, pose_qx, pose_qy, pose_qz, pose_qw)
KEY (id) VALUES
  (1, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0);

-- 初始关节位置全部为 0（弧度）。
MERGE INTO joint_state (joint_name, position) KEY (joint_name) VALUES
  ('joint_1', 0.0),
  ('joint_2', 0.0),
  ('joint_3', 0.0),
  ('joint_4', 0.0),
  ('joint_5', 0.0),
  ('joint_6', 0.0);

-- 版本 0 元数据；request_id 固定标记初始版本，不参与幂等冲突判定。
MERGE INTO scene_version (version, request_id, created_at,
  pose_x, pose_y, pose_z, pose_qx, pose_qy, pose_qz, pose_qw)
KEY (version) VALUES
  (0, '__initial__', 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0);

-- 版本 0 关节快照。
MERGE INTO joint_state_history (version, joint_name, position) KEY (version, joint_name)
SELECT 0, joint_name, position FROM joint_state;
