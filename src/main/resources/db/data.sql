-- 合成主数据：馈线、重要用户分级、抢修班组（H2 内存库，仅 JVM 生命周期内保留）

INSERT INTO feeder (feeder_code, feeder_name, substation, status, create_time) VALUES
    ('F101', '10kV 东风一线', '东风变电站', 'NORMAL', CURRENT_TIMESTAMP),
    ('F102', '10kV 滨河二线', '滨河变电站', 'NORMAL', CURRENT_TIMESTAMP),
    ('F103', '10kV 开发区三线', '开发区变电站', 'NORMAL', CURRENT_TIMESTAMP);

INSERT INTO important_user (user_code, user_name, grade, feeder_id, contact, create_time) VALUES
    ('U-S01', '市第一人民医院', 'SPECIAL', 1, '000-0000001', CURRENT_TIMESTAMP),
    ('U-S02', '市应急指挥中心',   'SPECIAL', 2, '000-0000002', CURRENT_TIMESTAMP),
    ('U-F01', '市自来水公司',     'FIRST',   1, '000-0000003', CURRENT_TIMESTAMP),
    ('U-F02', '轨道交通控制中心', 'FIRST',   2, '000-0000004', CURRENT_TIMESTAMP),
    ('U-D01', '滨河中学',         'SECOND',  2, '000-0000005', CURRENT_TIMESTAMP),
    ('U-N01', '滨河商业街',       'NORMAL',  2, '000-0000006', CURRENT_TIMESTAMP);

INSERT INTO crew (crew_code, crew_name, status, create_time) VALUES
    ('C01', '抢修一班', 'IDLE', CURRENT_TIMESTAMP),
    ('C02', '抢修二班', 'IDLE', CURRENT_TIMESTAMP),
    ('C03', '抢修三班', 'IDLE', CURRENT_TIMESTAMP);
