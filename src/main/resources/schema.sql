-- 治超执法服务建表脚本（H2，MODE=MySQL）。
-- 重量单位统一为千克（kg）；金额单位为元；时间统一为 UTC 时间戳。

CREATE SEQUENCE IF NOT EXISTS seq_case_no START WITH 1;
CREATE SEQUENCE IF NOT EXISTS seq_doc_no START WITH 1;

CREATE TABLE IF NOT EXISTS axle_limit (
    axle_type     VARCHAR(32)  NOT NULL COMMENT '轴型编码（如 AXLE_2/AXLE_4/AXLE_6）',
    limit_kg      DECIMAL(12,2) NOT NULL COMMENT '该轴型车货总重限值，单位千克',
    PRIMARY KEY (axle_type)
) COMMENT '轴型限值表（合成数据）';

CREATE TABLE IF NOT EXISTS station (
    station_code  VARCHAR(32)  NOT NULL COMMENT '检测站编码',
    station_name  VARCHAR(128) NOT NULL COMMENT '检测站名称',
    PRIMARY KEY (station_code)
) COMMENT '检测站表（合成数据）';

CREATE TABLE IF NOT EXISTS vehicle (
    plate_no      VARCHAR(16)  NOT NULL COMMENT '车牌号',
    axle_type     VARCHAR(32)  NOT NULL COMMENT '车辆轴型编码，关联 axle_limit',
    PRIMARY KEY (plate_no)
) COMMENT '车辆档案表（合成数据）';

CREATE TABLE IF NOT EXISTS weigh_record (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    request_id     VARCHAR(64)  NOT NULL COMMENT '幂等请求标识',
    fingerprint    VARCHAR(64)  NOT NULL COMMENT '幂等指纹：requestId 与全部业务字段的 SHA-256',
    plate_no       VARCHAR(16)  NOT NULL COMMENT '车牌号',
    station_code   VARCHAR(32)  NOT NULL COMMENT '检测站编码',
    weigh_time     TIMESTAMP    NOT NULL COMMENT '过磅时刻（UTC）',
    mode           VARCHAR(16)  NOT NULL COMMENT '称重方式：DYNAMIC 动态 / STATIC 静态复检',
    total_kg       DECIMAL(12,2) NOT NULL COMMENT '实测总重，单位千克',
    axle_type      VARCHAR(32)  NOT NULL COMMENT '轴型编码',
    limit_kg       DECIMAL(12,2) NOT NULL COMMENT '判定使用的限值，单位千克',
    overload_rate  DECIMAL(8,2)  NOT NULL COMMENT '超限率（两位小数，如 0.30 表示 30%）',
    tier           VARCHAR(16)  NOT NULL COMMENT '分档：NO_OVERLOAD/UNLOAD_RELEASE/FINE_POINTS',
    role           VARCHAR(16)  NOT NULL COMMENT '记录角色：AUTHORITATIVE/SUPERSEDED/LATE_EVIDENCE',
    case_id        BIGINT       NULL COMMENT '关联案件主键；未立案或迟到数据为 NULL',
    created_at     TIMESTAMP    NOT NULL COMMENT '记录创建时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_weigh_request (request_id),
    UNIQUE KEY uk_weigh_dedup (plate_no, station_code, weigh_time, mode)
) COMMENT '过磅检测记录表；同车牌+检测站+过磅时刻+方式去重';

CREATE TABLE IF NOT EXISTS enforcement_case (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    case_no        VARCHAR(32)  NOT NULL COMMENT '案件编号',
    plate_no       VARCHAR(16)  NOT NULL COMMENT '车牌号',
    station_code   VARCHAR(32)  NOT NULL COMMENT '检测站编码',
    weigh_time     TIMESTAMP    NOT NULL COMMENT '过磅时刻（UTC），与车牌、检测站共同定位案件',
    status         VARCHAR(16)  NOT NULL COMMENT '案件状态：OPEN/CLOSED（终态）',
    closed_at      TIMESTAMP    NULL COMMENT '结案时间（UTC）；未结案为 NULL',
    close_request_id VARCHAR(64) NULL COMMENT '结案幂等请求标识；未结案为 NULL',
    close_fingerprint VARCHAR(64) NULL COMMENT '结案幂等指纹（requestId+案件编号）；未结案为 NULL',
    created_at     TIMESTAMP    NOT NULL COMMENT '立案时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_case_no (case_no),
    UNIQUE KEY uk_case_biz (plate_no, station_code, weigh_time),
    UNIQUE KEY uk_case_close_req (close_request_id)
) COMMENT '治超案件表；结案为终态';

