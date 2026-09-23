-- 实验室样本保管服务建表脚本（H2 MySQL 兼容模式，随应用启动自动执行）
-- 体积单位统一为微升（µL），DECIMAL(14,3) 保证最多三位小数的精确守恒；
-- 时间字段为 UTC 时间戳；version 为乐观锁版本号，从 0 开始每次变更加 1。

CREATE TABLE IF NOT EXISTS specimen (
  specimen_id VARCHAR(64) NOT NULL PRIMARY KEY,
  total_volume_ul DECIMAL(14,3) NOT NULL,
  remaining_volume_ul DECIMAL(14,3) NOT NULL,
  custodian VARCHAR(64) NOT NULL,
  version BIGINT NOT NULL,
  last_event_at TIMESTAMP NOT NULL,
  created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS aliquot (
  aliquot_id VARCHAR(64) NOT NULL PRIMARY KEY,
  specimen_id VARCHAR(64) NOT NULL,
  volume_ul DECIMAL(14,3) NOT NULL,
  status VARCHAR(16) NOT NULL,
  custodian VARCHAR(64) NOT NULL,
  version BIGINT NOT NULL,
  last_event_at TIMESTAMP NOT NULL,
  created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS assay_request (
  assay_id VARCHAR(64) NOT NULL PRIMARY KEY,
  aliquot_id VARCHAR(64) NOT NULL,
  project VARCHAR(128) NOT NULL,
  reserved_volume_ul DECIMAL(14,3) NOT NULL,
  status VARCHAR(16) NOT NULL,
  result CLOB NULL,
  custodian VARCHAR(64) NOT NULL,
  version BIGINT NOT NULL,
  last_event_at TIMESTAMP NOT NULL,
  created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS custody_event (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  entity_type VARCHAR(16) NOT NULL,
  entity_id VARCHAR(64) NOT NULL,
  from_custodian VARCHAR(64) NOT NULL,
  to_custodian VARCHAR(64) NOT NULL,
  event_at TIMESTAMP NOT NULL,
  request_id VARCHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS idempotency_record (
  request_id VARCHAR(64) NOT NULL PRIMARY KEY,
  operation VARCHAR(32) NOT NULL,
  fingerprint VARCHAR(64) NOT NULL,
  response_body CLOB NOT NULL,
  created_at TIMESTAMP NOT NULL
);

COMMENT ON TABLE specimen IS '原始样本：以 specimenId 唯一，体积单位微升，最多三位小数';
COMMENT ON COLUMN specimen.specimen_id IS '样本唯一标识';
COMMENT ON COLUMN specimen.total_volume_ul IS '入库总体积（微升，三位小数）';
COMMENT ON COLUMN specimen.remaining_volume_ul IS '剩余未分装体积（微升），与 aliquot 体积之和精确守恒';
COMMENT ON COLUMN specimen.custodian IS '当前保管方';
COMMENT ON COLUMN specimen.version IS '乐观锁版本号，每次变更加 1';
COMMENT ON COLUMN specimen.last_event_at IS '最近一次保管交接时间（UTC），初始为纪元起始表示尚无交接；交接时间不得早于该值';
COMMENT ON COLUMN specimen.created_at IS '入库时间（UTC）';

COMMENT ON TABLE aliquot IS '分装样本：由分装操作生成，状态 AVAILABLE/RESERVED/CONSUMED/QUARANTINED';
COMMENT ON COLUMN aliquot.aliquot_id IS '分装唯一标识，分装操作内生成';
COMMENT ON COLUMN aliquot.specimen_id IS '来源样本标识';
COMMENT ON COLUMN aliquot.volume_ul IS '分装体积（微升，三位小数）';
COMMENT ON COLUMN aliquot.status IS '状态：AVAILABLE 可用 / RESERVED 已预留 / CONSUMED 已消耗 / QUARANTINED 已隔离';
COMMENT ON COLUMN aliquot.custodian IS '当前保管方';
COMMENT ON COLUMN aliquot.version IS '乐观锁版本号';
COMMENT ON COLUMN aliquot.last_event_at IS '最近一次保管交接时间（UTC），初始为纪元起始表示尚无交接';
COMMENT ON COLUMN aliquot.created_at IS '生成时间（UTC）';

COMMENT ON TABLE assay_request IS '检测申请：占用 aliquot 体积，状态 RESERVED/CONSUMED/CANCELLED';
COMMENT ON COLUMN assay_request.assay_id IS '检测申请唯一标识，创建时生成';
COMMENT ON COLUMN assay_request.aliquot_id IS '被占用的分装标识';
COMMENT ON COLUMN assay_request.project IS '检测项目名称';
COMMENT ON COLUMN assay_request.reserved_volume_ul IS '占用体积（微升，三位小数）';
COMMENT ON COLUMN assay_request.status IS '状态：RESERVED 已预留未开始 / CONSUMED 已开始并消耗 / CANCELLED 已取消';
COMMENT ON COLUMN assay_request.result IS '检测结果文本；未出结果或已取消时为 NULL';
COMMENT ON COLUMN assay_request.custodian IS '当前保管方';
COMMENT ON COLUMN assay_request.version IS '乐观锁版本号';
COMMENT ON COLUMN assay_request.last_event_at IS '最近一次保管交接时间（UTC），初始为纪元起始表示尚无交接';
COMMENT ON COLUMN assay_request.created_at IS '申请时间（UTC）';

COMMENT ON TABLE custody_event IS '保管交接事件：记录样本/分装/检测的每次保管方变更，隔离后历史事件保留';
COMMENT ON COLUMN custody_event.id IS '事件自增主键';
COMMENT ON COLUMN custody_event.entity_type IS '实体类型：SPECIMEN / ALIQUOT / ASSAY';
COMMENT ON COLUMN custody_event.entity_id IS '实体标识';
COMMENT ON COLUMN custody_event.from_custodian IS '交出方（须为当前持有人）';
COMMENT ON COLUMN custody_event.to_custodian IS '接收方';
COMMENT ON COLUMN custody_event.event_at IS '交接发生时间（UTC），不得早于该实体上一事件时间';
COMMENT ON COLUMN custody_event.request_id IS '交接请求幂等键，重复交接不新增事件';
COMMENT ON COLUMN custody_event.created_at IS '事件记录时间（UTC）';

COMMENT ON TABLE idempotency_record IS '幂等记录：同键同参返回首次结果，同键异参返回 409';
COMMENT ON COLUMN idempotency_record.request_id IS '客户端请求幂等键';
COMMENT ON COLUMN idempotency_record.operation IS '操作类型：ALIQUOT / ASSAY_CREATE / TRANSFER';
COMMENT ON COLUMN idempotency_record.fingerprint IS '请求参数指纹（SHA-256），用于识别同键异参';
COMMENT ON COLUMN idempotency_record.response_body IS '首次成功响应快照（JSON），重放时原样返回';
COMMENT ON COLUMN idempotency_record.created_at IS '记录时间（UTC）';
