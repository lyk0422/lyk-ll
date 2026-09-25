-- 文物借展与展柜微环境监测：H2 (MODE=MySQL) 建表脚本
-- 仅用于嵌入式内存库，SQL 方言按 H2 验证，不声明与 MySQL 完全兼容。

CREATE SEQUENCE IF NOT EXISTS domain_seq START WITH 1;

CREATE TABLE artifact (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    artifact_no VARCHAR(64)  NOT NULL,
    name        VARCHAR(128) NOT NULL,
    grade       INT          NOT NULL,
    material    VARCHAR(64)  NOT NULL,
    created_at  TIMESTAMP    NOT NULL
);
CREATE UNIQUE INDEX uk_artifact_no ON artifact (artifact_no);
COMMENT ON TABLE artifact IS '文物目录（合成数据）';
COMMENT ON COLUMN artifact.grade IS '文物等级：1=一级，2=二级，3=三级';
COMMENT ON COLUMN artifact.material IS '材质代码：BRONZE/SILK/CERAMIC 等';

CREATE TABLE hall (
    id      BIGINT AUTO_INCREMENT PRIMARY KEY,
    hall_no VARCHAR(64)  NOT NULL,
    name    VARCHAR(128) NOT NULL
);
CREATE UNIQUE INDEX uk_hall_no ON hall (hall_no);
COMMENT ON TABLE hall IS '展馆（合成数据）';

CREATE TABLE showcase (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    showcase_no   VARCHAR(64)    NOT NULL,
    hall_id       BIGINT         NOT NULL,
    material      VARCHAR(64)    NOT NULL,
    temp_min      DECIMAL(6,2)   NOT NULL,
    temp_max      DECIMAL(6,2)   NOT NULL,
    hum_min       DECIMAL(6,2)   NOT NULL,
    hum_max       DECIMAL(6,2)   NOT NULL,
    frozen        BOOLEAN        NOT NULL DEFAULT FALSE,
    frozen_reason VARCHAR(255),
    created_at    TIMESTAMP      NOT NULL
);
CREATE UNIQUE INDEX uk_showcase_no ON showcase (showcase_no);
COMMENT ON TABLE showcase IS '展柜及其按材质设定的温湿度区间（合成参数）';
COMMENT ON COLUMN showcase.temp_min IS '温度下限，单位摄氏度';
COMMENT ON COLUMN showcase.temp_max IS '温度上限，单位摄氏度';
COMMENT ON COLUMN showcase.hum_min IS '相对湿度下限，单位%RH';
COMMENT ON COLUMN showcase.hum_max IS '相对湿度上限，单位%RH';
COMMENT ON COLUMN showcase.frozen IS '是否已冻结新入藏：紧急工单未处置时为 TRUE';

CREATE TABLE loan_contract (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    contract_no     VARCHAR(64)  NOT NULL,
    artifact_id     BIGINT       NOT NULL,
    borrower        VARCHAR(128) NOT NULL,
    start_date      DATE         NOT NULL,
    end_date        DATE         NOT NULL,
    original_days   INT          NOT NULL,
    status          VARCHAR(24)  NOT NULL,
    showcase_id     BIGINT,
    renew_count     INT          NOT NULL DEFAULT 0,
    suspended_at    TIMESTAMP,
    resumed_at      TIMESTAMP,
    returned_at     TIMESTAMP,
    closed_at       TIMESTAMP,
    version         INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL
);
CREATE UNIQUE INDEX uk_contract_no ON loan_contract (contract_no);
CREATE INDEX idx_contract_artifact ON loan_contract (artifact_id);
COMMENT ON TABLE loan_contract IS '借展合同：APPLIED→IN_EXHIBITION(SUSPENDED)→RETURNED→CLOSED';
COMMENT ON COLUMN loan_contract.status IS '状态：APPLIED 已受理/IN_EXHIBITION 在展/SUSPENDED 异常挂起/RETURNED 已归还点交/CLOSED 已结案';
COMMENT ON COLUMN loan_contract.end_date IS '当前届满日（UTC 日界），续展后后移';
COMMENT ON COLUMN loan_contract.original_days IS '原始借展期限天数，用于限制续展不超过原期限一半';
COMMENT ON COLUMN loan_contract.renew_count IS '已续展次数，最多 1 次';

-- 同一文物同一时刻只允许一个有效合同：受理时插入，结案时删除，主键冲突即并发冲突
CREATE TABLE artifact_active_loan (
    artifact_id BIGINT NOT NULL,
    contract_id BIGINT NOT NULL,
    created_at  TIMESTAMP NOT NULL
);
ALTER TABLE artifact_active_loan ADD CONSTRAINT pk_artifact_active_loan PRIMARY KEY (artifact_id);
COMMENT ON TABLE artifact_active_loan IS '文物有效借展占用表：一行代表一份未结案合同';

