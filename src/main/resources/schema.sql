-- 博物馆借展与展柜微环境监测服务建表脚本（H2 MySQL 兼容模式）。
-- 所有时间戳均为 UTC；日期为 UTC 日界；温度单位摄氏度，湿度单位 %RH。

CREATE TABLE IF NOT EXISTS artifact (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    artifact_level VARCHAR(16) NOT NULL,
    material VARCHAR(32) NOT NULL
);
COMMENT ON TABLE artifact IS '文物档案（合成数据）';
COMMENT ON COLUMN artifact.id IS '文物主键';
COMMENT ON COLUMN artifact.name IS '文物名称';
COMMENT ON COLUMN artifact.artifact_level IS '文物等级：LEVEL_ONE 一级 / LEVEL_TWO 二级';
COMMENT ON COLUMN artifact.material IS '材质，决定展柜微环境温湿度区间：PAPER 纸质 / BRONZE 青铜 / GENERAL 一般';

CREATE TABLE IF NOT EXISTS showcase (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    gallery VARCHAR(64) NOT NULL,
    material VARCHAR(32) NOT NULL,
    temp_min DECIMAL(5,2) NOT NULL,
    temp_max DECIMAL(5,2) NOT NULL,
    humidity_min DECIMAL(5,2) NOT NULL,
    humidity_max DECIMAL(5,2) NOT NULL,
    frozen BOOLEAN NOT NULL DEFAULT FALSE
);
COMMENT ON TABLE showcase IS '展柜（合成数据），按材质设定温湿度区间';
COMMENT ON COLUMN showcase.id IS '展柜主键';
COMMENT ON COLUMN showcase.gallery IS '所属展馆/展厅名称';
COMMENT ON COLUMN showcase.material IS '适配材质：PAPER 纸质 / BRONZE 青铜 / GENERAL 一般';
COMMENT ON COLUMN showcase.temp_min IS '温度下限（摄氏度，含边界）';
COMMENT ON COLUMN showcase.temp_max IS '温度上限（摄氏度，含边界）';
COMMENT ON COLUMN showcase.humidity_min IS '湿度下限（%RH，含边界）';
COMMENT ON COLUMN showcase.humidity_max IS '湿度上限（%RH，含边界）';
COMMENT ON COLUMN showcase.frozen IS '是否冻结新入藏：TRUE 表示紧急工单未处置，禁止新借展入藏';

