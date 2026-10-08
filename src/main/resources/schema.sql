-- 水族馆水质监测与联动处置 schema（H2 MODE=MySQL）；时间字段均为 Asia/Shanghai 墙钟时间。

CREATE TABLE IF NOT EXISTS tank (
  id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
  code VARCHAR(64) NOT NULL COMMENT '展示池编码',
  name VARCHAR(128) NOT NULL COMMENT '展示池名称',
  status VARCHAR(16) NOT NULL DEFAULT 'NORMAL' COMMENT '状态: NORMAL正常/EMERGENCY紧急',
  display_suspended TINYINT NOT NULL DEFAULT 0 COMMENT '是否暂停展示登记: 0否 1是',
  auto_disposal_suspended TINYINT NOT NULL DEFAULT 0 COMMENT '传感器失联挂起自动处置: 0否 1是',
  data_missing TINYINT NOT NULL DEFAULT 0 COMMENT '数据缺失标记: 0否 1是',
  consecutive_violations INT NOT NULL DEFAULT 0 COMMENT '连续越限采样点数',
  sensor_status VARCHAR(16) NOT NULL DEFAULT 'ONLINE' COMMENT '传感器状态: ONLINE在线/LOST失联/AWAITING_CONFIRM待人工确认',
  sensor_last_seen_at TIMESTAMP NOT NULL COMMENT '传感器最后心跳时间',
  created_at TIMESTAMP NOT NULL COMMENT '创建时间',
  CONSTRAINT uk_tank_code UNIQUE (code)
);

CREATE TABLE IF NOT EXISTS water_sample (
  id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
  tank_id BIGINT NOT NULL COMMENT '展示池ID',
  dissolved_oxygen DOUBLE NOT NULL COMMENT '溶解氧(mg/L)',
  ammonia_nitrogen DOUBLE NOT NULL COMMENT '氨氮(mg/L)',
  temperature DOUBLE NOT NULL COMMENT '温度(摄氏度)',
  violating TINYINT NOT NULL COMMENT '是否越限: 0否 1是',
  sampled_at TIMESTAMP NOT NULL COMMENT '采样时间',
  created_at TIMESTAMP NOT NULL COMMENT '入库时间',
  CONSTRAINT fk_sample_tank FOREIGN KEY (tank_id) REFERENCES tank(id)
);

CREATE TABLE IF NOT EXISTS alarm (
  id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
  tank_id BIGINT NOT NULL COMMENT '展示池ID',
  level VARCHAR(16) NOT NULL COMMENT '级别: WARN告警/EMERGENCY紧急',
  metric VARCHAR(128) NOT NULL COMMENT '越限指标，多项以逗号连接',
  message VARCHAR(512) NOT NULL COMMENT '告警描述',
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE生效/RESOLVED已解除',
  created_at TIMESTAMP NOT NULL COMMENT '告警时间',
  resolved_at TIMESTAMP NULL COMMENT '解除时间，NULL表示仍生效',
  CONSTRAINT fk_alarm_tank FOREIGN KEY (tank_id) REFERENCES tank(id)
);

CREATE TABLE IF NOT EXISTS disposal_order (
  id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
  alarm_id BIGINT NOT NULL COMMENT '触发告警ID',
  tank_id BIGINT NOT NULL COMMENT '展示池ID',
  type VARCHAR(16) NOT NULL COMMENT '指令类型: OXYGENATE增氧/WATER_CHANGE换水/ISOLATE停展隔离',
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING待确认/CONFIRMED已确认/ESCALATED已升级',
  issued_at TIMESTAMP NOT NULL COMMENT '下发时间',
  confirm_deadline TIMESTAMP NOT NULL COMMENT '回执确认截止(下发+5分钟)，恰满未确认即升级',
  confirmed_at TIMESTAMP NULL COMMENT '回执确认时间，NULL表示未确认',
  escalated_at TIMESTAMP NULL COMMENT '升级时间，NULL表示未升级',
  CONSTRAINT fk_order_alarm FOREIGN KEY (alarm_id) REFERENCES alarm(id),
  CONSTRAINT fk_order_tank FOREIGN KEY (tank_id) REFERENCES tank(id)
);

CREATE TABLE IF NOT EXISTS retest_record (
  id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
  tank_id BIGINT NOT NULL COMMENT '展示池ID',
  dissolved_oxygen DOUBLE NOT NULL COMMENT '复测溶解氧(mg/L)',
  ammonia_nitrogen DOUBLE NOT NULL COMMENT '复测氨氮(mg/L)',
  temperature DOUBLE NOT NULL COMMENT '复测温度(摄氏度)',
  passed TINYINT NOT NULL COMMENT '复测是否合格: 0否 1是',
  recorded_at TIMESTAMP NOT NULL COMMENT '复测记录时间',
  CONSTRAINT fk_retest_tank FOREIGN KEY (tank_id) REFERENCES tank(id)
);

CREATE TABLE IF NOT EXISTS exhibit_registration (
  id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
  tank_id BIGINT NOT NULL COMMENT '展示池ID',
  exhibit_name VARCHAR(128) NOT NULL COMMENT '展示登记名称',
  created_at TIMESTAMP NOT NULL COMMENT '登记时间',
  CONSTRAINT fk_exhibit_tank FOREIGN KEY (tank_id) REFERENCES tank(id)
);

CREATE TABLE IF NOT EXISTS idempotency_record (
  request_id VARCHAR(128) PRIMARY KEY COMMENT '幂等键',
  fingerprint VARCHAR(64) NOT NULL COMMENT '指纹，覆盖requestId与全部业务字段',
  http_status INT NULL COMMENT '首次响应HTTP状态码，NULL表示处理中',
  response_body CLOB NULL COMMENT '首次响应JSON，NULL表示处理中',
  created_at TIMESTAMP NOT NULL COMMENT '记录创建时间'
);
