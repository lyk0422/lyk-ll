-- 合成种子数据：仅用于本地演示与开发，全部为虚构数据。
-- 采用 MERGE / NOT EXISTS 保证可重复执行（多个应用上下文共用命名内存库时幂等）。

MERGE INTO facility (code, name, active) KEY(code) VALUES
    ('F-INCINERATION', '区生活垃圾焚烧发电厂', 1),
    ('F-INCINERATION-2', '区第二生活垃圾焚烧发电厂', 1),
    ('F-KITCHEN', '区厨余垃圾资源化处理站', 1),
    ('F-RECYCLING', '区可回收物分拣中心', 1),
    ('F-HAZARDOUS', '区有害垃圾暂存转运点', 0);

-- 设施可接收品类（合成数据）：焚烧厂收其他垃圾，厨余站收厨余，分拣中心收可回收物，有害暂存点收有害垃圾。
INSERT INTO facility_category (facility_id, category)
SELECT f.id, c.category FROM facility f
JOIN (
    SELECT 'F-INCINERATION' AS code, 'OTHER' AS category
    UNION ALL SELECT 'F-INCINERATION-2', 'OTHER'
    UNION ALL SELECT 'F-KITCHEN', 'KITCHEN'
    UNION ALL SELECT 'F-RECYCLING', 'RECYCLABLE'
    UNION ALL SELECT 'F-HAZARDOUS', 'HAZARDOUS'
) c ON c.code = f.code
WHERE NOT EXISTS (
    SELECT 1 FROM facility_category fc WHERE fc.facility_id = f.id AND fc.category = c.category
);

MERGE INTO vehicle (plate_no) KEY(plate_no) VALUES
    ('沪A10001'),
    ('沪A10002'),
    ('沪A10003');

-- 沪A10001：皮重新鲜（当前时间）；沪A10002：皮重已超过 7 日，进场须复称；沪A10003：无皮重记录。
INSERT INTO tare_record (vehicle_id, tare_kg, weighed_at)
SELECT v.id, 8200.000, CURRENT_TIMESTAMP FROM vehicle v
WHERE v.plate_no = '沪A10001'
  AND NOT EXISTS (SELECT 1 FROM tare_record t WHERE t.vehicle_id = v.id);

INSERT INTO tare_record (vehicle_id, tare_kg, weighed_at)
SELECT v.id, 9000.000, TIMESTAMP '2020-01-01 00:00:00' FROM vehicle v
WHERE v.plate_no = '沪A10002'
  AND NOT EXISTS (SELECT 1 FROM tare_record t WHERE t.vehicle_id = v.id);
