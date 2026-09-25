-- 合成基础数据：仅嵌入式 H2 启动时自动执行，全部为虚构数据。
-- 使用 MERGE 保证重复初始化（多应用上下文/测试）时幂等。
-- 塔机：C-101 额定 5000kg；C-102 额定 3000kg。
MERGE INTO tower_crane (crane_code, rated_load_kg, created_at)
KEY (crane_code)
VALUES ('C-101', 5000.00, TIMESTAMP '2026-09-25 00:00:00');
MERGE INTO tower_crane (crane_code, rated_load_kg, created_at)
KEY (crane_code)
VALUES ('C-102', 3000.00, TIMESTAMP '2026-09-25 00:00:00');

-- 重叠区：Z-A1 为两台塔机回转范围重叠区域。
MERGE INTO overlap_zone (zone_code, description, created_at)
KEY (zone_code)
VALUES ('Z-A1', 'C-101 与 C-102 回转重叠区（合成数据）', TIMESTAMP '2026-09-25 00:00:00');
