-- 机器人模型与初始场景种子数据（合成数据，仅用于本地演示与测试）。

INSERT INTO scene_meta (id, version) SELECT 0, 0 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM scene_meta WHERE id = 0);

INSERT INTO robot_link (name) SELECT 'base_link' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM robot_link WHERE name = 'base_link');
INSERT INTO robot_link (name) SELECT 'link_1' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM robot_link WHERE name = 'link_1');
INSERT INTO robot_link (name) SELECT 'link_2' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM robot_link WHERE name = 'link_2');
INSERT INTO robot_link (name) SELECT 'link_3' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM robot_link WHERE name = 'link_3');
INSERT INTO robot_link (name) SELECT 'tool0' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM robot_link WHERE name = 'tool0');

INSERT INTO scene_frame (name) SELECT 'world' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM scene_frame WHERE name = 'world');
INSERT INTO scene_frame (name) SELECT 'base_link' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM scene_frame WHERE name = 'base_link');
INSERT INTO scene_frame (name) SELECT 'link_1' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM scene_frame WHERE name = 'link_1');
INSERT INTO scene_frame (name) SELECT 'link_2' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM scene_frame WHERE name = 'link_2');
INSERT INTO scene_frame (name) SELECT 'link_3' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM scene_frame WHERE name = 'link_3');
INSERT INTO scene_frame (name) SELECT 'tool0' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM scene_frame WHERE name = 'tool0');

INSERT INTO robot_joint (name, position, min_limit, max_limit)
SELECT 'joint_1', 0.0, -3.141592653589793, 3.141592653589793 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM robot_joint WHERE name = 'joint_1');
INSERT INTO robot_joint (name, position, min_limit, max_limit)
SELECT 'joint_2', 0.0, -1.5707963267948966, 1.5707963267948966 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM robot_joint WHERE name = 'joint_2');
INSERT INTO robot_joint (name, position, min_limit, max_limit)
SELECT 'joint_3', 0.0, -3.141592653589793, 3.141592653589793 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM robot_joint WHERE name = 'joint_3');
