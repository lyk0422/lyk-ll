-- 演示用合成 Schema 数据（仅嵌入式本地库自动初始化，全部为合成数据）

-- 节点 /camera（vision 参数组）
MERGE INTO param_schema(node, group_name, name, type, required_flag, dynamic_flag, default_value, depends_on)
KEY(node, name) VALUES ('/camera', 'vision', 'frame_rate', 'INTEGER', TRUE, TRUE, '30', 'frame_rate <= max_fps and frame_rate >= 1');
MERGE INTO param_schema(node, group_name, name, type, required_flag, dynamic_flag, default_value, depends_on)
KEY(node, name) VALUES ('/camera', 'vision', 'max_fps', 'INTEGER', TRUE, TRUE, '60', NULL);
MERGE INTO param_schema(node, group_name, name, type, required_flag, dynamic_flag, default_value, depends_on)
KEY(node, name) VALUES ('/camera', 'vision', 'resolution', 'STRING', TRUE, TRUE, '"1080p"', NULL);
-- 静态参数：热更新禁止修改，只能创建待重启配置
MERGE INTO param_schema(node, group_name, name, type, required_flag, dynamic_flag, default_value, depends_on)
KEY(node, name) VALUES ('/camera', 'vision', 'camera_index', 'INTEGER', TRUE, FALSE, '0', NULL);

-- 节点 /planner（navigation 参数组）
MERGE INTO param_schema(node, group_name, name, type, required_flag, dynamic_flag, default_value, depends_on)
KEY(node, name) VALUES ('/planner', 'navigation', 'max_speed', 'DOUBLE', TRUE, TRUE, '1.5', 'max_speed <= 3.0');
MERGE INTO param_schema(node, group_name, name, type, required_flag, dynamic_flag, default_value, depends_on)
KEY(node, name) VALUES ('/planner', 'navigation', 'min_obstacle_dist', 'DOUBLE', TRUE, TRUE, '0.3', 'min_obstacle_dist >= 0.1');
MERGE INTO param_schema(node, group_name, name, type, required_flag, dynamic_flag, default_value, depends_on)
KEY(node, name) VALUES ('/planner', 'navigation', 'use_sim_time', 'BOOLEAN', TRUE, FALSE, 'false', NULL);
