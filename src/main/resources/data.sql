-- 初始化单行日志状态（幂等）。
MERGE INTO ct_log_state KEY(id) VALUES (0, 0);
