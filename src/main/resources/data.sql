-- 合成基础数据：1 个矿区、3 个爆区、每爆区 2~4 个测点，全部为虚构数据
INSERT INTO mine (mine_code, mine_name, time_zone, frozen, version)
VALUES ('MINE-001', '合成示例矿区', 'Asia/Shanghai', FALSE, 0);

INSERT INTO blast_zone (zone_code, mine_code, zone_name, max_charge_per_delay_kg)
VALUES
    ('ZONE-A', 'MINE-001', '一号露天爆区', 500.000),
    ('ZONE-B', 'MINE-001', '二号露天爆区', 300.000),
    ('ZONE-C', 'MINE-001', '三号硐采爆区', 120.000);

INSERT INTO monitoring_point (zone_code, point_code, point_name, velocity_limit_mm_s)
VALUES
    ('ZONE-A', 'P1', '一号爆区近点', 20.000),
    ('ZONE-A', 'P2', '一号爆区远点', 15.000),
    ('ZONE-A', 'P3', '一号爆区侧点', 18.000),
    ('ZONE-A', 'P4', '一号爆区旁点', 10.000),
    ('ZONE-B', 'P1', '二号爆区近点', 20.000),
    ('ZONE-B', 'P2', '二号爆区远点', 15.000),
    ('ZONE-C', 'P1', '三号爆区近点', 10.000),
    ('ZONE-C', 'P2', '三号爆区远点', 8.000);
