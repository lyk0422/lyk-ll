-- 合成初始化数据：三个展示池分区，传感器在线，心跳时间为初始化时刻。
INSERT INTO zone_pool (code, name, status, consecutive_violations, sensor_state, last_heartbeat_at, missing_since, updated_at)
SELECT 'ZONE-A', '珊瑚礁展示区', 'NORMAL', 0, 'ONLINE', CURRENT_TIMESTAMP, NULL, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM zone_pool WHERE code = 'ZONE-A');

INSERT INTO zone_pool (code, name, status, consecutive_violations, sensor_state, last_heartbeat_at, missing_since, updated_at)
SELECT 'ZONE-B', '企鹅馆', 'NORMAL', 0, 'ONLINE', CURRENT_TIMESTAMP, NULL, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM zone_pool WHERE code = 'ZONE-B');

INSERT INTO zone_pool (code, name, status, consecutive_violations, sensor_state, last_heartbeat_at, missing_since, updated_at)
SELECT 'ZONE-C', '深海隧道', 'NORMAL', 0, 'ONLINE', CURRENT_TIMESTAMP, NULL, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM zone_pool WHERE code = 'ZONE-C');
