-- 合成种子数据：运营商、车辆、充电桩（仅本地/测试 H2 使用）。
-- 费率单位：元/kWh。使用 MERGE 保证脚本可重复执行（多 Spring 上下文共享内存库时幂等）。
MERGE INTO ev_operator (operator_code, operator_name, tariff_rate, created_at)
    KEY(operator_code) VALUES
    ('OP-A', '甲运营商', 1.2000, CURRENT_TIMESTAMP(3)),
    ('OP-B', '乙运营商', 0.8500, CURRENT_TIMESTAMP(3)),
    ('OP-C', '丙运营商', 1.5000, CURRENT_TIMESTAMP(3));

MERGE INTO ev_vehicle (cert_fingerprint, vin, plate_no, home_operator, created_at)
    KEY(cert_fingerprint) VALUES
    ('FP-AAA111-CERT-SHA256-0000000000000000000000000001', 'LSJA0000000000001', '京A00001', 'OP-A', CURRENT_TIMESTAMP(3)),
    ('FP-BBB222-CERT-SHA256-0000000000000000000000000002', 'LSJB0000000000002', '沪B00002', 'OP-B', CURRENT_TIMESTAMP(3)),
    ('FP-CCC333-CERT-SHA256-0000000000000000000000000003', 'LSJC0000000000003', '粤C00003', 'OP-C', CURRENT_TIMESTAMP(3));

MERGE INTO charge_point (point_id, point_name, operator_code, created_at)
    KEY(point_id) VALUES
    ('CP-0001', '一号直流快充桩', 'OP-B', CURRENT_TIMESTAMP(3)),
    ('CP-0002', '二号交流慢充桩', 'OP-A', CURRENT_TIMESTAMP(3)),
    ('CP-0003', '三号超充桩',     'OP-C', CURRENT_TIMESTAMP(3));
