-- 船舶防污染记录服务：H2（MODE=MySQL）建表脚本，仅合成数据，JVM 生命周期内有效。

CREATE TABLE IF NOT EXISTS ship (
    ship_id     VARCHAR(64)  NOT NULL,
    ship_name   VARCHAR(128) NOT NULL,
    created_at  TIMESTAMP    NOT NULL,
    CONSTRAINT pk_ship PRIMARY KEY (ship_id)
);
COMMENT ON COLUMN ship.ship_id IS '船舶合成标识，调用方指定或服务端生成';

CREATE TABLE IF NOT EXISTS voyage (
    ship_id     VARCHAR(64)  NOT NULL,
    voyage_no   VARCHAR(64)  NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    closed_at   TIMESTAMP    NULL,
    created_at  TIMESTAMP    NOT NULL,
    CONSTRAINT pk_voyage PRIMARY KEY (ship_id, voyage_no),
    CONSTRAINT fk_voyage_ship FOREIGN KEY (ship_id) REFERENCES ship (ship_id),
    CONSTRAINT ck_voyage_status CHECK (status IN ('OPEN', 'CLOSED'))
);
COMMENT ON COLUMN voyage.status IS '航次状态：OPEN-开放可新增记录；CLOSED-已关闭，新增记录一律拒绝';

-- 港口兼作“距最近陆地”的合成岸线参考点（海里距离按大圆距离计算）
CREATE TABLE IF NOT EXISTS port (
    port_code   VARCHAR(64)  NOT NULL,
    port_name   VARCHAR(128) NOT NULL,
    latitude    DECIMAL(10, 6) NOT NULL,
    longitude   DECIMAL(10, 6) NOT NULL,
    CONSTRAINT pk_port PRIMARY KEY (port_code)
);
COMMENT ON COLUMN port.latitude IS '港口/岸线参考点纬度（十进制度，WGS84 合成坐标）';
COMMENT ON COLUMN port.longitude IS '港口/岸线参考点经度（十进制度，WGS84 合成坐标）';

CREATE TABLE IF NOT EXISTS special_area (
    area_id     BIGINT AUTO_INCREMENT NOT NULL,
    area_code   VARCHAR(64)  NOT NULL,
    area_name   VARCHAR(128) NOT NULL,
    CONSTRAINT pk_special_area PRIMARY KEY (area_id),
    CONSTRAINT uq_special_area_code UNIQUE (area_code)
);

CREATE TABLE IF NOT EXISTS special_area_vertex (
    area_id     BIGINT       NOT NULL,
    seq         INT          NOT NULL,
    latitude    DECIMAL(10, 6) NOT NULL,
    longitude   DECIMAL(10, 6) NOT NULL,
    CONSTRAINT pk_special_area_vertex PRIMARY KEY (area_id, seq),
    CONSTRAINT fk_sav_area FOREIGN KEY (area_id) REFERENCES special_area (area_id)
);
COMMENT ON COLUMN special_area_vertex.latitude IS '多边形顶点纬度（十进制度）；边界线上的点视为区域内';

-- 排放记录版本表：每次提交/修正均新增一行，不做物理删除
CREATE TABLE IF NOT EXISTS emission_record (
    id                   BIGINT AUTO_INCREMENT NOT NULL,
    record_no            VARCHAR(64)  NOT NULL,
    version              INT          NOT NULL,
    request_id           VARCHAR(96)  NULL,
    ship_id              VARCHAR(64)  NOT NULL,
    voyage_no            VARCHAR(64)  NOT NULL,
    discharge_type       VARCHAR(16)  NOT NULL,
    latitude             DECIMAL(10, 6) NOT NULL,
    longitude            DECIMAL(10, 6) NOT NULL,
    event_time           TIMESTAMP    NOT NULL,
    quantity             DECIMAL(14, 3) NOT NULL,
    unit                 VARCHAR(16)  NOT NULL,
    compliant            BOOLEAN      NOT NULL,
    compliance_detail    VARCHAR(512) NULL,
    status               VARCHAR(16)  NOT NULL,
    supersedes_record_no VARCHAR(64)  NULL,
    supersedes_version   INT          NULL,
    created_at           TIMESTAMP    NOT NULL,
    CONSTRAINT pk_emission_record PRIMARY KEY (id),
    CONSTRAINT uq_emission_version UNIQUE (record_no, version),
    CONSTRAINT ck_emission_type CHECK (discharge_type IN ('OIL', 'GARBAGE', 'SEWAGE')),
    CONSTRAINT ck_emission_status CHECK (status IN ('ACTIVE', 'SUPERSEDED')),
    CONSTRAINT ck_emission_quantity CHECK (quantity > 0),
    CONSTRAINT fk_emission_voyage FOREIGN KEY (ship_id, voyage_no)
        REFERENCES voyage (ship_id, voyage_no)
);
COMMENT ON COLUMN emission_record.record_no IS '逻辑记录号，同一条排放的所有版本共用';
COMMENT ON COLUMN emission_record.version IS '版本号，从 1 起递增；并发修正仅一个版本胜出';
COMMENT ON COLUMN emission_record.event_time IS '排放时刻（UTC）';
COMMENT ON COLUMN emission_record.quantity IS '排放数量（>0），单位见 unit';
COMMENT ON COLUMN emission_record.compliant IS '提交时合规结论：特殊区域禁排油类/生活污水；垃圾距最近陆地≥12 海里';
COMMENT ON COLUMN emission_record.status IS 'ACTIVE-最新有效版本；SUPERSEDED-被修正取代，仍保留可查';
COMMENT ON COLUMN emission_record.supersedes_record_no IS '修正引用的原记录号';