CREATE TABLE handover (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    contract_id        BIGINT      NOT NULL,
    type               VARCHAR(16) NOT NULL,
    lender_confirmed   BOOLEAN     NOT NULL DEFAULT FALSE,
    borrower_confirmed BOOLEAN     NOT NULL DEFAULT FALSE,
    completed_at       TIMESTAMP,
    created_at         TIMESTAMP    NOT NULL,
    updated_at         TIMESTAMP    NOT NULL
);
CREATE UNIQUE INDEX uk_handover_contract_type ON handover (contract_id, type);
COMMENT ON TABLE handover IS '点交单：OUTBORED=出库点交，RETURN=归还点交；须双方确认，单方不生效';
COMMENT ON COLUMN handover.completed_at IS '双方均确认时刻；任一方未确认时为 NULL';

CREATE TABLE environment_reading (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    showcase_id  BIGINT     NOT NULL,
    sample_time  TIMESTAMP  NOT NULL,
    temperature  DECIMAL(6,2) NOT NULL,
    humidity     DECIMAL(6,2) NOT NULL,
    received_at  TIMESTAMP  NOT NULL,
    late         BOOLEAN    NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX uk_reading_showcase_time ON environment_reading (showcase_id, sample_time);
COMMENT ON TABLE environment_reading IS '温湿度读数：按展柜+采样时刻去重，乱序按采样时刻归位';
COMMENT ON COLUMN environment_reading.temperature IS '温度，单位摄氏度';
COMMENT ON COLUMN environment_reading.humidity IS '相对湿度，单位%RH';
COMMENT ON COLUMN environment_reading.late IS '是否为结案后迟到读数：TRUE 时仅记迟到事件，不参与判异';

CREATE TABLE environment_alert (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    alert_no           VARCHAR(64) NOT NULL,
    showcase_id        BIGINT      NOT NULL,
    contract_id        BIGINT,
    trigger_reading_id BIGINT,
    trigger_time       TIMESTAMP   NOT NULL,
    status             VARCHAR(20) NOT NULL,
    created_at         TIMESTAMP   NOT NULL,
    escalated_at       TIMESTAMP,
    recovered_at       TIMESTAMP,
    resolved_at        TIMESTAMP
);
CREATE UNIQUE INDEX uk_alert_no ON environment_alert (alert_no);
CREATE INDEX idx_alert_showcase_status ON environment_alert (showcase_id, status);
COMMENT ON TABLE environment_alert IS '环境异常：连续 3 个采样点越限触发';
COMMENT ON COLUMN environment_alert.status IS 'OPEN 待处置/URGENT 满24小时升级紧急/AWAIT_REVIEW 环境恢复待双方复核/RESOLVED 已复核恢复';

CREATE TABLE protection_work_order (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no      VARCHAR(64)  NOT NULL,
    alert_id      BIGINT       NOT NULL,
    showcase_id   BIGINT       NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    deadline      TIMESTAMP    NOT NULL,
    confirmed_at  TIMESTAMP,
    handler       VARCHAR(128),
    note          VARCHAR(512),
    created_at    TIMESTAMP    NOT NULL
);
CREATE UNIQUE INDEX uk_order_no ON protection_work_order (order_no);
CREATE UNIQUE INDEX uk_order_alert ON protection_work_order (alert_id);
COMMENT ON TABLE protection_work_order IS '保护工单：异常自动生成，要求 24 小时内处置确认，逾期升级紧急并冻结展柜';
COMMENT ON COLUMN protection_work_order.deadline IS '处置确认时限=触发时刻+24小时（UTC）';

CREATE TABLE resume_review (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    contract_id        BIGINT   NOT NULL,
    alert_id           BIGINT   NOT NULL,
    lender_confirmed   BOOLEAN  NOT NULL DEFAULT FALSE,
    borrower_confirmed BOOLEAN  NOT NULL DEFAULT FALSE,
    completed_at       TIMESTAMP,
    created_at         TIMESTAMP NOT NULL,
    updated_at         TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX uk_review_contract_alert ON resume_review (contract_id, alert_id);
COMMENT ON TABLE resume_review IS '挂起恢复复核单：须借出借入双方复核，单方不生效';

CREATE TABLE loan_event (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    contract_id BIGINT,
    showcase_id BIGINT,
    type        VARCHAR(32)  NOT NULL,
    detail      VARCHAR(1024),
    created_at  TIMESTAMP    NOT NULL
);
CREATE INDEX idx_event_contract ON loan_event (contract_id);
COMMENT ON TABLE loan_event IS '借展/监测领域事件流，供历史与轨迹复算';

CREATE TABLE idempotent_request (
    request_id    VARCHAR(64)  NOT NULL,
    fingerprint   VARCHAR(64)  NOT NULL,
    http_status   INT          NOT NULL,
    response_json CLOB,
    created_at    TIMESTAMP    NOT NULL
);
ALTER TABLE idempotent_request ADD CONSTRAINT pk_idempotent_request PRIMARY KEY (request_id);
COMMENT ON TABLE idempotent_request IS 'requestId 幂等记录：指纹覆盖 requestId 与全部业务字段';
