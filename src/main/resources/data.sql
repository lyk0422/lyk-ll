-- 合成台账与保护表初始数据（全部为合成数据，仅用于本地演示与测试基线）

INSERT INTO transmitter (id, code, full_power_w)
SELECT 1, 'TX-ALPHA', 10000 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM transmitter WHERE code = 'TX-ALPHA');
INSERT INTO transmitter (id, code, full_power_w)
SELECT 2, 'TX-BRAVO', 5000 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM transmitter WHERE code = 'TX-BRAVO');

INSERT INTO transmitter_channel (transmitter_id, frequency_mhz)
SELECT 1, 98.100 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM transmitter_channel WHERE frequency_mhz = 98.100);
INSERT INTO transmitter_channel (transmitter_id, frequency_mhz)
SELECT 1, 98.500 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM transmitter_channel WHERE frequency_mhz = 98.500);
INSERT INTO transmitter_channel (transmitter_id, frequency_mhz)
SELECT 2, 101.700 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM transmitter_channel WHERE frequency_mhz = 101.700);

INSERT INTO protection_limit (frequency_mhz, limit_w)
SELECT 98.100, 3000 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM protection_limit WHERE frequency_mhz = 98.100);
INSERT INTO protection_limit (frequency_mhz, limit_w)
SELECT 98.500, 2000 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM protection_limit WHERE frequency_mhz = 98.500);
INSERT INTO protection_limit (frequency_mhz, limit_w)
SELECT 101.700, 1500 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM protection_limit WHERE frequency_mhz = 101.700);
