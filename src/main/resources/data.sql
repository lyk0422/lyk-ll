-- 合成初始化数据：泊位、引航员、每日允许靠泊时段（UTC），仅用于本地运行与演示。

INSERT INTO berth (name, depth_limit) SELECT 'BERTH-1', 12.50 WHERE NOT EXISTS (SELECT 1 FROM berth);
INSERT INTO berth (name, depth_limit) SELECT 'BERTH-2', 8.00 WHERE NOT EXISTS (SELECT 1 FROM berth WHERE name = 'BERTH-2');

INSERT INTO pilot (name) SELECT 'PILOT-A' WHERE NOT EXISTS (SELECT 1 FROM pilot WHERE name = 'PILOT-A');
INSERT INTO pilot (name) SELECT 'PILOT-B' WHERE NOT EXISTS (SELECT 1 FROM pilot WHERE name = 'PILOT-B');
INSERT INTO pilot (name) SELECT 'PILOT-C' WHERE NOT EXISTS (SELECT 1 FROM pilot WHERE name = 'PILOT-C');

-- 每日允许靠泊时段：2026-09-24 至 2026-09-27，每日 UTC 00:00-12:00 与 13:00-23:00 两段。
INSERT INTO allowed_window (start_time, end_time)
SELECT '2026-09-24 00:00:00', '2026-09-24 12:00:00' WHERE NOT EXISTS (SELECT 1 FROM allowed_window);
INSERT INTO allowed_window (start_time, end_time) SELECT '2026-09-24 13:00:00', '2026-09-24 23:00:00' WHERE NOT EXISTS (SELECT 1 FROM allowed_window WHERE start_time = '2026-09-24 13:00:00');
INSERT INTO allowed_window (start_time, end_time) SELECT '2026-09-25 00:00:00', '2026-09-25 12:00:00' WHERE NOT EXISTS (SELECT 1 FROM allowed_window WHERE start_time = '2026-09-25 00:00:00');
INSERT INTO allowed_window (start_time, end_time) SELECT '2026-09-25 13:00:00', '2026-09-25 23:00:00' WHERE NOT EXISTS (SELECT 1 FROM allowed_window WHERE start_time = '2026-09-25 13:00:00');
INSERT INTO allowed_window (start_time, end_time) SELECT '2026-09-26 00:00:00', '2026-09-26 12:00:00' WHERE NOT EXISTS (SELECT 1 FROM allowed_window WHERE start_time = '2026-09-26 00:00:00');
INSERT INTO allowed_window (start_time, end_time) SELECT '2026-09-26 13:00:00', '2026-09-26 23:00:00' WHERE NOT EXISTS (SELECT 1 FROM allowed_window WHERE start_time = '2026-09-26 13:00:00');
INSERT INTO allowed_window (start_time, end_time) SELECT '2026-09-27 00:00:00', '2026-09-27 12:00:00' WHERE NOT EXISTS (SELECT 1 FROM allowed_window WHERE start_time = '2026-09-27 00:00:00');
INSERT INTO allowed_window (start_time, end_time) SELECT '2026-09-27 13:00:00', '2026-09-27 23:00:00' WHERE NOT EXISTS (SELECT 1 FROM allowed_window WHERE start_time = '2026-09-27 13:00:00');
