-- 冷链运输追溯：H2（MODE=MySQL）自动建表脚本，仅使用合成数据。
-- 时间列均为 Asia/Shanghai 业务时区下的本地时间（TIMESTAMP，不带时区偏移）。

-- 货件：shipmentId 唯一，温度闭区间 [min_temp, max_temp]（摄氏度），
-- max_excursion_minutes 为允许的最大连续超限分钟数，
-- max_sample_interval_seconds 为相邻读数最大采样间隔（秒），
-- current_version 为当前开放运输段版本号（从 1 开始，每次交接加 1）。
CREATE TABLE IF NOT EXISTS shipment (
    shipment_id                  VARCHAR(64)  NOT NULL PRIMARY KEY COMMENT '货件唯一标识',
    min_temp                     DECIMAL(8,3) NOT NULL COMMENT '允许温度闭区间下限（摄氏度，含端点）',
    max_temp                     DECIMAL(8,3) NOT NULL COMMENT '允许温度闭区间上限（摄氏度，含端点）',
    max_excursion_minutes        INT          NOT NULL COMMENT '允许的最大连续超限分钟数；超过即 EXCURSION',
    max_sample_interval_seconds  INT          NOT NULL COMMENT '相邻读数最大采样间隔（秒）；超过即 INCOMPLETE',
    current_version              INT          NOT NULL COMMENT '当前开放运输段版本号',
    created_at                   TIMESTAMP    NOT NULL COMMENT '建件时间（Asia/Shanghai）'
);

-- 运输段：同一货件版本号唯一；end_time/last_sequence 为空表示开放段。
-- status：OPEN 开放中 / CLEAR 合格 / EXCURSION 温度超限 / INCOMPLETE 缺号或间隔超限。
CREATE TABLE IF NOT EXISTS transport_segment (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    shipment_id     VARCHAR(64)  NOT NULL COMMENT '货件标识',
    version         INT          NOT NULL COMMENT '运输段版本号，货件内唯一',
    custodian       VARCHAR(128) NOT NULL COMMENT '本段保管方',
    start_time      TIMESTAMP    NOT NULL COMMENT '本段开始时间（Asia/Shanghai）',
    end_time        TIMESTAMP    NULL     COMMENT '本段交接关闭时间（Asia/Shanghai）；NULL=开放中',
    first_sequence  BIGINT       NOT NULL COMMENT '本段首个设备序号（含）',
    last_sequence   BIGINT       NULL     COMMENT '本段交接时末个设备序号（含）；NULL=开放中',
    status          VARCHAR(20)  NOT NULL COMMENT '段状态：OPEN/CLEAR/EXCURSION/INCOMPLETE',
    CONSTRAINT uq_segment_shipment_version UNIQUE (shipment_id, version)
);

-- 温度读数：同一货件序号严格唯一（允许乱序到达）。
CREATE TABLE IF NOT EXISTS temperature_reading (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    shipment_id   VARCHAR(64)  NOT NULL COMMENT '货件标识',
    sequence_no   BIGINT       NOT NULL COMMENT '设备采样序号，货件内全局严格递增且唯一',
    device_no     VARCHAR(64)  NOT NULL COMMENT '设备序号（设备标识）',
    sampled_at    TIMESTAMP    NOT NULL COMMENT '采样时间（Asia/Shanghai）',
    temperature   DECIMAL(10,3) NOT NULL COMMENT '采样温度（摄氏度，有限数值）',
    CONSTRAINT uq_reading_shipment_seq UNIQUE (shipment_id, sequence_no)
);

-- 交接记录：requestId 全局唯一，保存首次冻结结果与请求指纹用于幂等重放。
CREATE TABLE IF NOT EXISTS handover_record (
    request_id        VARCHAR(80)  NOT NULL PRIMARY KEY COMMENT '交接请求幂等键',
    shipment_id       VARCHAR(64)  NOT NULL COMMENT '货件标识',
    expected_version  INT          NOT NULL COMMENT '请求携带的期望版本号',
    receiver          VARCHAR(128) NOT NULL COMMENT '接收保管方',
    handover_at       TIMESTAMP    NOT NULL COMMENT '交接时间（Asia/Shanghai）',
    last_sequence     BIGINT       NOT NULL COMMENT '本段末个设备序号',
    fingerprint       VARCHAR(64)  NOT NULL COMMENT '请求参数指纹（SHA-256），同键异参判 409',
    segment_status    VARCHAR(20)  NULL     COMMENT '首次冻结的旧段判定结果；NULL=尚未完成',
    new_version       INT          NULL     COMMENT '首次冻结后打开的新段版本号；NULL=尚未完成',
    created_at        TIMESTAMP    NOT NULL COMMENT '记录创建时间（Asia/Shanghai）'
);