CREATE TABLE IF NOT EXISTS decision_doc (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    request_id     VARCHAR(64)  NOT NULL COMMENT '幂等请求标识',
    fingerprint    VARCHAR(64)  NOT NULL COMMENT '幂等指纹：requestId 与全部业务字段的 SHA-256',
    doc_no         VARCHAR(32)  NOT NULL COMMENT '文书编号',
    case_id        BIGINT       NOT NULL COMMENT '关联案件主键',
    doc_type       VARCHAR(16)  NOT NULL COMMENT '文书类型：UNLOAD_RELEASE/PENALTY/REVOCATION/NO_ACTION',
    tier           VARCHAR(16)  NOT NULL COMMENT '分档口径快照',
    total_kg       DECIMAL(12,2) NOT NULL COMMENT '判定总重快照，单位千克',
    limit_kg       DECIMAL(12,2) NOT NULL COMMENT '判定限值快照，单位千克',
    overload_rate  DECIMAL(8,2)  NOT NULL COMMENT '超限率快照（两位小数）',
    fine_amount    DECIMAL(12,2) NOT NULL COMMENT '罚款金额（元）；无罚款为 0',
    points         INT          NOT NULL COMMENT '记分数；无记分为 0',
    status         VARCHAR(16)  NOT NULL COMMENT '决定状态：ACTIVE/REVOKED',
    served_at      TIMESTAMP    NOT NULL COMMENT '送达时间（UTC），复检 7 日期限起算点',
    revoked_at     TIMESTAMP    NULL COMMENT '撤销时间（UTC）；未撤销为 NULL',
    created_at     TIMESTAMP    NOT NULL COMMENT '文书生成时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_doc_request (request_id),
    UNIQUE KEY uk_doc_no (doc_no)
) COMMENT '处罚决定/处理文书表';

CREATE TABLE IF NOT EXISTS review_request (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    request_id     VARCHAR(64)  NOT NULL COMMENT '幂等请求标识',
    fingerprint    VARCHAR(64)  NOT NULL COMMENT '幂等指纹：requestId 与全部业务字段的 SHA-256',
    case_id        BIGINT       NOT NULL COMMENT '关联案件主键',
    decision_id    BIGINT       NOT NULL COMMENT '被申请复检的处罚决定主键',
    reason         VARCHAR(512) NOT NULL COMMENT '申诉理由',
    status         VARCHAR(16)  NOT NULL COMMENT '复检状态：UNDER_REVIEW/UPHELD/OVERTURNED',
    applied_at     TIMESTAMP    NOT NULL COMMENT '申请提交时间（UTC）',
    concluded_at   TIMESTAMP    NULL COMMENT '复检结论时间（UTC）；未结论为 NULL',
    conclude_request_id VARCHAR(64) NULL COMMENT '复检结论幂等请求标识；未结论为 NULL',
    conclude_fingerprint VARCHAR(64) NULL COMMENT '复检结论幂等指纹；未结论为 NULL',
    recheck_total_kg DECIMAL(12,2) NULL COMMENT '复检实测总重快照，单位千克；未结论为 NULL',
    PRIMARY KEY (id),
    UNIQUE KEY uk_review_request (request_id),
    UNIQUE KEY uk_review_case (case_id),
    UNIQUE KEY uk_review_conclude_request (conclude_request_id)
) COMMENT '复检申诉表；同一案件仅受理一次（uk_review_case 保证并发唯一）';

CREATE TABLE IF NOT EXISTS points_ledger (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    plate_no       VARCHAR(16)  NOT NULL COMMENT '车牌号',
    case_id        BIGINT       NOT NULL COMMENT '关联案件主键',
    decision_id    BIGINT       NOT NULL COMMENT '关联处罚决定主键',
    delta          INT          NOT NULL COMMENT '记分变动：正数为记分，负数为退还',
    reason         VARCHAR(32)  NOT NULL COMMENT '变动原因：PENALTY 记分 / REVOCATION_REFUND 退还',
    created_at     TIMESTAMP    NOT NULL COMMENT '入账时间（UTC）',
    PRIMARY KEY (id)
) COMMENT '驾驶员记分台账';

CREATE TABLE IF NOT EXISTS fine_ledger (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    plate_no       VARCHAR(16)  NOT NULL COMMENT '车牌号',
    case_id        BIGINT       NOT NULL COMMENT '关联案件主键',
    decision_id    BIGINT       NOT NULL COMMENT '关联处罚决定主键',
    amount         DECIMAL(12,2) NOT NULL COMMENT '罚款变动（元）：正数为处罚，负数为退还',
    reason         VARCHAR(32)  NOT NULL COMMENT '变动原因：PENALTY 处罚 / REVOCATION_REFUND 退还',
    created_at     TIMESTAMP    NOT NULL COMMENT '入账时间（UTC）',
    PRIMARY KEY (id)
) COMMENT '罚款台账';

