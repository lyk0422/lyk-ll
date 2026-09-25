-- 合成基础数据：养殖场与可售存栏（耳标段）。仅存在于 JVM 内存。
MERGE INTO farm (farm_id, farm_name, created_at) KEY (farm_id)
VALUES ('FARM-001', '东山合成养殖场', CURRENT_TIMESTAMP);
MERGE INTO farm (farm_id, farm_name, created_at) KEY (farm_id)
VALUES ('FARM-002', '西河合成养禽场', CURRENT_TIMESTAMP);

MERGE INTO farm_stock (farm_id, animal_type, ear_tag_range, total_count, available_count, version)
KEY (farm_id, animal_type, ear_tag_range)
VALUES ('FARM-001', 'CATTLE', 'TAG-C-0001-0100', 100, 100, 0);
MERGE INTO farm_stock (farm_id, animal_type, ear_tag_range, total_count, available_count)
KEY (farm_id, animal_type, ear_tag_range)
VALUES ('FARM-001', 'PIG', 'TAG-P-0001-0200', 200, 200);
MERGE INTO farm_stock (farm_id, animal_type, ear_tag_range, total_count, available_count)
KEY (farm_id, animal_type, ear_tag_range)
VALUES ('FARM-001', 'SHEEP', 'TAG-S-0001-0050', 50, 50);
MERGE INTO farm_stock (farm_id, animal_type, ear_tag_range, total_count, available_count)
KEY (farm_id, animal_type, ear_tag_range)
VALUES ('FARM-002', 'POULTRY', 'TAG-B-0001-0500', 500, 500);
