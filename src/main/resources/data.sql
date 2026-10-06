-- 合成初始化数据：仅用于本地演示与开发，非真实数据。
-- 使用 INSERT IGNORE 保证重复启动（含测试上下文复用）时幂等。

INSERT IGNORE INTO pilot (id, name, license_no) VALUES
    (1, '张远航', 'PLT-0001'),
    (2, '李泊舟', 'PLT-0002'),
    (3, '王引潮', 'PLT-0003');

INSERT IGNORE INTO tug (id, name, bollard_pull) VALUES
    (1, '拖轮-海力一号', 60),
    (2, '拖轮-海力二号', 55),
    (3, '拖轮-海力三号', 50),
    (4, '拖轮-海力四号', 45);
