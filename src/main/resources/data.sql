-- 合成种子数据：两个开票方授信账户，仅用于本地演示，不含真实主体信息
INSERT INTO credit_account (seller_id, total_limit)
SELECT 'SELLER-001', 1000000.00
WHERE NOT EXISTS (SELECT 1 FROM credit_account WHERE seller_id = 'SELLER-001');

INSERT INTO credit_account (seller_id, total_limit)
SELECT 'SELLER-002', 500000.00
WHERE NOT EXISTS (SELECT 1 FROM credit_account WHERE seller_id = 'SELLER-002');
