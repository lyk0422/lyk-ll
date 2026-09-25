-- 合成演示数据：两名持证人员；电梯通过 API 创建。
INSERT INTO staff (name, cert_no, created_at)
SELECT '张三', 'CERT-A001', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM staff WHERE cert_no = 'CERT-A001');

INSERT INTO staff (name, cert_no, created_at)
SELECT '李四', 'CERT-A002', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM staff WHERE cert_no = 'CERT-A002');
