-- 本地演示用合成数据（仅 H2 本地运行；测试上下文会清空相关表）。
-- 一笔 2027 年底到期、可用额度 10000.00 USD 的信用证。
INSERT INTO lc_account
  (lc_no, applicant, beneficiary, currency, amount_total, amount_available,
   expiry_utc, created_at_utc, updated_at_utc)
SELECT 'LC-SYNTHETIC-0001', 'SYNTHETIC-APPLICANT-CO', 'SYNTHETIC-BENEFICIARY-CO',
       'USD', 10000.00, 10000.00,
       TIMESTAMP '2027-12-31 00:00:00', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
 WHERE NOT EXISTS (SELECT 1 FROM lc_account WHERE lc_no = 'LC-SYNTHETIC-0001');
