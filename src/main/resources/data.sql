-- 合成初始化数据，仅用于本地演示与联调，非真实业务数据。

INSERT INTO apiary (id, code, name) VALUES
    (1, 'AP001', '青山蜂场'),
    (2, 'AP002', '河谷蜂场'),
    (3, 'AP003', '林下蜂场');

-- 已放行的合成采收批：两个蜂场贡献，快检抑制率 12.50% 合格放行。
INSERT INTO harvest_batch (id, batch_no, flower_period, status, created_at) VALUES
    (1, 'HB-SEED-001', '2026-洋槐', 'RELEASED', '2026-05-10 08:00:00');

INSERT INTO harvest_batch_contribution (batch_id, apiary_id, ratio) VALUES
    (1, 1, 60.00),
    (1, 2, 40.00);

INSERT INTO test_chain (id, batch_id, status, created_at, closed_at) VALUES
    (1, 1, 'CLOSED', '2026-05-10 09:00:00', '2026-05-10 09:00:00');

INSERT INTO residue_test (chain_id, batch_id, test_type, sample_no, inhibition_rate, conclusion, created_at) VALUES
    (1, 1, 'RAPID', 'SMP-SEED-001', 12.50, 'PASS', '2026-05-10 09:00:00');

-- 已受理的合成加工批：引用采收批 1，用于演示召回对既有加工批加隔离标记。
INSERT INTO processing_batch (id, batch_no, created_at) VALUES
    (1, 'PB-SEED-001', '2026-05-11 08:00:00');

INSERT INTO processing_batch_ref (processing_batch_id, harvest_batch_id) VALUES
    (1, 1);
