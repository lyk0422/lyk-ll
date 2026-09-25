-- 合成历史执行率数据（H2 MERGE 语法，重复启动幂等；仅用于本地演示与联调）
-- 阈值口径：执行率 = executed / planned，>= 80% 保留原时刻，79.9% 及以下进入公共池。

MERGE INTO slot_history KEY(airline, flight_no, slot_time) VALUES
    (1, 'CA', 'CA1234', '08:30', 9, 10),
    (2, 'CA', 'CA1235', '09:00', 8, 10),
    (3, 'MU', 'MU2001', '10:15', 7, 10),
    (4, 'CZ', 'CZ3001', '11:05', 799, 1000);

-- 发布控制单行：NULL 表示本期尚未发布
MERGE INTO slot_publication KEY(id) VALUES (1, NULL);
