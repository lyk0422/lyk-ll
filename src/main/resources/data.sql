-- 种子数据：全部为合成演示资源，不包含真实设备信息。
-- joint1：预设位置降级；joint2：零速度降级；joint3：保持降级。

INSERT INTO resource (resource_id, min_position, max_position, max_velocity, max_acceleration,
                      command_ttl_ms, safe_mode, preset_position)
SELECT 'joint1', -180.0, 180.0, 90.0, 360.0, 5000, 'PRESET_POSITION', 0.0
WHERE NOT EXISTS (SELECT 1 FROM resource WHERE resource_id = 'joint1');

INSERT INTO resource (resource_id, min_position, max_position, max_velocity, max_acceleration,
                      command_ttl_ms, safe_mode, preset_position)
SELECT 'joint2', -90.0, 90.0, 45.0, 180.0, 5000, 'ZERO_VELOCITY', NULL
WHERE NOT EXISTS (SELECT 1 FROM resource WHERE resource_id = 'joint2');

INSERT INTO resource (resource_id, min_position, max_position, max_velocity, max_acceleration,
                      command_ttl_ms, safe_mode, preset_position)
SELECT 'joint3', -45.0, 45.0, 30.0, 120.0, 5000, 'HOLD', NULL
WHERE NOT EXISTS (SELECT 1 FROM resource WHERE resource_id = 'joint3');

INSERT INTO estop_state (id, active, updated_at)
SELECT 1, FALSE, 0
WHERE NOT EXISTS (SELECT 1 FROM estop_state WHERE id = 1);
