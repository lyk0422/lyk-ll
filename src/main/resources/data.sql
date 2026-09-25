-- 合成演示数据：仅用于本地启动后的手工体验，测试不依赖本文件
-- 使用 INSERT IGNORE 保证多上下文/重复初始化时幂等
-- 13800000001：无欠费、无在网约定、上次携转于 2026-01-01（资格满足）
INSERT IGNORE INTO subscriber (msisdn, current_operator, arrears_amount, contract_end_date, last_port_time)
VALUES ('13800000001', 'OP_A', 0.00, NULL, '2026-01-01 00:00:00');
-- 13800000002：存在欠费（资格不满足）
INSERT IGNORE INTO subscriber (msisdn, current_operator, arrears_amount, contract_end_date, last_port_time)
VALUES ('13800000002', 'OP_A', 12.50, NULL, NULL);
-- 13800000003：在网约定期限至 2027-01-01（资格不满足）
INSERT IGNORE INTO subscriber (msisdn, current_operator, arrears_amount, contract_end_date, last_port_time)
VALUES ('13800000003', 'OP_A', 0.00, '2027-01-01', NULL);
-- 13800000004：上次携转于 2026-08-01，距演示日常态不足 120 日（资格不满足）
INSERT IGNORE INTO subscriber (msisdn, current_operator, arrears_amount, contract_end_date, last_port_time)
VALUES ('13800000004', 'OP_A', 0.00, NULL, '2026-08-01 00:00:00');
