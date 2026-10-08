-- 每个测试方法前清理业务数据并重建合成蜂场，避免用例间顺序依赖。
DELETE FROM recall_processing_link;
DELETE FROM recall_harvest_link;
DELETE FROM source_recall;
DELETE FROM processing_batch_ref;
DELETE FROM processing_batch;
DELETE FROM idempotency_record;
DELETE FROM apiary_warning;
DELETE FROM residue_test;
DELETE FROM open_chain;
DELETE FROM test_chain;
DELETE FROM harvest_batch_contribution;
DELETE FROM harvest_batch;
DELETE FROM apiary;

INSERT INTO apiary (id, code, name) VALUES
    (1, 'AP001', '青山蜂场'),
    (2, 'AP002', '河谷蜂场'),
    (3, 'AP003', '林下蜂场');
