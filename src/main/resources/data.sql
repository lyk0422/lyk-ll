-- 合成种子数据（幂等）：计量站、管输合同、若干气量日的日指定能量
-- 全部为虚构数据，仅用于本地演示；时区按 UTC 气量日。

MERGE INTO meter_station (station_code, station_name, party_a_name, party_b_name)
KEY (station_code)
VALUES ('ST01', '门东交接计量站', '华北天然气销售有限公司', '东城燃气有限公司');

MERGE INTO pipeline_contract (contract_no, station_code, penalty_unit_price)
KEY (contract_no)
VALUES ('C-2026-001', 'ST01', 0.5000);

MERGE INTO daily_nomination (station_code, gas_date, nominated_energy)
KEY (station_code, gas_date)
VALUES ('ST01', DATE '2026-09-20', 10000.00),
       ('ST01', DATE '2026-09-21', 10000.00),
       ('ST01', DATE '2026-09-22', 10000.00),
       ('ST01', DATE '2026-09-23', 10000.00),
       ('ST01', DATE '2026-09-24', 10000.00),
       ('ST01', DATE '2026-09-25', 10000.00);
