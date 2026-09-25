-- 船舶防污染记录服务建表脚本（H2 MySQL 兼容模式；MySQL 下语法同样兼容）。
-- 仅使用合成数据；排放记录只做版本化标记，不做物理删除。

CREATE TABLE IF NOT EXISTS ship (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    imo        VARCHAR(32)  NOT NULL COMMENT '船舶 IMO 编号（合成）',
    name       VARCHAR(128) NOT NULL COMMENT '船名（合成）',
    created_at TIMESTAMP    NOT NULL COMMENT '创建时刻（UTC）',
    CONSTRAINT uk_ship_imo UNIQUE (imo)
);

CREATE TABLE IF NOT EXISTS voyage (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    ship_id    BIGINT       NOT NULL COMMENT '所属船舶 id',
    voyage_no  VARCHAR(64)  NOT NULL COMMENT '航次号（合成）',
    status     VARCHAR(16)  NOT NULL COMMENT '航次状态：OPEN=开放可记录，CLOSED=已关闭不可新增',
    closed_at  TIMESTAMP    NULL     COMMENT '关闭时刻（UTC），空表示未关闭',
    created_at TIMESTAMP    NOT NULL COMMENT '创建时刻（UTC）',
    CONSTRAINT uk_voyage_ship_no UNIQUE (ship_id, voyage_no)
);

-- 特殊区域为合成多边形；环点格式 [经度,纬度]，闭合由算法隐式处理。
CREATE TABLE IF NOT EXISTS special_area (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    name         VARCHAR(128)  NOT NULL COMMENT '特殊区域名称（合成）',
    polygon_json VARCHAR(8192) NOT NULL COMMENT '多边形环点 JSON：[[lon,lat],...]',
    created_at   TIMESTAMP     NOT NULL COMMENT '创建时刻（UTC）'
);

-- “最近陆地”以合成陆地点集合近似：距离取排放点到所有陆地点的大圆距离最小值（海里）。
CREATE TABLE IF NOT EXISTS land_mark (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(128) NOT NULL COMMENT '陆地参照点名称（合成）',
    longitude  DOUBLE       NOT NULL COMMENT '经度（度）',
    latitude   DOUBLE       NOT NULL COMMENT '纬度（度）',
    created_at TIMESTAMP    NOT NULL COMMENT '创建时刻（UTC）'
);

-- 排放事件记录；修正以新版本行体现，原版本保留。
CREATE TABLE IF NOT EXISTS emission_record (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id     VARCHAR(64)  NOT NULL COMMENT '幂等键；同键同参返回首条，同键异参 409',
    root_id        BIGINT       NULL     COMMENT '版本族根记录 id；首版本入库后立即回填为自身 id',
    version_no     INT          NOT NULL COMMENT '版本号，从 1 起；同 root 并发修正唯一',
    supersedes_id  BIGINT       NULL     COMMENT '本版本直接修正的上一版本 id，首版本为空',
    ship_id        BIGINT       NOT NULL COMMENT '船舶 id',
    voyage_id      BIGINT       NOT NULL COMMENT '航次 id',
    record_type    VARCHAR(16)  NOT NULL COMMENT '排放类型：OIL=油类，GARBAGE=垃圾，SEWAGE=生活污水',
    longitude      DOUBLE       NOT NULL COMMENT '排放点经度（度）',
    latitude       DOUBLE       NOT NULL COMMENT '排放点纬度（度）',
    event_time     TIMESTAMP    NOT NULL COMMENT '排放时刻（UTC）',
    quantity       DECIMAL(14,3) NOT NULL COMMENT '排放数量（单位见 unit）',
    unit           VARCHAR(36)  NOT NULL COMMENT '数量单位，如 TON / CBM',
    status         VARCHAR(16)  NOT NULL COMMENT '版本状态：ACTIVE=最新有效，SUPERSEDED=已被新版本取代',
    fingerprint    CHAR(64)     NOT NULL COMMENT 'requestId+全部业务字段的 SHA-256 指纹',
    created_at     TIMESTAMP    NOT NULL COMMENT '提交时刻（UTC）',
    CONSTRAINT uk_emission_request     UNIQUE (request_id),
    CONSTRAINT uk_emission_root_version UNIQUE (root_id, version_no)
);

CREATE TABLE IF NOT EXISTS inspection (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    inspection_no   VARCHAR(40) NOT NULL COMMENT '核查号（业务键，复查引用）',
    ship_id         BIGINT      NOT NULL COMMENT '被核查船舶 id（按船舶发起）',
    officer         VARCHAR(128) NOT NULL COMMENT '核查官（合成）',
    status          VARCHAR(16) NOT NULL COMMENT '核查状态：OPEN=进行中，ISSUED=已签发终态',
    inspection_date DATE        NOT NULL COMMENT '核查日（UTC 日界），整改期限=该日+14 自然日',
    issued_at       TIMESTAMP   NULL     COMMENT '签发时刻（UTC），空表示未签发',
    created_at      TIMESTAMP   NOT NULL COMMENT '创建时刻（UTC）',
    CONSTRAINT uk_inspection_no UNIQUE (inspection_no)
);

-- 核查条目：每条针对一个版本族，结论在标注/确认时刻钉住所依据的具体版本。
CREATE TABLE IF NOT EXISTS inspection_item (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    inspection_id   BIGINT       NOT NULL COMMENT '所属核查 id',
    root_record_id  BIGINT       NOT NULL COMMENT '排放记录版本族根 id',
    pinned_record_id BIGINT     NOT NULL COMMENT '结论所依据（钉住）的具体版本行 id',
    pinned_version_no INT        NOT NULL COMMENT '结论所依据版本号',
    decision        VARCHAR(16)  NOT NULL COMMENT '结论：CONFIRMED=确认，VIOLATION=标注违规',
    reason          VARCHAR(1024) NULL    COMMENT '核查说明/违规理由',
    status          VARCHAR(16)  NULL     COMMENT '整改状态：OPEN=待整改，RECTIFIED=已按期整改关闭，OVERDUE=逾期；CONFIRMED 条目为空',
    due_date        DATE         NULL     COMMENT '整改截止日（UTC）=核查日+14 自然日，恰当日有效',
    rectified_at    TIMESTAMP    NULL     COMMENT '整改关闭时刻（UTC），空表示未关闭',
    created_at      TIMESTAMP    NOT NULL COMMENT '创建时刻（UTC）',
    updated_at      TIMESTAMP    NOT NULL COMMENT '最近更新时刻（UTC）',
    CONSTRAINT uk_inspection_item UNIQUE (inspection_id, root_record_id)
);

-- 签发后补充证据走复查，引用原核查号，不修改原结论。
CREATE TABLE IF NOT EXISTS inspection_review (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    inspection_id BIGINT       NOT NULL COMMENT '原核查 id',
    inspection_no VARCHAR(40)  NOT NULL COMMENT '原核查号（复查引用）',
    item_id       BIGINT       NULL     COMMENT '关联缺陷/条目 id，可空表示对整份核查补充',
    evidence      VARCHAR(2048) NOT NULL COMMENT '补充证据说明',
    created_at    TIMESTAMP     NOT NULL COMMENT '复查提交时刻（UTC）'
);
