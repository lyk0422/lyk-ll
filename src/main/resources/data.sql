-- 本地演示用合成数据（仅 JVM 生命周期内有效）；INSERT IGNORE 保证重复初始化幂等
INSERT IGNORE INTO tower_crane (id, name, rated_load_tonnes) VALUES
    ('CRANE-A', '塔机A', 8.000),
    ('CRANE-B', '塔机B', 8.000),
    ('CRANE-C', '塔机C', 12.000);

INSERT IGNORE INTO overlap_zone (id, name, center_x_m, center_y_m, radius_m) VALUES
    ('ZONE-1', 'A/B回转重叠区', 0.0, 0.0, 10.0),
    ('ZONE-2', 'B/C回转重叠区', 50.0, 0.0, 10.0);
