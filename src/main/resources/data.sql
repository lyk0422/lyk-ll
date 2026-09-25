-- 本地演示用合成数据（非真实人员/设备）
INSERT INTO elevator (code, name, status, key_supervision, commissioned_day)
VALUES ('EL-001', '1号楼客梯（合成）', 'RUNNING', FALSE, DATE '2026-01-01'),
       ('EL-002', '2号楼货梯（合成）', 'RUNNING', FALSE, DATE '2026-01-01');

INSERT INTO staff (name, cert_no, certified)
VALUES ('合成人员-甲', 'CERT-SYN-001', TRUE),
       ('合成人员-乙', 'CERT-SYN-002', TRUE),
       ('合成人员-丙', 'CERT-SYN-003', FALSE);
