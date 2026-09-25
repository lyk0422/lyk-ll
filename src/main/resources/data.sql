-- 合成种子数据：检验类型与两艘示例船舶，仅存于 JVM 内存库。

MERGE INTO survey_type (code, name, extendable) KEY(code) VALUES
  ('ANNUAL', '年度检验', 1),
  ('INTERMEDIATE', '中间检验', 0),
  ('SPECIAL', '特别检验', 0);

MERGE INTO ship (imo, name, anniversary_date) KEY(imo) VALUES
  ('IMO9000001', '远航之星', DATE '2000-06-15'),
  ('IMO9000002', '蓝海先锋', DATE '2000-11-30');
