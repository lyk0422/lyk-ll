-- 合成基础数据（车辆、危化品类别、路线、管制时段均为合成数据）

-- 危化品类别：通行等级要求
MERGE INTO goods_category (category_code, category_name, required_level) KEY (category_code) VALUES
 ('EXPLOSIVE', '爆炸品', 3),
 ('FLAMMABLE', '易燃液体', 2),
 ('TOXIC',     '毒害品',   1);

-- 路线：R-G3 等级 3；R-G2 等级 2；R-G1 等级 1；R-BAN 禁行；R-N3 夜间干线（等级 3，含夜间禁行）
MERGE INTO route_info (route_code, route_name, pass_level, forbidden) KEY (route_code) VALUES
 ('R-G3',  '化工区高速专用线', 3, 0),
 ('R-G2',  '城郊快速通道',     2, 0),
 ('R-G1',  '普通国道联络线',   1, 0),
 ('R-BAN', '水源保护区禁行线', 1, 1),
 ('R-N3',  '夜间跨区干线',     3, 0);

-- 途经点模板：计划通过时刻 = 计划发运 + 偏移分钟
MERGE INTO route_waypoint_def (route_code, seq, name, offset_minutes) KEY (route_code, seq) VALUES
 ('R-G3', 0, 'G3-起点卡口', 30),
 ('R-G3', 1, 'G3-园区立交', 60),
 ('R-G3', 2, 'G3-隧道前检查点', 90),
 ('R-G3', 3, 'G3-收货厂门', 120),
 ('R-G2', 0, 'G2-起点卡口', 30),
 ('R-G2', 1, 'G2-跨河大桥', 60),
 ('R-G2', 2, 'G2-绕城枢纽', 90),
 ('R-G2', 3, 'G2-收货厂门', 120),
 ('R-G1', 0, 'G1-起点', 30),
 ('R-G1', 1, 'G1-镇道口', 60),
 ('R-G1', 2, 'G1-收货点', 90),
 ('R-BAN', 0, 'BAN-入口', 30),
 ('R-BAN', 1, 'BAN-库区', 60),
 ('R-N3', 0, 'N3-傍晚入口', 60),
 ('R-N3', 1, 'N3-城区外环', 120),
 ('R-N3', 2, 'N3-安全接力站', 180),
 ('R-N3', 3, 'N3-午夜卡口', 240),
 ('R-N3', 4, 'N3-凌晨收货点', 300);

-- 夜间禁行时段（半开区间）：R-N3 在 2026-10-03 00:00~05:00 禁行，恰满 00:00 视为已入禁行
MERGE INTO night_curfew (id, route_code, curfew_start, curfew_end) KEY (id) VALUES
 (1, 'R-N3', TIMESTAMP '2026-10-03 00:00:00', TIMESTAMP '2026-10-03 05:00:00');

-- 管制时段（半开区间）：R-G2 在 2026-10-01 08:00~10:00 管制
MERGE INTO control_period (id, route_code, start_time, end_time) KEY (id) VALUES
 (1, 'R-G2', TIMESTAMP '2026-10-01 08:00:00', TIMESTAMP '2026-10-01 10:00:00');

-- 合成车辆
MERGE INTO vehicle (plate, frozen, frozen_shipment_id, frozen_at) KEY (plate) VALUES
 ('沪A-1001', 0, NULL, NULL),
 ('沪A-1002', 0, NULL, NULL),
 ('沪B-2001', 0, NULL, NULL),
 ('沪C-3001', 0, NULL, NULL),
 ('沪C-3002', 0, NULL, NULL),
 ('沪C-3003', 0, NULL, NULL);
