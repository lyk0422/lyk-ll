-- 合成种子数据：仅嵌入式 H2 下由 Spring SQL 初始化自动执行（spring.sql.init.mode=embedded）。
-- 使用 H2 的 MERGE ... KEY 保证重复启动幂等；全部为合成数据。

MERGE INTO diver (diver_code, name, created_at) KEY(diver_code) VALUES
    ('D001', '阿海', CURRENT_TIMESTAMP),
    ('D002', '阿波', CURRENT_TIMESTAMP),
    ('D003', '阿潮', CURRENT_TIMESTAMP),
    ('D004', '阿汐', CURRENT_TIMESTAMP);

-- 合成免减压极限表（米 -> 分钟，恰等于极限合法）
MERGE INTO deco_table_entry (depth_m, ndl_minutes) KEY(depth_m) VALUES
    (12, 120),
    (18, 50),
    (24, 30),
    (30, 15),
    (40, 9);
