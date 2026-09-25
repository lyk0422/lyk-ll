-- 合成演示数据：1 个换热站、4 个单元、每单元 1 个阀门（初始开度 50%）
-- 时间字段为 UTC；仅用于本地启动演示，单元测试自行清理并重置数据。
-- 使用 MERGE 保证多上下文/重复初始化时幂等。

MERGE INTO heat_station (id, code, name) KEY(id) VALUES
    (1, 'ST-EAST-01', '城东一号换热站');

MERGE INTO heating_unit (id, station_id, code, name) KEY(id) VALUES
    (1, 1, 'U1', '一号单元'),
    (2, 1, 'U2', '二号单元'),
    (3, 1, 'U3', '三号单元'),
    (4, 1, 'U4', '四号单元');

MERGE INTO valve (id, unit_id, opening_percent, updated_at) KEY(id) VALUES
    (1, 1, 50.00, TIMESTAMP '2026-01-01 00:00:00'),
    (2, 2, 50.00, TIMESTAMP '2026-01-01 00:00:00'),
    (3, 3, 50.00, TIMESTAMP '2026-01-01 00:00:00'),
    (4, 4, 50.00, TIMESTAMP '2026-01-01 00:00:00');
