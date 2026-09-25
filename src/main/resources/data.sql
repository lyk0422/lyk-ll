-- 合成 IICL 风格残损代码与修理项价目（仅合成数据，MERGE 保证重复初始化不冲突）。
MERGE INTO repair_item (repair_code, name, unit, unit_cost) KEY (repair_code) VALUES
('RP-PATCH',  '顶板割伤补焊',     '处', 120.00),
('RP-STRAIT', '板件凹陷矫正',     '处', 80.00),
('RP-MEMBER', '横梁矫正更换',     '根', 260.00),
('RP-DOOR',   '门板校正补漆',     '扇', 150.00),
('RP-POST',   '角柱火焰矫正',     '根', 300.00);

MERGE INTO iicl_code (code, description, default_repair_code) KEY (code) VALUES
('IICL-2010', '顶板破孔割伤（长度方向）', 'RP-PATCH'),
('IICL-2020', '侧板凹陷变形',             'RP-STRAIT'),
('IICL-2030', '底横梁弯曲变形',           'RP-MEMBER'),
('IICL-3010', '门板破损变形',             'RP-DOOR'),
('IICL-4010', '端角柱碰撞变形',           'RP-POST');
