-- 合成基础数据：运营商、车辆、充电桩（仅内存库演示与测试使用）
MERGE INTO operator (id, operator_code, operator_name, tariff_receivable, tariff_payable) KEY (id) VALUES
    (1, 'OP-A', '家园充电网', 1.2000, 0.8000),
    (2, 'OP-B', '漫游充电网', 1.3000, 0.9000);

MERGE INTO vehicle (id, cert_fingerprint, home_operator_id, enabled) KEY (id) VALUES
    (1, 'FP-CAR-0001', 1, 1),
    (2, 'FP-CAR-0002', 2, 1),
    (3, 'FP-CAR-DISABLED', 1, 0);

MERGE INTO charging_pile (id, pile_no, operator_id, power_kw, enabled) KEY (id) VALUES
    (1, 'PILE-A-001', 1, 60.00, 1),
    (2, 'PILE-B-001', 2, 120.00, 1);
