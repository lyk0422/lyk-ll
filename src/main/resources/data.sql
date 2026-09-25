-- 合成产线数据（仅本地/测试使用）
INSERT INTO production_line (line_code, line_name)
SELECT 'L1', '一轧线' WHERE NOT EXISTS (SELECT 1 FROM production_line WHERE line_code = 'L1');
INSERT INTO production_line (line_code, line_name)
SELECT 'L2', '二轧线' WHERE NOT EXISTS (SELECT 1 FROM production_line WHERE line_code = 'L2');