CREATE TABLE IF NOT EXISTS loan_contract (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    artifact_id BIGINT NOT NULL,
    showcase_id BIGINT NOT NULL,
    borrower VARCHAR(128) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    original_end_date DATE NOT NULL,
    extended BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(32) NOT NULL,
    suspend_reason VARCHAR(64) DEFAULT NULL,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE loan_contract IS '借展合同，状态流转：APPLIED 申请→ON_DISPLAY 在展→RETURNED 归还点交完成→CLOSED 结案；SUSPENDED 为环境异常挂起';
COMMENT ON COLUMN loan_contract.id IS '合同主键';
COMMENT ON COLUMN loan_contract.request_id IS '受理申请的幂等请求号';
COMMENT ON COLUMN loan_contract.artifact_id IS '借展文物主键';
COMMENT ON COLUMN loan_contract.showcase_id IS '入藏展柜主键';
COMMENT ON COLUMN loan_contract.borrower IS '借展方名称';
COMMENT ON COLUMN loan_contract.start_date IS '借展开始日（UTC 日界，含当日）';
COMMENT ON COLUMN loan_contract.end_date IS '当前借展截止日（UTC 日界，含当日），续展后更新';
COMMENT ON COLUMN loan_contract.original_end_date IS '原始借展截止日，用于核算续展上限';
COMMENT ON COLUMN loan_contract.extended IS '是否已续展：TRUE 表示唯一一次续展已使用';
COMMENT ON COLUMN loan_contract.status IS '合同状态：APPLIED/ON_DISPLAY/SUSPENDED/RETURNED/CLOSED';
COMMENT ON COLUMN loan_contract.suspend_reason IS '挂起原因，NULL 表示未挂起';
COMMENT ON COLUMN loan_contract.version IS '乐观锁版本号';
COMMENT ON COLUMN loan_contract.created_at IS '创建时间（UTC）';
COMMENT ON COLUMN loan_contract.updated_at IS '最近更新时间（UTC）';

CREATE TABLE IF NOT EXISTS artifact_active_loan (
    artifact_id BIGINT PRIMARY KEY,
    contract_id BIGINT NOT NULL
);
COMMENT ON TABLE artifact_active_loan IS '文物有效借展唯一约束表：同一文物同一时刻仅允许一个未结案合同';
COMMENT ON COLUMN artifact_active_loan.artifact_id IS '文物主键（主键即唯一约束）';
COMMENT ON COLUMN artifact_active_loan.contract_id IS '当前有效合同主键';

CREATE TABLE IF NOT EXISTS handover (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    contract_id BIGINT NOT NULL,
    handover_type VARCHAR(16) NOT NULL,
    museum_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    borrower_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP DEFAULT NULL,
    CONSTRAINT uk_handover UNIQUE (contract_id, handover_type)
);
COMMENT ON TABLE handover IS '点交单：交付与归还点交均须馆方与借展方双方确认后生效';
COMMENT ON COLUMN handover.id IS '点交单主键';
COMMENT ON COLUMN handover.contract_id IS '所属合同主键';
COMMENT ON COLUMN handover.handover_type IS '点交类型：DELIVERY 交付点交 / RETURN 归还点交';
COMMENT ON COLUMN handover.museum_confirmed IS '馆方是否已确认';
COMMENT ON COLUMN handover.borrower_confirmed IS '借展方是否已确认';
COMMENT ON COLUMN handover.created_at IS '点交单创建时间（UTC）';
COMMENT ON COLUMN handover.completed_at IS '双方确认完成时间（UTC），NULL 表示未生效';

CREATE TABLE IF NOT EXISTS resume_review (
    contract_id BIGINT NOT NULL,
    party VARCHAR(16) NOT NULL,
    reviewed_at TIMESTAMP NOT NULL,
    PRIMARY KEY (contract_id, party)
);
COMMENT ON TABLE resume_review IS '挂起恢复复核记录：馆方与借展方双方复核齐备后合同恢复在展';
COMMENT ON COLUMN resume_review.contract_id IS '合同主键';
COMMENT ON COLUMN resume_review.party IS '复核方：MUSEUM 馆方 / BORROWER 借展方';
COMMENT ON COLUMN resume_review.reviewed_at IS '复核时间（UTC）';

CREATE TABLE IF NOT EXISTS climate_reading (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    showcase_id BIGINT NOT NULL,
    sampled_at TIMESTAMP NOT NULL,
    temperature DECIMAL(5,2) NOT NULL,
    humidity DECIMAL(5,2) NOT NULL,
    out_of_range BOOLEAN NOT NULL,
    late BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_reading UNIQUE (showcase_id, sampled_at)
);
COMMENT ON TABLE climate_reading IS '展柜微环境读数，按展柜+采样时刻去重，乱序按采样时刻归位';
COMMENT ON COLUMN climate_reading.id IS '读数主键';
COMMENT ON COLUMN climate_reading.showcase_id IS '展柜主键';
COMMENT ON COLUMN climate_reading.sampled_at IS '采样时刻（UTC）';
COMMENT ON COLUMN climate_reading.temperature IS '温度（摄氏度）';
COMMENT ON COLUMN climate_reading.humidity IS '湿度（%RH）';
COMMENT ON COLUMN climate_reading.out_of_range IS '是否越限：按展柜温湿度区间（含边界）判定';
COMMENT ON COLUMN climate_reading.late IS '是否迟到读数：TRUE 表示合同结案后到达，仅记录不参与越限判定';
COMMENT ON COLUMN climate_reading.created_at IS '入库时间（UTC）';

CREATE TABLE IF NOT EXISTS protection_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    showcase_id BIGINT NOT NULL,
    contract_id BIGINT DEFAULT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    deadline_at TIMESTAMP NOT NULL,
    confirmed_at TIMESTAMP DEFAULT NULL,
    escalated_at TIMESTAMP DEFAULT NULL,
    watermark_at TIMESTAMP DEFAULT NULL
);
COMMENT ON TABLE protection_order IS '保护工单：连续 3 个采样点越限自动生成，24 小时内须处置确认，逾期升级紧急';
COMMENT ON COLUMN protection_order.id IS '工单主键';
COMMENT ON COLUMN protection_order.showcase_id IS '展柜主键';
COMMENT ON COLUMN protection_order.contract_id IS '触发时在展合同主键，NULL 表示无在展合同';
COMMENT ON COLUMN protection_order.status IS '工单状态：OPEN 待处置 / CONFIRMED 已确认 / ESCALATED 已升级紧急';
COMMENT ON COLUMN protection_order.created_at IS '工单生成时间（UTC）';
COMMENT ON COLUMN protection_order.deadline_at IS '处置时限（UTC）= 生成时间 + 24 小时，恰满即升级';
COMMENT ON COLUMN protection_order.confirmed_at IS '处置确认时间（UTC），NULL 表示未确认';
COMMENT ON COLUMN protection_order.escalated_at IS '升级紧急时间（UTC），NULL 表示未升级';
COMMENT ON COLUMN protection_order.watermark_at IS '处置确认时的越限序列复位水位（采样时刻），NULL 表示未确认';

