-- 啤酒发酵批次服务本地 H2(MODE=MySQL) 建表脚本，仅用于合成数据。
CREATE TABLE IF NOT EXISTS fermentation_tank (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    tank_code   VARCHAR(64) NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_tank_code UNIQUE (tank_code)
);
COMMENT ON TABLE fermentation_tank IS '发酵罐表';
COMMENT ON COLUMN fermentation_tank.tank_code IS '发酵罐编码，全局唯一';

CREATE TABLE IF NOT EXISTS fermentation_batch (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no             VARCHAR(64) NOT NULL,
    tank_id              BIGINT NOT NULL,
    status               VARCHAR(16) NOT NULL,
    filled_at            TIMESTAMP(6) NOT NULL,
    primary_started_at   TIMESTAMP(6),
    cooling_at           TIMESTAMP(6),
    maturing_at          TIMESTAMP(6),
    judged_at            TIMESTAMP(6),
    extension_used       BOOLEAN NOT NULL DEFAULT FALSE,
    active_curve_version INTEGER,
    maturity_hold_from   TIMESTAMP(6),
    maturity_passed      BOOLEAN,
    transferred_at       TIMESTAMP(6),
    created_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    active_tank_key      BIGINT GENERATED ALWAYS AS (CASE WHEN status <> 'TRANSFERRED' THEN tank_id ELSE NULL END),
    CONSTRAINT uk_batch_no UNIQUE (batch_no),
    CONSTRAINT uk_active_tank UNIQUE (active_tank_key)
);
COMMENT ON TABLE fermentation_batch IS '发酵批次表';
COMMENT ON COLUMN fermentation_batch.status IS '状态:FILLED满罐/PRIMARY主酵/COOLING降温/MATURING后熟/REVIEW复审/TRANSFERRED清酒转出';
COMMENT ON COLUMN fermentation_batch.filled_at IS '满罐时刻(UTC时间戳)';
COMMENT ON COLUMN fermentation_batch.primary_started_at IS '主酵开始时刻，NULL表示尚未进入主酵';
COMMENT ON COLUMN fermentation_batch.cooling_at IS '降温指令生效时刻，NULL表示尚未降温';
COMMENT ON COLUMN fermentation_batch.maturing_at IS '后熟开始时刻，NULL表示尚未进入后熟';
COMMENT ON COLUMN fermentation_batch.extension_used IS '是否已使用过唯一一次后熟延长';
COMMENT ON COLUMN fermentation_batch.maturity_hold_from IS '后熟保温计时起点(UTC),延长一次时顺延为判定时刻;NULL表示未进入后熟';
COMMENT ON COLUMN fermentation_batch.active_curve_version IS '当前生效温度曲线版本号，NULL表示尚未切换';
COMMENT ON COLUMN fermentation_batch.maturity_passed IS '最近一次成熟度综合判定是否合格;NULL表示尚未判定;延长后以新判定覆盖';
COMMENT ON COLUMN fermentation_batch.transferred_at IS '清酒转出时刻，NULL表示未转出(终态标记)';
COMMENT ON COLUMN fermentation_batch.active_tank_key IS '占用发酵罐的唯一键:未转出批次=tank_id,转出后=NULL,保证一罐一批';

CREATE TABLE IF NOT EXISTS curve_version (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id            BIGINT NOT NULL,
    version_no          INTEGER NOT NULL,
    reason              VARCHAR(24) NOT NULL,
    cooling_command_id  BIGINT,
    request_id          VARCHAR(64),
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_curve_version UNIQUE (batch_id, version_no)
);
COMMENT ON TABLE curve_version IS '温度曲线版本表';
COMMENT ON COLUMN curve_version.version_no IS '批次内曲线版本号,从1递增';
COMMENT ON COLUMN curve_version.reason IS '版本来源:INITIAL入罐初始曲线/COOLING降温切换';
COMMENT ON COLUMN curve_version.cooling_command_id IS '降温切换时关联的降温指令ID,初始曲线为NULL';

CREATE TABLE IF NOT EXISTS curve_segment (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    version_id    BIGINT NOT NULL,
    segment_seq   INTEGER NOT NULL,
    from_hour     INTEGER NOT NULL,
    to_hour       INTEGER NOT NULL,
    target_temp   DECIMAL(5,2) NOT NULL
);
COMMENT ON TABLE curve_segment IS '曲线分段表';
COMMENT ON COLUMN curve_segment.segment_seq IS '段内顺序,从0开始';
COMMENT ON COLUMN curve_segment.from_hour IS '段起点相对满罐时刻的小时数(含)';
COMMENT ON COLUMN curve_segment.to_hour IS '段终点相对满罐时刻的小时数(不含)';
COMMENT ON COLUMN curve_segment.target_temp IS '该段目标温度,单位摄氏度';

