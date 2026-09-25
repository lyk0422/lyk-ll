-- 本地演示用合成数据：一个风场、四台机组，总额定 7000 kW，当前可用合计 5400 kW。
INSERT INTO farm (id, code, name) VALUES (1, 'FARM-DEMO', '演示风场');
INSERT INTO turbine (farm_id, code, capacity_kw, available_kw) VALUES
    (1, 'WTG-01', 2000, 1800),
    (1, 'WTG-02', 2000, 1500),
    (1, 'WTG-03', 1500, 1200),
    (1, 'WTG-04', 1500, 900);
