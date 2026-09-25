-- 合成宽度档位初始化（板宽毫米，宽→窄每级相差 50 毫米，相邻档位差一级合法）。
MERGE INTO width_grade (width_mm, grade_no) KEY (width_mm) VALUES
    (1250, 1),
    (1200, 2),
    (1150, 3),
    (1100, 4),
    (1050, 5),
    (1000, 6);

MERGE INTO id_alloc (seq_name, next_val) KEY (seq_name) VALUES ('global', 1);
