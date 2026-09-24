-- 合成种子数据：一个试验、两个中心、三名揭盲授权人。全部为合成数据。
INSERT INTO trial (code, name, treat_code, control_code)
SELECT 'TRIAL-001', '合成双盲随机对照试验', 'A', 'B'
WHERE NOT EXISTS (SELECT 1 FROM trial WHERE code = 'TRIAL-001');

INSERT INTO center (code, name)
SELECT 'C01', '合成中心一'
WHERE NOT EXISTS (SELECT 1 FROM center WHERE code = 'C01');

INSERT INTO center (code, name)
SELECT 'C02', '合成中心二'
WHERE NOT EXISTS (SELECT 1 FROM center WHERE code = 'C02');

INSERT INTO authorized_unblinder (trial_id, person_id, display_name)
SELECT t.id, 'PI-001', '合成授权人甲' FROM trial t
WHERE t.code = 'TRIAL-001'
  AND NOT EXISTS (SELECT 1 FROM authorized_unblinder u WHERE u.trial_id = t.id AND u.person_id = 'PI-001');

INSERT INTO authorized_unblinder (trial_id, person_id, display_name)
SELECT t.id, 'PI-002', '合成授权人乙' FROM trial t
WHERE t.code = 'TRIAL-001'
  AND NOT EXISTS (SELECT 1 FROM authorized_unblinder u WHERE u.trial_id = t.id AND u.person_id = 'PI-002');

INSERT INTO authorized_unblinder (trial_id, person_id, display_name)
SELECT t.id, 'DM-001', '合成授权人丙' FROM trial t
WHERE t.code = 'TRIAL-001'
  AND NOT EXISTS (SELECT 1 FROM authorized_unblinder u WHERE u.trial_id = t.id AND u.person_id = 'DM-001');
