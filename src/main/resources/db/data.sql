-- 合成商户与费率数据；MERGE 保证重复初始化不产生重复行。
MERGE INTO merchant (merchant_no, merchant_name, status) KEY(merchant_no)
VALUES ('M001', '合成示例商户一号', 'ACTIVE'),
       ('M002', '合成示例商户二号', 'ACTIVE');

MERGE INTO merchant_fee_rate (merchant_no, fee_rate) KEY(merchant_no)
VALUES ('M001', 0.0060),
       ('M002', 0.0100);
