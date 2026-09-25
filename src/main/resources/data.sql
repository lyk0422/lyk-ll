-- 合成参考数据：船舶、开放航次、港口（岸线参考点）、特殊区域多边形。
-- 全部为虚构数据，仅用于本地运行与测试，JVM 退出后随内存库消失。

MERGE INTO ship (ship_id, ship_name, created_at) KEY (ship_id)
VALUES ('SHIP-001', '海峰轮（合成）', '2026-01-01 00:00:00');

MERGE INTO voyage (ship_id, voyage_no, status, closed_at, created_at) KEY (ship_id, voyage_no)
VALUES ('SHIP-001', 'V-2026-01', 'OPEN', NULL, '2026-01-01 00:00:00');

-- 合成岸线参考点（兼作港口）；垃圾排放“距最近陆地”取到这些点的大圆最小距离
MERGE INTO port (port_code, port_name, latitude, longitude) KEY (port_code)
VALUES ('P-SYN-01', '合成东港', 30.000000, 122.000000);
MERGE INTO port (port_code, port_name, latitude, longitude) KEY (port_code)
VALUES ('P-SYN-02', '合成南港', 25.000000, 119.000000);

-- 合成特殊区域：纬度 20~30、经度 110~120 的矩形；边界线视为区域内
MERGE INTO special_area (area_id, area_code, area_name) KEY (area_id)
VALUES (1, 'SA-SYN-01', '合成特殊排放控制区');

MERGE INTO special_area_vertex (area_id, seq, latitude, longitude) KEY (area_id, seq) VALUES (1, 1, 20.000000, 110.000000);
MERGE INTO special_area_vertex (area_id, seq, latitude, longitude) KEY (area_id, seq) VALUES (1, 2, 30.000000, 110.000000);
MERGE INTO special_area_vertex (area_id, seq, latitude, longitude) KEY (area_id, seq) VALUES (1, 3, 30.000000, 120.000000);
MERGE INTO special_area_vertex (area_id, seq, latitude, longitude) KEY (area_id, seq) VALUES (1, 4, 20.000000, 120.000000);
