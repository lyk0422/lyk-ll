-- 合成基础数据：ATM 与押运员。MERGE 保证重复初始化幂等。
MERGE INTO atm (id, code, name, capacity, daily_avg_withdrawal, frozen, created_at) KEY(id) VALUES
    (1, 'ATM-0001', '中山路支行存取款机', 400000, 150000, 0, CURRENT_TIMESTAMP),
    (2, 'ATM-0002', '高铁北站自助点',     600000, 220000, 0, CURRENT_TIMESTAMP),
    (3, 'ATM-0003', '大学城便利店点',     200000,  80000, 0, CURRENT_TIMESTAMP);

MERGE INTO custodian (id, code, name, frozen, created_at) KEY(id) VALUES
    (1, 'C-01', '张伟', 0, CURRENT_TIMESTAMP),
    (2, 'C-02', '李娜', 0, CURRENT_TIMESTAMP),
    (3, 'C-03', '王强', 0, CURRENT_TIMESTAMP),
    (4, 'C-04', '赵敏', 0, CURRENT_TIMESTAMP);
