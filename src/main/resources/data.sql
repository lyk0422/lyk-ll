-- 本地启动自动初始化的合成数据：一条有效危废经营许可（Asia/Shanghai 日期口径）。
-- 仅用于演示申报绑定；内存库随 JVM 退出清空，测试使用独立许可编号互不干扰。
MERGE INTO hazardous_license (license_no, holder_name, valid_from, valid_to, revoked, created_at)
KEY (license_no)
VALUES ('LIC-DEMO-001', '示例水泥窑协同处置危废有限公司', DATE '2026-01-01', DATE '2026-12-31', FALSE, CURRENT_TIMESTAMP);
