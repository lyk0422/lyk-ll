-- 合成初始数据：1 个矿区、2 个爆区及监测测点，仅供本地运行与演示。
-- 使用 MERGE 保证重复初始化（如多 Spring 上下文共享内存库）幂等。
MERGE INTO mine (id, name, velocity_threshold, frozen) KEY(id) VALUES
    (1, '青石矿区', 1.000, FALSE);

MERGE INTO blast_zone (id, mine_id, name, max_charge_per_delay) KEY(id) VALUES
    (1, 1, '东一爆区', 120.000),
    (2, 1, '西二爆区', 80.000);

MERGE INTO monitor_point (zone_id, point_code) KEY(zone_id, point_code) VALUES
    (1, 'P1'),
    (1, 'P2'),
    (2, 'P1');
