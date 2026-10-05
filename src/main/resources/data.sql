-- 合成演示数据（仅合成数据）：已故成员 M-DEMO-01，账户留存两笔未冻结缴费（单位：分）。
-- 该成员无进行中分期计划，继承金额=账户余额=120000+80000=200000 分。

INSERT INTO member (member_no, name, hire_date, status)
SELECT 'M-DEMO-01', '演示成员甲', DATE '2010-06-15', 'DECEASED'
WHERE NOT EXISTS (SELECT 1 FROM member WHERE member_no = 'M-DEMO-01');

INSERT INTO contribution (member_id, contribution_type, amount_cents, contribution_date, period)
SELECT id, 'PERSONAL', 120000, DATE '2026-01-10', '2026-01' FROM member
WHERE member_no = 'M-DEMO-01'
  AND NOT EXISTS (SELECT 1 FROM contribution c
                  WHERE c.member_id = member.id AND c.period = '2026-01');

INSERT INTO contribution (member_id, contribution_type, amount_cents, contribution_date, period)
SELECT id, 'ENTERPRISE', 80000, DATE '2026-02-10', '2026-02' FROM member
WHERE member_no = 'M-DEMO-01'
  AND NOT EXISTS (SELECT 1 FROM contribution c
                  WHERE c.member_id = member.id AND c.period = '2026-02');
