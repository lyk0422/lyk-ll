-- 放射性药物活度校准与分装核对服务建表脚本（H2 MySQL 兼容模式）
-- 所有时间字段均为 UTC 时间戳；活度单位一律为 MBq。

CREATE TABLE IF NOT EXISTS nuclide (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(64) NOT NULL UNIQUE,
    half_life_seconds BIGINT NOT NULL
);
COMMENT ON TABLE nuclide IS '合成核素表：半衰期参数唯一来源';
COMMENT ON COLUMN nuclide.name IS '核素名称（合成数据）';
COMMENT ON COLUMN nuclide.half_life_seconds IS '半衰期，单位秒';

CREATE TABLE IF NOT EXISTS dose_calibrator (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL
);
COMMENT ON TABLE dose_calibrator IS '活度计台账（合成数据）';
COMMENT ON COLUMN dose_calibrator.code IS '活度计编号，唯一';
COMMENT ON COLUMN dose_calibrator.name IS '活度计名称';

CREATE TABLE IF NOT EXISTS reference_source (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    calibrator_id BIGINT NOT NULL,
    nuclide_id BIGINT NOT NULL,
    activity_mbq DOUBLE NOT NULL,
    measured_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_ref_cal FOREIGN KEY (calibrator_id) REFERENCES dose_calibrator (id),
    CONSTRAINT fk_ref_nuc FOREIGN KEY (nuclide_id) REFERENCES nuclide (id)
);
COMMENT ON TABLE reference_source IS '日检参考源（合成数据）';
COMMENT ON COLUMN reference_source.activity_mbq IS '参考源标定活度，单位 MBq';
COMMENT ON COLUMN reference_source.measured_at IS '标定时刻（UTC）';

CREATE TABLE IF NOT EXISTS mother_liquor (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    nuclide_id BIGINT NOT NULL,
    initial_activity_mbq DOUBLE NOT NULL,
    measured_at TIMESTAMP NOT NULL,
    remaining_activity_mbq DOUBLE NOT NULL,
    remaining_measured_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_ml_nuc FOREIGN KEY (nuclide_id) REFERENCES nuclide (id)
);
COMMENT ON TABLE mother_liquor IS '母液登记与活度库存';
COMMENT ON COLUMN mother_liquor.initial_activity_mbq IS '母液初始活度，单位 MBq';
COMMENT ON COLUMN mother_liquor.measured_at IS '初始活度测量时刻（UTC）';
COMMENT ON COLUMN mother_liquor.remaining_activity_mbq IS '当前剩余活度（发药扣减后），单位 MBq';
COMMENT ON COLUMN mother_liquor.remaining_measured_at IS '剩余活度对应测量时刻（UTC）';

CREATE TABLE IF NOT EXISTS daily_check (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL UNIQUE,
    calibrator_id BIGINT NOT NULL,
    check_date DATE NOT NULL,
    expected_activity_mbq DOUBLE NOT NULL,
    measured_activity_mbq DOUBLE NOT NULL,
    deviation DOUBLE NOT NULL,
    passed BOOLEAN NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_dc_cal FOREIGN KEY (calibrator_id) REFERENCES dose_calibrator (id),
    CONSTRAINT uk_dc_cal_date UNIQUE (calibrator_id, check_date)
);
COMMENT ON TABLE daily_check IS '活度计日检记录；同一活度计同一自然日（UTC）唯一受理';
COMMENT ON COLUMN daily_check.request_id IS '幂等请求键';
COMMENT ON COLUMN daily_check.check_date IS '日检日期（UTC 自然日）';
COMMENT ON COLUMN daily_check.expected_activity_mbq IS '参考源衰变推算至日检时刻的期望活度，单位 MBq';
COMMENT ON COLUMN daily_check.deviation IS '相对偏差 (实测-期望)/期望；绝对值>5% 当日禁用，恰 5% 合法';
COMMENT ON COLUMN daily_check.passed IS '日检是否通过';
COMMENT ON COLUMN daily_check.fingerprint IS '幂等指纹：requestId 与全部业务字段的 SHA-256';

