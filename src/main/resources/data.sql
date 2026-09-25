-- 合成演示数据：企业、保税仓与商品库存，仅用于本地运行与测试，不含真实数据。
-- 幂等写法：重复执行（如多上下文共享命名内存库）不产生主键冲突。
INSERT INTO enterprise (id, name, frozen)
    SELECT 'ENT-001', '合成外贸企业甲', 0 WHERE NOT EXISTS (SELECT 1 FROM enterprise WHERE id = 'ENT-001');
INSERT INTO enterprise (id, name, frozen)
    SELECT 'ENT-002', '合成外贸企业乙', 0 WHERE NOT EXISTS (SELECT 1 FROM enterprise WHERE id = 'ENT-002');

INSERT INTO warehouse (id, name)
    SELECT 'WH-A', '合成保税区 A 仓' WHERE NOT EXISTS (SELECT 1 FROM warehouse WHERE id = 'WH-A');
INSERT INTO warehouse (id, name)
    SELECT 'WH-B', '合成保税区 B 仓' WHERE NOT EXISTS (SELECT 1 FROM warehouse WHERE id = 'WH-B');

INSERT INTO inventory (warehouse_id, hs_code, total_qty, frozen_qty)
    SELECT 'WH-A', 'HS-1001', 1000, 0 WHERE NOT EXISTS
        (SELECT 1 FROM inventory WHERE warehouse_id = 'WH-A' AND hs_code = 'HS-1001');
INSERT INTO inventory (warehouse_id, hs_code, total_qty, frozen_qty)
    SELECT 'WH-A', 'HS-1002', 500, 0 WHERE NOT EXISTS
        (SELECT 1 FROM inventory WHERE warehouse_id = 'WH-A' AND hs_code = 'HS-1002');
INSERT INTO inventory (warehouse_id, hs_code, total_qty, frozen_qty)
    SELECT 'WH-B', 'HS-1001', 200, 0 WHERE NOT EXISTS
        (SELECT 1 FROM inventory WHERE warehouse_id = 'WH-B' AND hs_code = 'HS-1001');
