-- 本地启动合成种子数据：两条退税率文库版本（仅 JVM 内存，重启清空）。
MERGE INTO rate_library (hs_code, effective_date, rate, created_at)
KEY (hs_code, effective_date)
VALUES ('9403609900', DATE '2026-01-01', 0.130000, CURRENT_TIMESTAMP);
MERGE INTO rate_library (hs_code, effective_date, rate, created_at)
KEY (hs_code, effective_date)
VALUES ('9403609900', DATE '2026-07-01', 0.100000, CURRENT_TIMESTAMP);