CREATE TABLE IF NOT EXISTS dispense (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL UNIQUE,
    mother_liquor_id BIGINT NOT NULL,
    prescription_activity_mbq DOUBLE NOT NULL,
    calculated_activity_mbq DOUBLE NOT NULL,
    administer_at TIMESTAMP NOT NULL,
    deviation DOUBLE NOT NULL,
    status VARCHAR(16) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_dp_ml FOREIGN KEY (mother_liquor_id) REFERENCES mother_liquor (id)
);
COMMENT ON TABLE dispense IS '分装批次；状态 PENDING_CHECK/FIRST_CHECKED/CHECKED/DELIVERED/REJECTED，DELIVERED 与 REJECTED 为终态';
COMMENT ON COLUMN dispense.request_id IS '幂等请求键';
COMMENT ON COLUMN dispense.prescription_activity_mbq IS '处方活度，单位 MBq';
COMMENT ON COLUMN dispense.calculated_activity_mbq IS '按衰变公式推算至给药时刻的活度，单位 MBq';
COMMENT ON COLUMN dispense.administer_at IS '给药时刻（UTC）';
COMMENT ON COLUMN dispense.deviation IS '相对偏差 (推算-处方)/处方；绝对值>10% 拒绝分装，恰 10% 合法';

CREATE TABLE IF NOT EXISTS dispense_check (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL UNIQUE,
    dispense_id BIGINT NOT NULL,
    checker_id VARCHAR(64) NOT NULL,
    seq INT NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_dck_dp FOREIGN KEY (dispense_id) REFERENCES dispense (id)
);
COMMENT ON TABLE dispense_check IS '分装双人核对链；须两名不同人员先后核对，单人不生效';
COMMENT ON COLUMN dispense_check.checker_id IS '核对人员标识';
COMMENT ON COLUMN dispense_check.seq IS '核对次序：1 首核，2 复核';

CREATE TABLE IF NOT EXISTS delivery (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL UNIQUE,
    dispense_id BIGINT NOT NULL UNIQUE,
    remaining_activity_mbq DOUBLE NOT NULL,
    remaining_measured_at TIMESTAMP NOT NULL,
    initial_at_ref_mbq DOUBLE NOT NULL,
    dispensed_at_ref_mbq DOUBLE NOT NULL,
    decay_residual_mbq DOUBLE NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_dv_dp FOREIGN KEY (dispense_id) REFERENCES dispense (id)
);
COMMENT ON TABLE delivery IS '发药记录；与母液活度扣减同事务完成';
COMMENT ON COLUMN delivery.remaining_activity_mbq IS '发药时实测剩余活度，单位 MBq';
COMMENT ON COLUMN delivery.remaining_measured_at IS '剩余活度测量时刻（UTC），勾稽基准时刻';
COMMENT ON COLUMN delivery.initial_at_ref_mbq IS '母液初始活度衰变推算至基准时刻，单位 MBq';
COMMENT ON COLUMN delivery.dispensed_at_ref_mbq IS '分装总量衰变推算至基准时刻，单位 MBq';
COMMENT ON COLUMN delivery.decay_residual_mbq IS '衰变残量=初始-分装-剩余（均推算至基准时刻），单位 MBq';

CREATE TABLE IF NOT EXISTS ledger (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    mother_liquor_id BIGINT NOT NULL,
    dispense_id BIGINT NOT NULL,
    delivery_id BIGINT NOT NULL,
    initial_activity_mbq DOUBLE NOT NULL,
    dispensed_activity_mbq DOUBLE NOT NULL,
    remaining_activity_mbq DOUBLE NOT NULL,
    decay_residual_mbq DOUBLE NOT NULL,
    reference_time TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_lg_ml FOREIGN KEY (mother_liquor_id) REFERENCES mother_liquor (id),
    CONSTRAINT fk_lg_dp FOREIGN KEY (dispense_id) REFERENCES dispense (id),
    CONSTRAINT fk_lg_dv FOREIGN KEY (delivery_id) REFERENCES delivery (id)
);
COMMENT ON TABLE ledger IS '活度勾稽台账：母液初始活度=分装总量+剩余活度+衰变残量，可复算';
COMMENT ON COLUMN ledger.reference_time IS '勾稽基准时刻（UTC）';

CREATE TABLE IF NOT EXISTS measurement (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL UNIQUE,
    dispense_id BIGINT NOT NULL,
    measured_activity_mbq DOUBLE NOT NULL,
    measured_at TIMESTAMP NOT NULL,
    late BOOLEAN NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_ms_dp FOREIGN KEY (dispense_id) REFERENCES dispense (id)
);
COMMENT ON TABLE measurement IS '批次测量数据；批次完成后到达的测量只记迟到事件（late=true）';
COMMENT ON COLUMN measurement.late IS '是否迟到事件：批次发药完成后到达为 true';
