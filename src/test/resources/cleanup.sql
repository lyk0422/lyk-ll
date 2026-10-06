-- 测试场景间清理：清空全部业务表，避免顺序依赖
DELETE FROM pest_trace;
DELETE FROM purchase_suggestion;
DELETE FROM dispense_record;
DELETE FROM pesticide;
DELETE FROM outbound_registration;
DELETE FROM preventive_evaluation;
DELETE FROM active_chain;
DELETE FROM treatment_chain;
DELETE FROM pest_report;
DELETE FROM zone_adjacency;
DELETE FROM archive_zone;
DELETE FROM idempotency_key;
