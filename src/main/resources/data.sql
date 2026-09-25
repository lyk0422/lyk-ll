-- 合成初始数据：组分罐与成品规格，仅用于本地演示与开发，非真实化验数据
INSERT INTO component_tank (code, name, octane, sulfur, rvp, stock_kg) VALUES
('T101', '催化汽油组分A', 96.0, 6.0, 52.0, 100000),
('T102', '重整汽油组分B', 88.0, 30.0, 65.0, 80000),
('T103', '烷基化油组分C', 92.0, 10.0, 58.0, 60000);

INSERT INTO product_spec (product_code, octane_max, sulfur_max, rvp_max, octane_margin, sulfur_margin, rvp_margin) VALUES
('P92', 92.5, 15.0, 62.0, 0.5, 2.0, 3.0),
('P95', 95.5, 10.0, 60.0, 0.5, 1.0, 2.0);