CREATE TABLE IF NOT EXISTS temperature_reading (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id      BIGINT NOT NULL,
    reading_at    TIMESTAMP(6) NOT NULL,
    stage         VARCHAR(16) NOT NULL,
    target_temp   DECIMAL(5,2) NOT NULL,
    temperature   DECIMAL(5,2) NOT NULL,
    deviation     DECIMAL(5,2) NOT NULL,
    out_of_range  BOOLEAN NOT NULL,
    run_length    INTEGER NOT NULL,
    alarmed       BOOLEAN NOT NULL,
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE temperature_reading IS '温度采样读数表';
COMMENT ON COLUMN temperature_reading.reading_at IS '采样时刻(UTC)';
COMMENT ON COLUMN temperature_reading.stage IS '采样时批次状态:PRIMARY主酵/MATURING后熟';
COMMENT ON COLUMN temperature_reading.temperature IS '实测温度,单位摄氏度';
COMMENT ON COLUMN temperature_reading.deviation IS '实测温度减目标温度,单位摄氏度';
COMMENT ON COLUMN temperature_reading.out_of_range IS '是否偏离目标超过±0.5℃(恰0.5℃为合格FALSE)';
COMMENT ON COLUMN temperature_reading.run_length IS '截至本点连续偏离采样点数,恢复合格则归0';
COMMENT ON COLUMN temperature_reading.alarmed IS '本点是否触发告警(恰连续3点触发TRUE)';

CREATE TABLE IF NOT EXISTS diacetyl_test (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id    BIGINT NOT NULL,
    tested_at   TIMESTAMP(6) NOT NULL,
    value_mg_l  DECIMAL(6,3) NOT NULL,
    request_id  VARCHAR(64) NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_diacetyl_request UNIQUE (request_id)
);
COMMENT ON TABLE diacetyl_test IS '双乙酰检测记录表';
COMMENT ON COLUMN diacetyl_test.tested_at IS '采样检测时刻(UTC)';
COMMENT ON COLUMN diacetyl_test.value_mg_l IS '双乙酰含量,单位mg/L,恰0.10为合格';
COMMENT ON COLUMN diacetyl_test.request_id IS '检测上报幂等键';

CREATE TABLE IF NOT EXISTS cooling_command (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id      BIGINT NOT NULL,
    command_at    TIMESTAMP(6) NOT NULL,
    from_version  INTEGER,
    to_version    INTEGER NOT NULL,
    request_id    VARCHAR(64) NOT NULL,
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_cooling_request UNIQUE (request_id)
);
COMMENT ON TABLE cooling_command IS '降温指令表';
COMMENT ON COLUMN cooling_command.command_at IS '降温指令时刻(UTC)';
COMMENT ON COLUMN cooling_command.from_version IS '切换前曲线版本号';
COMMENT ON COLUMN cooling_command.to_version IS '切换后曲线版本号,与指令同事务生效';

CREATE TABLE IF NOT EXISTS maturity_judgment (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id    BIGINT NOT NULL,
    judged_at   TIMESTAMP(6) NOT NULL,
    passed      BOOLEAN NOT NULL,
    extended    BOOLEAN NOT NULL,
    detail      VARCHAR(512) NOT NULL,
    request_id  VARCHAR(64) NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_maturity_request UNIQUE (request_id)
);
COMMENT ON TABLE maturity_judgment IS '成熟度判定记录表';
COMMENT ON COLUMN maturity_judgment.passed IS '综合判定是否合格';
COMMENT ON COLUMN maturity_judgment.extended IS '本次不合格是否触发唯一一次后熟延长';
COMMENT ON COLUMN maturity_judgment.detail IS '判定口径说明(后熟时长/保温/延长状态)';

CREATE TABLE IF NOT EXISTS transfer_record (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id        BIGINT NOT NULL,
    transferred_at  TIMESTAMP(6) NOT NULL,
    request_id      VARCHAR(64) NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_transfer_batch UNIQUE (batch_id),
    CONSTRAINT uk_transfer_request UNIQUE (request_id)
);
COMMENT ON TABLE transfer_record IS '清酒转出记录表,每批唯一';
COMMENT ON COLUMN transfer_record.transferred_at IS '转出时刻(UTC),批次进入TRANSFERRED终态';

CREATE TABLE IF NOT EXISTS late_event (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id     BIGINT NOT NULL,
    event_type   VARCHAR(32) NOT NULL,
    payload_json CLOB NOT NULL,
    received_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE late_event IS '转出终态后迟到事件表';
COMMENT ON COLUMN late_event.event_type IS '事件类型:TEMPERATURE_READING温度/DIACETYL_TEST双乙酰';
COMMENT ON COLUMN late_event.payload_json IS '迟到请求原始内容快照';

CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id     VARCHAR(64) NOT NULL,
    request_type   VARCHAR(32) NOT NULL,
    fingerprint    CHAR(64) NOT NULL,
    status_code    INTEGER NOT NULL,
    response_json  CLOB NOT NULL,
    batch_id       BIGINT,
    created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_idempotent_request PRIMARY KEY (request_id)
);
COMMENT ON TABLE idempotent_request IS '幂等请求记录表';
COMMENT ON COLUMN idempotent_request.fingerprint IS '覆盖requestId与全部业务字段的SHA-256指纹,同键异参冲突';
COMMENT ON COLUMN idempotent_request.response_json IS '首次受理的响应快照,重放原样返回';