-- requestId 幂等表：request_id 唯一，指纹覆盖 requestId 与全部业务字段
CREATE TABLE IF NOT EXISTS emission_request (
    request_id   VARCHAR(96)  NOT NULL,
    fingerprint  VARCHAR(64)  NOT NULL,
    action       VARCHAR(16)  NOT NULL,
    record_no    VARCHAR(64)  NOT NULL,
    version      INT          NOT NULL,
    created_at   TIMESTAMP    NOT NULL,
    CONSTRAINT pk_emission_request PRIMARY KEY (request_id)
);
COMMENT ON COLUMN emission_request.fingerprint IS 'requestId 与全部业务字段的 SHA-256 指纹；同键异参返回 409';

CREATE TABLE IF NOT EXISTS inspection (
    inspection_no  VARCHAR(64)  NOT NULL,
    ship_id        VARCHAR(64)  NOT NULL,
    port_code      VARCHAR(64)  NOT NULL,
    inspect_date   DATE         NOT NULL,
    status         VARCHAR(16)  NOT NULL,
    review_of      VARCHAR(64)  NULL,
    issued_at      TIMESTAMP    NULL,
    created_at     TIMESTAMP    NOT NULL,
    CONSTRAINT pk_inspection PRIMARY KEY (inspection_no),
    CONSTRAINT ck_inspection_status CHECK (status IN ('OPEN', 'ISSUED')),
    CONSTRAINT fk_inspection_ship FOREIGN KEY (ship_id) REFERENCES ship (ship_id),
    CONSTRAINT fk_inspection_port FOREIGN KEY (port_code) REFERENCES port (port_code)
);
COMMENT ON COLUMN inspection.inspect_date IS '核查日（UTC 日期）；整改期限=核查日+14 个自然日，恰第 14 日关闭有效';
COMMENT ON COLUMN inspection.status IS 'OPEN-可逐条结论；ISSUED-已签发终态，结论不可修改';
COMMENT ON COLUMN inspection.review_of IS '复查引用的原核查号；签发后补充证据只能走复查';

CREATE TABLE IF NOT EXISTS inspection_item (
    id             BIGINT AUTO_INCREMENT NOT NULL,
    inspection_no  VARCHAR(64)  NOT NULL,
    record_no      VARCHAR(64)  NOT NULL,
    record_version INT          NOT NULL,
    conclusion     VARCHAR(16)  NOT NULL,
    evidence       VARCHAR(512) NULL,
    decided_at     TIMESTAMP    NOT NULL,
    CONSTRAINT pk_inspection_item PRIMARY KEY (id),
    CONSTRAINT uq_inspection_item UNIQUE (inspection_no, record_no),
    CONSTRAINT ck_item_conclusion CHECK (conclusion IN ('CONFIRMED', 'VIOLATION')),
    CONSTRAINT fk_item_inspection FOREIGN KEY (inspection_no) REFERENCES inspection (inspection_no)
);
COMMENT ON COLUMN inspection_item.record_version IS '结论时刻确认的排放记录版本；签发后迟到修正不影响该结论';

CREATE TABLE IF NOT EXISTS defect (
    id             BIGINT AUTO_INCREMENT NOT NULL,
    inspection_no  VARCHAR(64)  NOT NULL,
    item_id        BIGINT       NOT NULL,
    record_no      VARCHAR(64)  NOT NULL,
    record_version INT          NOT NULL,
    deadline       DATE         NOT NULL,
    status         VARCHAR(16)  NOT NULL,
    closed_at      TIMESTAMP    NULL,
    created_at     TIMESTAMP    NOT NULL,
    CONSTRAINT pk_defect PRIMARY KEY (id),
    CONSTRAINT uq_defect_item UNIQUE (item_id),
    CONSTRAINT ck_defect_status CHECK (status IN ('OPEN', 'CLOSED', 'OVERDUE')),
    CONSTRAINT fk_defect_inspection FOREIGN KEY (inspection_no) REFERENCES inspection (inspection_no),
    CONSTRAINT fk_defect_item FOREIGN KEY (item_id) REFERENCES inspection_item (id)
);
COMMENT ON COLUMN defect.deadline IS '整改截止日（UTC）=核查日+14，含当日；超过则不可按期关闭';
COMMENT ON COLUMN defect.status IS 'OPEN-待整改；CLOSED-已按期关闭；OVERDUE-逾期未关闭';
