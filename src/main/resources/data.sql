-- 合成基础数据：1 座水库、3 孔闸门及 1 条水情读数；仅存于 JVM 内存库
-- 时间戳为 UTC 毫秒：2026-09-25T00:00:00Z = 1790294400000
MERGE INTO reservoir (id, code, name, created_at) KEY (id)
VALUES (1, 'RSV-01', '合成一号水库', 1790294400000);

MERGE INTO gate (id, code, name, reservoir_id, max_opening) KEY (id)
VALUES (1, 'G1', '1号溢洪道闸门', 1, 100.000);
MERGE INTO gate (id, code, name, reservoir_id, max_opening) KEY (id)
VALUES (2, 'G2', '2号溢洪道闸门', 1, 100.000);
MERGE INTO gate (id, code, name, reservoir_id, max_opening) KEY (id)
VALUES (3, 'G3', '3号泄洪洞闸门', 1,  80.000);

-- 读数时刻 2026-09-25T00:05:00Z
MERGE INTO water_reading (id, reservoir_id, level_m, inflow_m3s, read_at) KEY (id)
VALUES (1, 1, 145.250, 320.500, 1790294700000);
