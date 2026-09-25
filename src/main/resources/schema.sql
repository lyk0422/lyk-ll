-- 轨交票务清分库表结构（H2 MySQL 兼容模式）。
-- 时间列均为 Asia/Shanghai 本地时间（LocalDateTime），不带时区；金额单位为“分”（fen），里程单位为“公里”（km）。

CREATE TABLE IF NOT EXISTS station (
  code VARCHAR(16) PRIMARY KEY,
  name VARCHAR(64) NOT NULL,
  line_code VARCHAR(16) NOT NULL
);
COMMENT ON TABLE station IS '站点表（合成数据）；换乘站在多条线路各有一条记录时以 code 唯一为准';
COMMENT ON COLUMN station.code IS '站点编码，全局唯一';
COMMENT ON COLUMN station.name IS '站点中文名';
COMMENT ON COLUMN station.line_code IS '所属线路编码，换乘站记其任一线路';

CREATE TABLE IF NOT EXISTS mileage (
  from_station VARCHAR(16) NOT NULL,
  to_station VARCHAR(16) NOT NULL,
  distance_km INT NOT NULL,
  PRIMARY KEY (from_station, to_station)
);
COMMENT ON TABLE mileage IS '合成里程表：任意两站间最短路径里程（公里），启动时由线网初始化器计算写入';
COMMENT ON COLUMN mileage.distance_km IS '最短路径里程，单位公里，整数';

CREATE TABLE IF NOT EXISTS route_line_mileage (
  from_station VARCHAR(16) NOT NULL,
  to_station VARCHAR(16) NOT NULL,
  line_code VARCHAR(16) NOT NULL,
  distance_km INT NOT NULL,
  PRIMARY KEY (from_station, to_station, line_code)
);
COMMENT ON TABLE route_line_mileage IS '最短路径按线路拆分的承运里程（公里），用于跨线收入分摊';
COMMENT ON COLUMN route_line_mileage.distance_km IS '该线路承运里程，单位公里；同一 OD 各线路之和等于 mileage.distance_km';

CREATE TABLE IF NOT EXISTS idempotency_record (
  request_id VARCHAR(64) PRIMARY KEY,
  fingerprint VARCHAR(128) NOT NULL,
  status VARCHAR(16) NOT NULL,
  response_body CLOB,
  created_at TIMESTAMP NOT NULL,
  completed_at TIMESTAMP
);
COMMENT ON TABLE idempotency_record IS '幂等键表：requestId 唯一；指纹覆盖 requestId 与全部业务字段';
COMMENT ON COLUMN idempotency_record.fingerprint IS 'SHA-256(requestId + 全部业务字段)，同键异参返回 409';
COMMENT ON COLUMN idempotency_record.status IS 'IN_PROGRESS=已占位处理中；COMPLETED=已完成可重放；FAILED=业务失败已释放';
COMMENT ON COLUMN idempotency_record.response_body IS '首次成功响应的 JSON 报文，同键同参重放时原样返回';

