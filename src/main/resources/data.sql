-- 合成初始化数据：三个展示池，传感器初始在线。
MERGE INTO tank (id, code, name, status, display_suspended, auto_disposal_suspended, data_missing,
  consecutive_violations, sensor_status, sensor_last_seen_at, created_at) KEY(id) VALUES
(1, 'TANK-CORAL', '珊瑚礁展示池', 'NORMAL', 0, 0, 0, 0, 'ONLINE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(2, 'TANK-PENGUIN', '极地企鹅池', 'NORMAL', 0, 0, 0, 0, 'ONLINE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(3, 'TANK-DEEPSEA', '深海隧道池', 'NORMAL', 0, 0, 0, 0, 'ONLINE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
