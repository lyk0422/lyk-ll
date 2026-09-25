-- 合成演示数据:示范尾矿库及库容曲线,仅供本地启动后手工调用示例,不含真实数据。
-- 库容曲线:水位180m->100万m3,190m->160万m3,200m(允许最高调洪水位)->240万m3。
-- 即水位190m时可用调洪库容=240万-160万=80万m3。

INSERT INTO tailings_pond (id, name, max_flood_level, downstream_safe_discharge, dry_beach_min, phreatic_line_min)
VALUES (1, '示范尾矿库(合成)', 200.000, 100.000, 50.000, 2.000);

INSERT INTO storage_curve (pond_id, water_level, capacity) VALUES
    (1, 180.000, 1000000.000),
    (1, 190.000, 1600000.000),
    (1, 200.000, 2400000.000);