CREATE TABLE IF NOT EXISTS metro_transaction (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tx_no VARCHAR(64) NOT NULL UNIQUE,
  request_id VARCHAR(64) NOT NULL,
  ticket_id VARCHAR(64) NOT NULL,
  tx_type VARCHAR(8) NOT NULL,
  station VARCHAR(16) NOT NULL,
  occurred_at TIMESTAMP NOT NULL,
  created_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE metro_transaction IS '交易流水表：进出闸原始流水，流水号全局唯一，重复流水拒绝入账';
COMMENT ON COLUMN metro_transaction.tx_no IS '交易流水号，全局唯一约束';
COMMENT ON COLUMN metro_transaction.tx_type IS 'ENTRY=进闸；EXIT=出闸（含补登出闸）';
COMMENT ON COLUMN metro_transaction.occurred_at IS '交易发生时间（Asia/Shanghai 本地时间）';

CREATE TABLE IF NOT EXISTS trip (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  ticket_id VARCHAR(64) NOT NULL,
  entry_tx_no VARCHAR(64) NOT NULL,
  entry_station VARCHAR(16) NOT NULL,
  entry_time TIMESTAMP NOT NULL,
  exit_tx_no VARCHAR(64),
  exit_station VARCHAR(16),
  exit_time TIMESTAMP,
  distance_km INT,
  chain_cum_km INT,
  fare_fen BIGINT,
  provisional_fare_fen BIGINT,
  status VARCHAR(16) NOT NULL,
  batch_date DATE,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE trip IS '行程表：一次进闸到出闸的完整行程及计费结果';
COMMENT ON COLUMN trip.distance_km IS '进出站最短路径里程，单位公里；未出闸为空';
COMMENT ON COLUMN trip.chain_cum_km IS '连续换乘链累计里程（含本段），单位公里；30 分钟内（含恰 30 分钟）换乘续链，只计一次起步价';
COMMENT ON COLUMN trip.fare_fen IS '实收金额，单位分；含换乘链增量计费与超时补收';
COMMENT ON COLUMN trip.provisional_fare_fen IS '出闸缺失时按最远站票价暂记的金额，单位分；补登出闸后同事务冲销';
COMMENT ON COLUMN trip.status IS 'OPEN=已进闸未出闸；PROVISIONAL=日结暂记；SETTLED=已按实结算';
COMMENT ON COLUMN trip.batch_date IS '实际入账的清分批次日期；封账后迟到交易顺延至未封账批次';

CREATE TABLE IF NOT EXISTS clearing_detail (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  batch_date DATE NOT NULL,
  trip_id BIGINT NOT NULL,
  tx_no VARCHAR(64) NOT NULL,
  line_code VARCHAR(16) NOT NULL,
  distance_km INT NOT NULL,
  ratio DECIMAL(7,4) NOT NULL,
  amount_fen BIGINT NOT NULL,
  detail_type VARCHAR(16) NOT NULL,
  created_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE clearing_detail IS '清分明细：按线路承运里程比例分摊收入，可逐笔复算';
COMMENT ON COLUMN clearing_detail.line_code IS '承运线路编码；PROV 表示出闸缺失暂记/冲销的占位线路';
COMMENT ON COLUMN clearing_detail.ratio IS '分摊比例，保留四位小数；各线路比例之和允许存在尾差';
COMMENT ON COLUMN clearing_detail.amount_fen IS '分摊金额，单位分，取整到分；同批次各线路合计等于实收金额，差额归入里程最长线路';
COMMENT ON COLUMN clearing_detail.detail_type IS 'SETTLE=按实结算；PROVISIONAL=暂记；REVERSAL=暂记冲销（负数）';

CREATE TABLE IF NOT EXISTS settlement_batch (
  batch_date DATE PRIMARY KEY,
  request_id VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  total_fen BIGINT NOT NULL,
  sealed_at TIMESTAMP
);
COMMENT ON TABLE settlement_batch IS '日结清分批次：封账为终态，封账后合计不再变化';
COMMENT ON COLUMN settlement_batch.status IS 'SEALED=已封账（终态）；重复封账幂等成功';
COMMENT ON COLUMN settlement_batch.total_fen IS '封账时核定的批次合计，单位分，封账后不再修改';

CREATE TABLE IF NOT EXISTS diff_record (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  batch_date DATE NOT NULL,
  posted_batch_date DATE NOT NULL,
  tx_no VARCHAR(64) NOT NULL,
  trip_id BIGINT,
  reason VARCHAR(32) NOT NULL,
  created_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE diff_record IS '对账差异登记：封账后迟到交易等差异逐笔登记，可复算';
COMMENT ON COLUMN diff_record.batch_date IS '交易原始归属批次日期（已封账）';
COMMENT ON COLUMN diff_record.posted_batch_date IS '实际入账批次日期（顺延后的未封账批次）';
COMMENT ON COLUMN diff_record.reason IS 'LATE_AFTER_SEAL=封账后迟到交易顺延入账';