CREATE TABLE IF NOT EXISTS offsite_device (
    device_id        VARCHAR(32)  NOT NULL COMMENT '非现场称重设备编号',
    lane_no          VARCHAR(16)  NOT NULL COMMENT '车道号',
    status           VARCHAR(16)  NOT NULL COMMENT '设备状态：NORMAL/FAULT',
    calibrated_from  TIMESTAMP    NOT NULL COMMENT '校准有效期起（UTC，含边界）',
    calibrated_until TIMESTAMP    NOT NULL COMMENT '校准有效期止（UTC，含边界）',
    recorded_at      TIMESTAMP    NOT NULL COMMENT '设备状态记录时间（UTC）',
    PRIMARY KEY (device_id)
) COMMENT '非现场称重设备状态记录表；校准有效期外数据不得进入证据链';

CREATE TABLE IF NOT EXISTS offsite_weigh (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    request_id     VARCHAR(64)  NOT NULL COMMENT '幂等请求标识',
    fingerprint    VARCHAR(64)  NOT NULL COMMENT '幂等指纹：requestId 与全部业务字段的 SHA-256',
    plate_no       VARCHAR(16)  NOT NULL COMMENT '车牌号',
    lane_no        VARCHAR(16)  NOT NULL COMMENT '车道号',
    weigh_time     TIMESTAMP    NOT NULL COMMENT '过磅时刻（UTC）',
    total_kg       DECIMAL(12,2) NOT NULL COMMENT '实测总重，单位千克',
    image_ref      VARCHAR(128) NULL COMMENT '合成影像标识；缺失为 NULL（证据链缺项）',
    device_id      VARCHAR(32)  NOT NULL COMMENT '称重设备编号，关联 offsite_device',
    status         VARCHAR(16)  NOT NULL COMMENT '接入状态：AUTO_DECIDED 已自动决定 / MANUAL_PENDING 人工待补 / LATE_EVENT 迟到事件',
    case_id        BIGINT       NULL COMMENT '关联案件主键；人工待补未立案为 NULL',
    decision_id    BIGINT       NULL COMMENT '自动生成的处罚决定主键；未生成为 NULL',
    created_at     TIMESTAMP    NOT NULL COMMENT '接入时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_offsite_request (request_id)
) COMMENT '非现场动态称重接入表；按车牌+时刻窗口与现场记录去重，窗口内只保留首次';

CREATE TABLE IF NOT EXISTS manual_pending (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    offsite_weigh_id BIGINT       NOT NULL COMMENT '非现场称重记录主键',
    plate_no         VARCHAR(16)  NOT NULL COMMENT '车牌号',
    lane_no          VARCHAR(16)  NOT NULL COMMENT '车道号',
    weigh_time       TIMESTAMP    NOT NULL COMMENT '过磅时刻（UTC）',
    reason           VARCHAR(32)  NOT NULL COMMENT '待补原因：MISSING_IMAGE 缺影像 / DEVICE_STATUS_MISSING 缺设备状态 / DEVICE_CAL_EXPIRED 校准有效期外',
    status           VARCHAR(16)  NOT NULL COMMENT '处理状态：PENDING 待补',
    created_at       TIMESTAMP    NOT NULL COMMENT '转人工时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_pending_offsite (offsite_weigh_id)
) COMMENT '证据链缺项人工待补表；缺项数据不得自动生成处罚决定';

CREATE TABLE IF NOT EXISTS late_offsite_event (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    offsite_weigh_id BIGINT       NOT NULL COMMENT '非现场称重记录主键',
    case_id          BIGINT       NOT NULL COMMENT '已结案件主键',
    plate_no         VARCHAR(16)  NOT NULL COMMENT '车牌号',
    lane_no          VARCHAR(16)  NOT NULL COMMENT '车道号',
    weigh_time       TIMESTAMP    NOT NULL COMMENT '过磅时刻（UTC）',
    note             VARCHAR(256) NOT NULL COMMENT '迟到事件说明',
    created_at       TIMESTAMP    NOT NULL COMMENT '事件记录时间（UTC）',
    PRIMARY KEY (id)
) COMMENT '结案后迟到非现场数据事件表；只记迟到事件，不改已结案件';

CREATE TABLE IF NOT EXISTS late_weigh_event (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    weigh_record_id BIGINT      NOT NULL COMMENT '迟到过磅记录主键',
    case_id        BIGINT       NOT NULL COMMENT '已结案件主键',
    plate_no       VARCHAR(16)  NOT NULL COMMENT '车牌号',
    station_code   VARCHAR(32)  NOT NULL COMMENT '检测站编码',
    weigh_time     TIMESTAMP    NOT NULL COMMENT '过磅时刻（UTC）',
    mode           VARCHAR(16)  NOT NULL COMMENT '称重方式',
    total_kg       DECIMAL(12,2) NOT NULL COMMENT '实测总重，单位千克',
    note           VARCHAR(256) NOT NULL COMMENT '迟到事件说明',
    created_at     TIMESTAMP    NOT NULL COMMENT '事件记录时间（UTC）',
    PRIMARY KEY (id)
) COMMENT '结案后迟到过磅事件表；仅入证据备查，不改判已结案件';
