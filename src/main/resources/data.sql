-- 合成基础数据：1 座水库、3 孔闸门、1 条水情读数（仅合成数据）
MERGE INTO reservoir (id, code, name, created_at) KEY (id) VALUES
  (1, 'RSV-001', '云峰水库', CURRENT_TIMESTAMP);

MERGE INTO gate (id, code, name, reservoir_id, opening, updated_at) KEY (id) VALUES
  (1, 'GATE-A', '1号表孔闸门', 1, 0.000, CURRENT_TIMESTAMP),
  (2, 'GATE-B', '2号表孔闸门', 1, 0.000, CURRENT_TIMESTAMP),
  (3, 'GATE-C', '3号深孔闸门', 1, 0.000, CURRENT_TIMESTAMP);

MERGE INTO water_reading (id, reservoir_id, inflow, level_value, read_at) KEY (id) VALUES
  (1, 1, 1200.000, 245.500, CURRENT_TIMESTAMP);