CREATE TABLE IF NOT EXISTS late_reading_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    showcase_id BIGINT NOT NULL,
    sampled_at TIMESTAMP NOT NULL,
    recorded_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE late_reading_event IS '迟到读数事件：合同结案后到达的读数仅记录事件，不参与判定';
COMMENT ON COLUMN late_reading_event.id IS '事件主键';
COMMENT ON COLUMN late_reading_event.showcase_id IS '展柜主键';
COMMENT ON COLUMN late_reading_event.sampled_at IS '采样时刻（UTC）';
COMMENT ON COLUMN late_reading_event.recorded_at IS '事件记录时间（UTC）';

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(64) PRIMARY KEY,
    fingerprint VARCHAR(128) NOT NULL,
    op_type VARCHAR(64) NOT NULL,
    response_status INT DEFAULT NULL,
    response_body CLOB DEFAULT NULL,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE idempotency_record IS '幂等记录：指纹覆盖 requestId 与全部业务字段，同键同参返回首次结果，同键异参返回 409';
COMMENT ON COLUMN idempotency_record.request_id IS '幂等请求号（主键）';
COMMENT ON COLUMN idempotency_record.fingerprint IS '请求指纹：requestId 与全部业务字段的散列';
COMMENT ON COLUMN idempotency_record.op_type IS '操作类型，如 APPLY/HANDOVER/EXTEND/RETURN/CLOSE/READING/CONFIRM/RESUME/IMAGES_DELIVERY/IMAGES_RETURN/RESOLVE_DIFF_ORDER';
COMMENT ON COLUMN idempotency_record.response_status IS '首次响应 HTTP 状态码，NULL 表示处理中';
COMMENT ON COLUMN idempotency_record.response_body IS '首次响应报文快照';
COMMENT ON COLUMN idempotency_record.created_at IS '记录创建时间（UTC）';

-- 点交影像留档：出借/归还点交登记的合成影像特征清单（特征键值对），与点交单同一事务落库
CREATE TABLE IF NOT EXISTS handover_image_feature (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    handover_id BIGINT NOT NULL,
    feature_key VARCHAR(64) NOT NULL,
    feature_value VARCHAR(256) NOT NULL,
    captured_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_handover_feature UNIQUE (handover_id, feature_key)
);
COMMENT ON TABLE handover_image_feature IS '点交影像合成特征清单：出借与归还点交各登记一份，归还时按键比对特征值';
COMMENT ON COLUMN handover_image_feature.id IS '影像特征主键';
COMMENT ON COLUMN handover_image_feature.handover_id IS '所属点交单主键（DELIVERY 清单为比对基线）';
COMMENT ON COLUMN handover_image_feature.feature_key IS '合成影像特征项名称，如 CORNER 边角 / GLAZE 釉面 / INSCRIPTION 题跋';
COMMENT ON COLUMN handover_image_feature.feature_value IS '合成影像特征值（合成描述符/哈希，非真实影像）';
COMMENT ON COLUMN handover_image_feature.captured_at IS '特征采集登记时间（UTC）';

