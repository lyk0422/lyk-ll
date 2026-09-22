-- 本工程本地与测试默认使用 H2 内存库（MODE=MySQL），实际建表脚本见 schema-h2.sql，
-- 由 spring.sql.init.platform=h2 指定加载。Spring Boot 会同时执行通用 schema.sql，
-- 空脚本会触发解析异常，故保留一条无副作用的 no-op 语句。
SELECT 1;
