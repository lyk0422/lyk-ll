-- 本地启动合成数据：仅内存库演示用，全部为虚构当事人与信用证。
MERGE INTO lc_credit
    (lc_no, applicant, beneficiary, currency, total_amount, available_amount,
     expiry_date, status, created_at)
KEY (lc_no)
VALUES
    ('LC-DEMO-001', 'ACME TRADING LTD', 'SHENZHEN EXPORT CO', 'USD', 10000.00, 10000.00,
     DATE '2027-12-31', 'ACTIVE', TIMESTAMP '2026-09-18 08:00:00');
