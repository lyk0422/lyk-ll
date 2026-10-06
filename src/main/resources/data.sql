-- 合成初始化数据：处理设施目录、示例车辆与近期皮重（仅演示，全部为合成数据）

INSERT INTO facility (code, name, active)
SELECT 'F-INCINERATION', '区生活垃圾焚烧发电厂', TRUE
WHERE NOT EXISTS (SELECT 1 FROM facility WHERE code = 'F-INCINERATION');

INSERT INTO facility (code, name, active)
SELECT 'F-KITCHEN', '区厨余垃圾资源化处理厂', TRUE
WHERE NOT EXISTS (SELECT 1 FROM facility WHERE code = 'F-KITCHEN');

INSERT INTO facility (code, name, active)
SELECT 'F-RECYCLING', '区可回收物分拣中心', TRUE
WHERE NOT EXISTS (SELECT 1 FROM facility WHERE code = 'F-RECYCLING');

INSERT INTO vehicle (plate_no, created_at)
SELECT '沪A00001', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM vehicle WHERE plate_no = '沪A00001');

INSERT INTO vehicle (plate_no, created_at)
SELECT '沪A00002', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM vehicle WHERE plate_no = '沪A00002');

INSERT INTO tare_record (vehicle_id, tare_kg, weighed_at)
SELECT v.id, 8000.000, CURRENT_TIMESTAMP
FROM vehicle v
WHERE v.plate_no = '沪A00001'
  AND NOT EXISTS (SELECT 1 FROM tare_record t WHERE t.vehicle_id = v.id);

INSERT INTO tare_record (vehicle_id, tare_kg, weighed_at)
SELECT v.id, 7500.000, CURRENT_TIMESTAMP
FROM vehicle v
WHERE v.plate_no = '沪A00002'
  AND NOT EXISTS (SELECT 1 FROM tare_record t WHERE t.vehicle_id = v.id);
