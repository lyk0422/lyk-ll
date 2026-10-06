-- 合成台账与保护表初始数据（仅合成数据）

INSERT INTO transmitter (code, full_power_watts)
SELECT 'TX-ALPHA', 10000
WHERE NOT EXISTS (SELECT 1 FROM transmitter WHERE code = 'TX-ALPHA');

INSERT INTO transmitter (code, full_power_watts)
SELECT 'TX-BETA', 8000
WHERE NOT EXISTS (SELECT 1 FROM transmitter WHERE code = 'TX-BETA');

INSERT INTO transmitter (code, full_power_watts)
SELECT 'TX-GAMMA', 5000
WHERE NOT EXISTS (SELECT 1 FROM transmitter WHERE code = 'TX-GAMMA');

-- 发射机可用频率：TX-ALPHA 为双频发射机（用于验证多未结事件最严格限值）
INSERT INTO transmitter_channel (transmitter_id, frequency_mhz)
SELECT id, 514.250 FROM transmitter WHERE code = 'TX-ALPHA'
AND NOT EXISTS (
    SELECT 1 FROM transmitter_channel tc
    WHERE tc.transmitter_id = transmitter.id AND tc.frequency_mhz = 514.250);

INSERT INTO transmitter_channel (transmitter_id, frequency_mhz)
SELECT id, 626.000 FROM transmitter WHERE code = 'TX-ALPHA'
AND NOT EXISTS (
    SELECT 1 FROM transmitter_channel tc
    WHERE tc.transmitter_id = transmitter.id AND tc.frequency_mhz = 626.000);

INSERT INTO transmitter_channel (transmitter_id, frequency_mhz)
SELECT id, 514.250 FROM transmitter WHERE code = 'TX-BETA'
AND NOT EXISTS (
    SELECT 1 FROM transmitter_channel tc
    WHERE tc.transmitter_id = transmitter.id AND tc.frequency_mhz = 514.250);

INSERT INTO transmitter_channel (transmitter_id, frequency_mhz)
SELECT id, 626.000 FROM transmitter WHERE code = 'TX-GAMMA'
AND NOT EXISTS (
    SELECT 1 FROM transmitter_channel tc
    WHERE tc.transmitter_id = transmitter.id AND tc.frequency_mhz = 626.000);

INSERT INTO protection_limit (frequency_mhz, max_power_watts)
SELECT 514.250, 2000
WHERE NOT EXISTS (SELECT 1 FROM protection_limit WHERE frequency_mhz = 514.250);

INSERT INTO protection_limit (frequency_mhz, max_power_watts)
SELECT 626.000, 1500
WHERE NOT EXISTS (SELECT 1 FROM protection_limit WHERE frequency_mhz = 626.000);
