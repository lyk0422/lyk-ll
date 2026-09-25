-- 合成轴型限值数据（单位千克），仅用于本地运行与测试
MERGE INTO axle_limit (axle_type, limit_weight_kg) KEY(axle_type) VALUES
    ('AXLE_2', 18000),
    ('AXLE_3', 25000),
    ('AXLE_4', 31000),
    ('AXLE_5', 43000),
    ('AXLE_6', 49000);
