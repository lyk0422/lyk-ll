-- 合成种子数据（可重复执行；历史执行率以上一航季 S25 为口径）

MERGE INTO slot_season (season, frozen, frozen_at, version, created_at)
KEY (season)
VALUES ('S26', 0, NULL, 0, 0);

-- CA1001 PEK 08:00 执行率恰好 80.0% -> 达标保留
MERGE INTO historical_performance (prev_season, airline, flight_no, airport, slot_time, execution_rate)
KEY (prev_season, airline, flight_no, airport, slot_time)
VALUES ('S25', 'CA', '1001', 'PEK', '08:00', 80.0);

-- CA1002 PEK 08:30 执行率 79.9% -> 未达标进入公共池
MERGE INTO historical_performance (prev_season, airline, flight_no, airport, slot_time, execution_rate)
KEY (prev_season, airline, flight_no, airport, slot_time)
VALUES ('S25', 'CA', '1002', 'PEK', '08:30', 79.9);

-- MU2001 PKX 09:15 执行率 92.5% -> 达标保留
MERGE INTO historical_performance (prev_season, airline, flight_no, airport, slot_time, execution_rate)
KEY (prev_season, airline, flight_no, airport, slot_time)
VALUES ('S25', 'MU', '2001', 'PKX', '09:15', 92.5);

-- CZ3001 PEK 10:00 执行率 75.0% -> 未达标进入公共池
MERGE INTO historical_performance (prev_season, airline, flight_no, airport, slot_time, execution_rate)
KEY (prev_season, airline, flight_no, airport, slot_time)
VALUES ('S25', 'CZ', '3001', 'PEK', '10:00', 75.0);
