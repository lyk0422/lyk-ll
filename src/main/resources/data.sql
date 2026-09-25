-- 合成主数据：品种与地块（仅本地演示与测试使用）
MERGE INTO variety (id, code, name) KEY (code) VALUES
    (1, 'V-RICE-001', '合成水稻一号'),
    (2, 'V-CORN-001', '合成玉米一号');

MERGE INTO field_plot (id, code, location) KEY (code) VALUES
    (1, 'P-A-01', '合成基地-A区-01号田'),
    (2, 'P-B-02', '合成基地-B区-02号田');