-- 差异工单：归还点交影像比对差异项超过 0 时自动生成，闭环前合同不得结案
CREATE TABLE IF NOT EXISTS difference_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    contract_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    diff_count INT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    resolved_at TIMESTAMP DEFAULT NULL,
    resolved_by VARCHAR(128) DEFAULT NULL
);
COMMENT ON TABLE difference_order IS '归还点交差异工单：差异项超过 0 自动生成，OPEN 未闭环 / RESOLVED 已闭环';
COMMENT ON COLUMN difference_order.id IS '差异工单主键';
COMMENT ON COLUMN difference_order.contract_id IS '所属借展合同主键';
COMMENT ON COLUMN difference_order.status IS '工单状态：OPEN 进行中（未闭环）/ RESOLVED 已闭环';
COMMENT ON COLUMN difference_order.diff_count IS '比对差异项数量（大于 0 才生成工单）';
COMMENT ON COLUMN difference_order.request_id IS '触发生成工单的归还点交登记幂等请求号';
COMMENT ON COLUMN difference_order.created_at IS '工单生成时间（UTC）';
COMMENT ON COLUMN difference_order.resolved_at IS '闭环时间（UTC），NULL 表示未闭环';
COMMENT ON COLUMN difference_order.resolved_by IS '闭环处置人，NULL 表示未闭环';

-- 差异明细：工单内每一个差异项（值不一致 / 归还缺失 / 归还有新增）
CREATE TABLE IF NOT EXISTS difference_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    feature_key VARCHAR(64) NOT NULL,
    delivery_value VARCHAR(256) DEFAULT NULL,
    return_value VARCHAR(256) DEFAULT NULL,
    diff_type VARCHAR(24) NOT NULL
);
COMMENT ON TABLE difference_item IS '差异工单明细：逐特征记录出借基线值与归还值及差异类型';
COMMENT ON COLUMN difference_item.id IS '差异明细主键';
COMMENT ON COLUMN difference_item.order_id IS '所属差异工单主键';
COMMENT ON COLUMN difference_item.feature_key IS '差异特征项名称';
COMMENT ON COLUMN difference_item.delivery_value IS '出借点交基线特征值，NULL 表示归还时新增项';
COMMENT ON COLUMN difference_item.return_value IS '归还点交特征值，NULL 表示归还时缺失项';
COMMENT ON COLUMN difference_item.diff_type IS '差异类型：VALUE_MISMATCH 值不一致 / MISSING_ON_RETURN 归还缺失 / EXTRA_ON_RETURN 归还新增';

-- 同一合同同一时刻仅允许一个进行中（OPEN）差异工单的唯一约束表
CREATE TABLE IF NOT EXISTS contract_open_diff_order (
    contract_id BIGINT PRIMARY KEY,
    order_id BIGINT NOT NULL
);
COMMENT ON TABLE contract_open_diff_order IS '进行中差异工单唯一约束表：合同主键即唯一约束，工单闭环时删除对应行';
COMMENT ON COLUMN contract_open_diff_order.contract_id IS '合同主键（主键即唯一约束）';
COMMENT ON COLUMN contract_open_diff_order.order_id IS '当前唯一进行中差异工单主键';

-- 合成种子数据：两件文物、两个展柜
MERGE INTO artifact KEY(id) VALUES
    (1, '宋代纸本山水卷', 'LEVEL_ONE', 'PAPER'),
    (2, '商代青铜鼎', 'LEVEL_TWO', 'BRONZE');
MERGE INTO showcase KEY(id) VALUES
    (1, '书画一厅', 'PAPER', 18.00, 22.00, 50.00, 60.00, FALSE),
    (2, '青铜二厅', 'BRONZE', 16.00, 24.00, 40.00, 55.00, FALSE);
