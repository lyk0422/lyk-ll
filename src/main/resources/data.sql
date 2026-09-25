-- 合成演示数据：一个开票方授信账户，便于本地启动后直接体验开票/红冲流程。
INSERT INTO credit_account (seller_tax_no, total_limit, blue_unarchived_amount, red_confirmed_amount, version, created_at, updated_at)
SELECT '91310000DEMO00001X', 1000000.00, 0.00, 0.00, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM credit_account WHERE seller_tax_no = '91310000DEMO00001X');
