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

-- 非现场称重设备状态记录（合成数据）：DEV-L02 校准已过期，用于验证有效期外数据转人工待补。
MERGE INTO offsite_device (device_id, lane_no, status, calibrated_from, calibrated_until, recorded_at) KEY(device_id) VALUES
    ('DEV-L01', 'L01', 'NORMAL', '2026-01-01 00:00:00', '2027-01-01 00:00:00', '2026-01-01 00:00:00'),
    ('DEV-L02', 'L02', 'NORMAL', '2025-01-01 00:00:00', '2026-01-01 00:00:00', '2025-01-01 00:00:00'),
    ('DEV-L03', 'L03', 'NORMAL', '2026-01-01 00:00:00', '2027-01-01 00:00:00', '2026-01-01 00:00:00');
