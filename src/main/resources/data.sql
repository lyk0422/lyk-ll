-- 合成宽度档位初始化（板宽毫米，宽→窄每级相差 50 毫米，相邻档位差一级合法）。
MERGE INTO width_grade (width_mm, grade_no) KEY (width_mm) VALUES
    (1250, 1),
    (1200, 2),
    (1150, 3),
    (1100, 4),
    (1050, 5),
    (1000, 6);

MERGE INTO id_alloc (seq_name, next_val) KEY (seq_name) VALUES ('global', 1);

-- 合成轧辊台账：ROLL-A/ROLL-B 为新辊（周期上限 10 公里，累计 0）；
-- ROLL-EXP 累计里程已达上限，处于禁排态，用于演示寿命到期拒绝上机。
MERGE INTO roll_ledger (roll_no, cycle_limit_km, total_km, coils_used, on_machine, banned, frozen_at)
  KEY (roll_no) VALUES
    ('ROLL-A', 10.000, 0.000, 0, 0, 0, NULL),
    ('ROLL-B', 10.000, 0.000, 0, 0, 0, NULL),
    ('ROLL-EXP', 10.000, 10.000, 12, 0, 1, NULL);
