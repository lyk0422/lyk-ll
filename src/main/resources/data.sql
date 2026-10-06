-- 合成初始化数据：引航员与拖轮，仅用于本地演示与测试，不含真实数据
-- 使用 MERGE 保证多上下文重复初始化时幂等
MERGE INTO pilot (id, code, name) KEY(id) VALUES
    (1, 'P001', '张远航'),
    (2, 'P002', '李定澜'),
    (3, 'P003', '王望潮');

MERGE INTO tugboat (id, code, name) KEY(id) VALUES
    (1, 'T001', '港拖一号'),
    (2, 'T002', '港拖二号'),
    (3, 'T003', '港拖三号'),
    (4, 'T004', '港拖四号');
