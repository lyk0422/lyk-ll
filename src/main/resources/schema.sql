-- 粮情测控与通风作业联动服务：H2 (MODE=MySQL) 建表脚本
-- 状态统一用英文码存储，含义见各列 COMMENT；时间均为 Asia/Shanghai 本地墙钟时间。

CREATE TABLE IF NOT EXISTS silo (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    grain_type VARCHAR(32) NOT NULL,
    safe_temperature DOUBLE NOT NULL,
    max_gradient DOUBLE NOT NULL,
    equilibrium_humidity DOUBLE NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'MONITORING',
    ventilation_rounds INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
COMMENT ON COLUMN silo.code IS '粮仓编号（合成数据唯一键）';
COMMENT ON COLUMN silo.safe_temperature IS '安全温度，单位摄氏度；任一粮温点严格大于该值才告警，恰等判正常';
COMMENT ON COLUMN silo.max_gradient IS '允许粮温梯度，单位摄氏度；复测时最高温减最低温严格大于该值判超标，恰等合格';
COMMENT ON COLUMN silo.equilibrium_humidity IS '粮堆平衡湿度，单位百分比；外界湿度严格大于该值禁止通风，恰等允许';
COMMENT ON COLUMN silo.status IS '粮仓状态：MONITORING 日常监测 / ALARM 异常告警 / VENTILATING 通风作业 / RETEST_PENDING 待复测 / RETEST_FAIL 复测超标 / RETEST_PASS 复测达标 / MANUAL 人工处置 / ARCHIVED 已归档（终态）';
COMMENT ON COLUMN silo.ventilation_rounds IS '已开始的通风轮次，单位轮，最大 2；两轮复测仍超标转人工处置';

CREATE TABLE IF NOT EXISTS fan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE fan IS '风机台账（合成数据），同一台风机同一时刻只服务一个仓';

-- 风机执行中占用表：fan_id 为主键，插入冲突即代表风机已被锁定，保证并发下唯一占用。
CREATE TABLE IF NOT EXISTS fan_active (
    fan_id BIGINT NOT NULL PRIMARY KEY,
    operation_id BIGINT NOT NULL,
    silo_id BIGINT NOT NULL,
    locked_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS sensor_reading (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    silo_id BIGINT NOT NULL,
    point_code VARCHAR(64) NOT NULL,
    temperature DOUBLE,
    humidity DOUBLE,
    measured_at TIMESTAMP NOT NULL,
    phase VARCHAR(32) NOT NULL DEFAULT 'DAILY',
    created_at TIMESTAMP NOT NULL
);
COMMENT ON COLUMN sensor_reading.temperature IS '粮温，单位摄氏度，空值表示该点只上报湿度';
COMMENT ON COLUMN sensor_reading.humidity IS '相对湿度，单位百分比，空值表示该点只上报温度';
COMMENT ON COLUMN sensor_reading.phase IS '数据所属阶段：DAILY 日常 / ALARM 告警采样 / ROUND_1 一轮复测 / ROUND_2 二轮复测';

CREATE TABLE IF NOT EXISTS late_reading_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    silo_id BIGINT NOT NULL,
    point_code VARCHAR(64) NOT NULL,
    temperature DOUBLE,
    humidity DOUBLE,
    measured_at TIMESTAMP,
    received_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE late_reading_event IS '粮仓归档后迟到的温湿度数据，仅登记事件，不参与任何业务判定';

CREATE TABLE IF NOT EXISTS alarm (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    silo_id BIGINT NOT NULL,
    request_id VARCHAR(64) NOT NULL UNIQUE,
    max_temperature DOUBLE NOT NULL,
    min_temperature DOUBLE NOT NULL,
    alarmed BOOLEAN NOT NULL,
    measured_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS ventilation_operation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    silo_id BIGINT NOT NULL,
    fan_id BIGINT NOT NULL,
    request_id VARCHAR(64) NOT NULL UNIQUE,
    purpose VARCHAR(32) NOT NULL,
    round_no INT NOT NULL,
    ambient_humidity DOUBLE NOT NULL,
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'EXECUTING',
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON COLUMN ventilation_operation.purpose IS '通风目的：COOLING 降温 / DEHUMIDIFY 降水 / QUALITY 调质';
COMMENT ON COLUMN ventilation_operation.ambient_humidity IS '下达时外界相对湿度，单位百分比，严格大于粮堆平衡湿度禁止通风';
COMMENT ON COLUMN ventilation_operation.start_time IS '作业窗起点（含），Asia/Shanghai';
COMMENT ON COLUMN ventilation_operation.end_time IS '作业窗终点（不含），Asia/Shanghai；占用按半开区间[start,end)判定，端点相接不冲突';
COMMENT ON COLUMN ventilation_operation.status IS 'EXECUTING 执行中 / COMPLETED 已完成（复测时同事务完成并释放风机）';

CREATE TABLE IF NOT EXISTS retest (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    silo_id BIGINT NOT NULL,
    operation_id BIGINT NOT NULL,
    request_id VARCHAR(64) NOT NULL UNIQUE,
    round_no INT NOT NULL,
    max_temperature DOUBLE NOT NULL,
    min_temperature DOUBLE NOT NULL,
    gradient DOUBLE NOT NULL,
    passed BOOLEAN NOT NULL,
    measured_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON COLUMN retest.gradient IS '复测粮温梯度=最高温-最低温，单位摄氏度；任一点超安全温度或梯度超标即不通过';

-- 幂等请求台账：request_id 主键，指纹覆盖 requestId 与全部业务字段。
CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id VARCHAR(64) NOT NULL PRIMARY KEY,
    kind VARCHAR(32) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    silo_id BIGINT,
    status_code INT NOT NULL,
    response CLOB NOT NULL,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON COLUMN idempotent_request.kind IS '请求类型：ALARM 告警 / VENTILATION 通风令 / RETEST 复测 / ARCHIVE 归档';
COMMENT ON COLUMN idempotent_request.fingerprint IS 'SHA-256 指纹，覆盖 requestId 与全部业务字段；同键异参返回 409';
