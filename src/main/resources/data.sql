-- 合成演示数据：起运仓 WH-A 与目的仓 WH-B 的初始库存，仅用于本地演示与测试基线
INSERT INTO warehouse_stock (warehouse_code, tariff_code, quantity)
SELECT 'WH-A', '0901.21', 1000 WHERE NOT EXISTS (
    SELECT 1 FROM warehouse_stock WHERE warehouse_code = 'WH-A' AND tariff_code = '0901.21');
INSERT INTO warehouse_stock (warehouse_code, tariff_code, quantity)
SELECT 'WH-A', '8542.31', 500 WHERE NOT EXISTS (
    SELECT 1 FROM warehouse_stock WHERE warehouse_code = 'WH-A' AND tariff_code = '8542.31');
INSERT INTO warehouse_stock (warehouse_code, tariff_code, quantity)
SELECT 'WH-B', '0901.21', 800 WHERE NOT EXISTS (
    SELECT 1 FROM warehouse_stock WHERE warehouse_code = 'WH-B' AND tariff_code = '0901.21');
