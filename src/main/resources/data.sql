-- 合成初始化数据：轴型限值、检测站、车辆档案。全部为合成数据，不含真实信息。

MERGE INTO axle_limit (axle_type, limit_kg) KEY(axle_type) VALUES
    ('AXLE_2', 18000.00),
    ('AXLE_3', 25000.00),
    ('AXLE_4', 31000.00),
    ('AXLE_6', 49000.00);

MERGE INTO station (station_code, station_name) KEY(station_code) VALUES
    ('ST001', '城东治超检测站'),
    ('ST002', '西环治超检测站');

MERGE INTO vehicle (plate_no, axle_type) KEY(plate_no) VALUES
    ('沪A10001', 'AXLE_4'),
    ('沪B20002', 'AXLE_6'),
    ('苏C30003', 'AXLE_2');
