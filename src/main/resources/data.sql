-- 合成演示数据：1 号站 4 单元（初始开度 50%）；2 号站 2 单元（40%/60%）及预置联调组
-- 时间字段为 UTC；仅用于本地启动演示，单元测试自行清理并重置数据。
-- 使用 MERGE 保证多上下文/重复初始化时幂等。

MERGE INTO heat_station (id, code, name) KEY(id) VALUES
    (1, 'ST-EAST-01', '城东一号换热站'),
    (2, 'ST-EAST-02', '城东二号换热站');

MERGE INTO heating_unit (id, station_id, code, name) KEY(id) VALUES
    (1, 1, 'U1', '一号单元'),
    (2, 1, 'U2', '二号单元'),
    (3, 1, 'U3', '三号单元'),
    (4, 1, 'U4', '四号单元'),
    (5, 2, 'U1', '二号站一号单元'),
    (6, 2, 'U2', '二号站二号单元');

MERGE INTO valve (id, unit_id, opening_percent, updated_at) KEY(id) VALUES
    (1, 1, 50.00, TIMESTAMP '2026-01-01 00:00:00'),
    (2, 2, 50.00, TIMESTAMP '2026-01-01 00:00:00'),
    (3, 3, 50.00, TIMESTAMP '2026-01-01 00:00:00'),
    (4, 4, 50.00, TIMESTAMP '2026-01-01 00:00:00'),
    (5, 5, 40.00, TIMESTAMP '2026-01-01 00:00:00'),
    (6, 6, 60.00, TIMESTAMP '2026-01-01 00:00:00');

-- 联调演示：2 号站两只同权阀（40%/60%）预置联调组，可演示 40/60 → 50/50 耦合联调
MERGE INTO coupled_group (id, station_id, status, version, request_id, fingerprint,
    created_at, dissolved_at) KEY(id) VALUES
    (1, 2, 'ACTIVE', 1, 'demo-seed-coupled-group', 'demo-seed',
     TIMESTAMP '2026-01-01 00:00:00', NULL);

MERGE INTO coupled_group_member (id, group_id, valve_id, weight, valve_version) KEY(id) VALUES
    (1, 1, 5, 1, 0),
    (2, 1, 6, 1, 0);
