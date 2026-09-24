-- 合成商户与费率数据（仅用于本地运行与测试）
INSERT INTO merchant (merchant_no, merchant_name, fee_rate_bp)
SELECT 'M0001', '合成商户一', 30
WHERE NOT EXISTS (SELECT 1 FROM merchant WHERE merchant_no = 'M0001');
INSERT INTO merchant (merchant_no, merchant_name, fee_rate_bp)
SELECT 'M0002', '合成商户二', 50
WHERE NOT EXISTS (SELECT 1 FROM merchant WHERE merchant_no = 'M0002');
INSERT INTO merchant (merchant_no, merchant_name, fee_rate_bp)
SELECT 'M0003', '合成商户三', 0
WHERE NOT EXISTS (SELECT 1 FROM merchant WHERE merchant_no = 'M0003');
