-- 造纸机改品种过渡料控制服务：H2（MODE=MySQL）自动建表脚本。
-- 数据仅在 JVM 生命周期内保留；金额/长度类字段单位见各列中文注释。

-- 品种工艺主数据（合成数据）
CREATE TABLE IF NOT EXISTS grade (
    grade_code        VARCHAR(32)  NOT NULL PRIMARY KEY COMMENT '品种编码',
    grade_name        VARCHAR(64)  NOT NULL COMMENT '品种名称',
    basis_weight      DECIMAL(8,2) NOT NULL COMMENT '克重，单位 g/m2',
    ash_level         INT          NOT NULL COMMENT '灰分等级，整数级，级差按绝对值比较',
    speed_min         DECIMAL(8,2) NOT NULL COMMENT '新品种工艺窗口-车速下限，单位 m/min（含边界）',
    speed_max         DECIMAL(8,2) NOT NULL COMMENT '新品种工艺窗口-车速上限，单位 m/min（含边界）',
    consistency_min   DECIMAL(6,3) NOT NULL COMMENT '新品种工艺窗口-上料浓度下限，单位 %（含边界）',
    consistency_max   DECIMAL(6,3) NOT NULL COMMENT '新品种工艺窗口-上料浓度上限，单位 %（含边界）'
);

-- 改品种单：申请→方式判定→执行→过渡料处置→完成→归档
CREATE TABLE IF NOT EXISTS transition_order (
    id                     BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    order_no               VARCHAR(40)  NOT NULL COMMENT '改品种单号，业务唯一键',
    paper_machine          VARCHAR(32)  NOT NULL COMMENT '纸机编号',
    from_grade             VARCHAR(32)  NOT NULL COMMENT '原品种编码',
    to_grade               VARCHAR(32)  NOT NULL COMMENT '新品种编码',
    basis_weight_from      DECIMAL(8,2) NOT NULL COMMENT '原品种克重快照，单位 g/m2',
    basis_weight_to        DECIMAL(8,2) NOT NULL COMMENT '新品种克重快照，单位 g/m2',
    ash_level_from         INT          NOT NULL COMMENT '原品种灰分等级快照',
    ash_level_to           INT          NOT NULL COMMENT '新品种灰分等级快照',
    basis_weight_delta_pct DECIMAL(9,4) NULL COMMENT '克重差百分比（|to-from|/from*100），判定后写入，单位 %',
    ash_level_delta        INT          NULL COMMENT '灰分等级差绝对值，判定后写入（级）',
    direct_switch          TINYINT      NULL COMMENT '是否直切：1=直切，0=停机清洗，NULL=未判定',
    wire_flush             TINYINT      NULL COMMENT '是否冲洗网部：1=须冲洗，0=不冲洗，NULL=未判定',
    switch_time_sec        INT          NULL COMMENT '切换时间，单位 秒',
    machine_speed_m_min    DECIMAL(8,2) NULL COMMENT '执行车速，单位 m/min，须落在新品种工艺窗口内（含边界）',
    stock_consistency_pct  DECIMAL(6,3) NULL COMMENT '执行上料浓度，单位 %，须落在新品种工艺窗口内（含边界）',
    transition_length_m    DECIMAL(12,3) NULL COMMENT '过渡料长度=车速*切换时间/60，单位 m',
    current_blend_version  INT          NULL COMMENT '当前生效的回抄浆掺配比例版本号，NULL=未携带比例维度',
    consecutive_out_of_tolerance INT    NOT NULL DEFAULT 0 COMMENT '连续超差（偏离>±2%）采样点数，复核恢复或比例调整后清零',
    paused_at              TIMESTAMP    NULL COMMENT '掺配失控暂停转人工时间，NULL=未暂停',
    resumed_at             TIMESTAMP    NULL COMMENT '最近一次复核确认恢复时间，NULL=从未恢复',
    status                 VARCHAR(24)  NOT NULL COMMENT '状态：APPLIED/DECIDED/EXECUTED/PAUSED/DISPOSED/COMPLETED/ARCHIVED',
    applied_request_id     VARCHAR(64)  NULL COMMENT '申请幂等 requestId',
    applied_at             TIMESTAMP    NOT NULL COMMENT '申请时间',
    decided_at             TIMESTAMP    NULL COMMENT '方式判定时间，NULL=未判定',
    executed_at            TIMESTAMP    NULL COMMENT '执行时间，NULL=未执行',
    disposed_at            TIMESTAMP    NULL COMMENT '过渡料处置时间，NULL=未处置',
    completed_at           TIMESTAMP    NULL COMMENT '完成时间，NULL=未完成',
    archived_at            TIMESTAMP    NULL COMMENT '归档时间（终态），NULL=未归档',
    CONSTRAINT uk_transition_order_no UNIQUE (order_no)
);

-- 纸机占用槽：同一纸机同时刻只允许一张在途改品种单；释放后 active_flag 置 NULL（唯一索引允许多个 NULL）
CREATE TABLE IF NOT EXISTS machine_active_slot (
    id            BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    paper_machine VARCHAR(32) NOT NULL COMMENT '纸机编号',
    order_no      VARCHAR(40) NOT NULL COMMENT '占用该纸机的改品种单号',
    active_flag   VARCHAR(8)  NULL COMMENT '占用标记：ACTIVE=占用中，NULL=已释放',
    CONSTRAINT uk_machine_active UNIQUE (paper_machine, active_flag)
);

-- 停机清洗资源窗口：仅停机清洗方式占用，直切不得登记
CREATE TABLE IF NOT EXISTS cleaning_window (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    window_no     VARCHAR(40)  NOT NULL COMMENT '清洗窗口编号',
    paper_machine VARCHAR(32)  NOT NULL COMMENT '纸机编号',
    order_no      VARCHAR(40)  NOT NULL COMMENT '关联改品种单号',
    occupy_status VARCHAR(16)  NOT NULL COMMENT '占用状态：OCCUPIED=占用中，RELEASED=已释放',
    active_flag   VARCHAR(8)   NULL COMMENT '占用唯一标记：ACTIVE=占用中，NULL=已释放',
    occupied_at   TIMESTAMP    NOT NULL COMMENT '占用登记时间',
    released_at   TIMESTAMP    NULL COMMENT '释放时间，NULL=未释放',
    CONSTRAINT uk_cleaning_window_no UNIQUE (window_no),
    CONSTRAINT uk_cleaning_machine_active UNIQUE (paper_machine, active_flag)
);

-- 过渡纸卷：按卷号唯一登记，强制降级，不得按正品入库
CREATE TABLE IF NOT EXISTS transition_roll (
    id               BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    roll_no          VARCHAR(40)  NOT NULL COMMENT '纸卷卷号，全局唯一',
    order_no         VARCHAR(40)  NOT NULL COMMENT '所属改品种单号',
    paper_machine    VARCHAR(32)  NOT NULL COMMENT '纸机编号',
    length_m         DECIMAL(12,3) NULL COMMENT '纸卷长度，单位 m，可空',
    verdict          VARCHAR(16)  NOT NULL COMMENT '判定终态：DOWNGRADED=降级，UPGRADED=复议改判正品',
    warehouse_status VARCHAR(24)  NOT NULL COMMENT '入库状态：NON_GENUINE=非正品库，GENUINE=正品库（仅改判后）',
    eligible_reexam  TINYINT      NOT NULL DEFAULT 0 COMMENT '是否具备复议资格：1=首次检验恰达/超过正品门槛，0=不具备',
    reexam_count     INT          NOT NULL DEFAULT 0 COMMENT '已受理复议次数，仅允许为 0 或 1',
    registered_at    TIMESTAMP    NOT NULL COMMENT '登记时间',
    verdict_changed_at TIMESTAMP  NULL COMMENT '终态改判时间，NULL=未改判',
    CONSTRAINT uk_transition_roll_no UNIQUE (roll_no)
);

-- 纸卷检验记录（首次检验与复议各至多一条，由唯一约束保证）
CREATE TABLE IF NOT EXISTS roll_inspection (
    id                 BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    roll_no            VARCHAR(40)  NOT NULL COMMENT '纸卷卷号',
    inspection_type    VARCHAR(16)  NOT NULL COMMENT '检验类型：INITIAL=首次检验，REEXAM=复议检验',
    request_id         VARCHAR(64)  NOT NULL COMMENT '检验提交幂等 requestId',
    ash_value_pct      DECIMAL(6,3) NOT NULL COMMENT '灰分实测值，单位 %',
    moisture_pct       DECIMAL(6,3) NOT NULL COMMENT '水分实测值，单位 %',
    tensile_index      DECIMAL(8,3) NOT NULL COMMENT '裂断长/抗张指数合成指标，单位 Nm/g',
    basis_weight_g_m2  DECIMAL(8,2) NOT NULL COMMENT '定量实测值，单位 g/m2',
    meets_threshold    TINYINT      NOT NULL COMMENT '是否恰达/超过正品门槛：1=是，0=否',
    verdict            VARCHAR(24)  NOT NULL COMMENT '本次检验结论：GENUINE_ELIGIBLE=达门槛可复议，BELOW_THRESHOLD=未达门槛，REEXAM_UPGRADED=复议改判正品，REEXAM_REJECTED=复议驳回',
    created_at         TIMESTAMP    NOT NULL COMMENT '提交时间',
    CONSTRAINT uk_roll_inspection_type UNIQUE (roll_no, inspection_type)
);

-- 改判链事件：首次降级与复议改判全程留痕，原降级记录保留可查
CREATE TABLE IF NOT EXISTS roll_verdict_event (
    id          BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    roll_no     VARCHAR(40) NOT NULL COMMENT '纸卷卷号',
    event_type  VARCHAR(24) NOT NULL COMMENT '事件类型：FORCED_DOWNGRADE=强制降级，REEXAM_UPGRADED=复议改判正品，REEXAM_REJECTED=复议驳回',
    from_verdict VARCHAR(16) NULL COMMENT '变更前判定，首次降级时为 NULL',
    to_verdict  VARCHAR(16) NOT NULL COMMENT '变更后判定',
    request_id  VARCHAR(64) NULL COMMENT '触发该事件的 requestId，登记降级时为空',
    event_time  TIMESTAMP   NOT NULL COMMENT '事件时间'
);

-- 回抄浆掺配比例版本链：与改品种单号绑定留档，历史订单可按版本复算
CREATE TABLE IF NOT EXISTS blend_ratio_version (
    id               BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    order_no         VARCHAR(40)  NOT NULL COMMENT '改品种单号',
    version_no       INT          NOT NULL COMMENT '比例版本号，单内自增从 1 开始',
    target_ratio_pct DECIMAL(6,3) NOT NULL COMMENT '目标回抄浆掺配比例，单位 %，须在合成允许区间内（含边界）',
    source           VARCHAR(8)   NOT NULL COMMENT '来源：APPLY=申请携带，ADJUST=执行中调整',
    request_id       VARCHAR(64)  NOT NULL COMMENT '产生该版本的幂等 requestId',
    created_at       TIMESTAMP    NOT NULL COMMENT '版本登记时间；同一订单同一来源同时刻只允许一次',
    CONSTRAINT uk_blend_version_no UNIQUE (order_no, version_no),
    CONSTRAINT uk_blend_source_time UNIQUE (order_no, source, created_at)
);

-- 实际掺配读数：按订单与采样时刻去重；偏离目标超过 ±2%（恰 2% 合格）记超差
CREATE TABLE IF NOT EXISTS blend_reading (
    id               BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    order_no         VARCHAR(40)  NOT NULL COMMENT '改品种单号',
    version_no       INT          NOT NULL COMMENT '采样时生效的比例版本号',
    sampled_at       TIMESTAMP    NOT NULL COMMENT '采样时刻，与订单号联合唯一去重',
    actual_ratio_pct DECIMAL(6,3) NOT NULL COMMENT '实际掺配比例读数，单位 %',
    deviation_pct    DECIMAL(7,3) NOT NULL COMMENT '偏离目标=|实测-目标|，单位 百分点',
    out_of_tolerance TINYINT      NOT NULL COMMENT '是否超差：1=偏离超过 ±2%，0=合格（恰 2% 为 0）',
    request_id       VARCHAR(64)  NOT NULL COMMENT '读数提交幂等 requestId',
    created_at       TIMESTAMP    NOT NULL COMMENT '登记时间',
    CONSTRAINT uk_blend_reading_time UNIQUE (order_no, sampled_at)
);

-- 掺配控制事件：失控暂停转人工与复核恢复全程留痕
CREATE TABLE IF NOT EXISTS blend_control_event (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    order_no    VARCHAR(40)  NOT NULL COMMENT '改品种单号',
    event_type  VARCHAR(24)  NOT NULL COMMENT '事件类型：OUT_OF_CONTROL_PAUSE=失控暂停转人工，MANUAL_RESUME=复核确认恢复',
    request_id  VARCHAR(64)  NULL COMMENT '触发该事件的幂等 requestId，失控暂停由读数触发',
    reviewer    VARCHAR(64)  NULL COMMENT '复核人，仅复核恢复事件有值',
    detail      VARCHAR(255) NULL COMMENT '事件说明（合成数据）',
    event_time  TIMESTAMP    NOT NULL COMMENT '事件时间'
);

-- 回收重量登记（投料额度）：每个未改判正品的已登记过渡卷至多一条；
-- 勾稽不变式：registered_kg = consumed_kg + 可用额度；失效时 invalidated_kg = 失效前未消费额度
CREATE TABLE IF NOT EXISTS recycle_quota (
    id             BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    roll_no        VARCHAR(40)  NOT NULL COMMENT '纸卷卷号，每卷至多一条额度登记',
    initial_kg     DECIMAL(12,3) NOT NULL COMMENT '登记时原额度，单位 kg，正数最多三位小数，登记后不再变化',
    registered_kg  DECIMAL(12,3) NOT NULL COMMENT '当前有效登记额度，单位 kg，只允许调减且不得小于累计消费',
    consumed_kg    DECIMAL(12,3) NOT NULL DEFAULT 0 COMMENT '累计已消费公斤数，单位 kg，投料成功时增加',
    invalidated_kg DECIMAL(12,3) NOT NULL DEFAULT 0 COMMENT '改判正品时失效的未消费额度，单位 kg，未失效为 0',
    version        INT          NOT NULL DEFAULT 1 COMMENT '额度版本号：登记为 1，每次成功调减/消费/失效 +1',
    status         VARCHAR(16)  NOT NULL COMMENT '状态：ACTIVE=有效，INVALIDATED=改判正品后未消费额度已失效',
    invalid_reason VARCHAR(32)  NULL COMMENT '失效原因：REEXAM_UPGRADED=复议改判正品，NULL=未失效',
    request_id     VARCHAR(64)  NOT NULL COMMENT '登记幂等 requestId',
    created_at     TIMESTAMP    NOT NULL COMMENT '登记时间（UTC）',
    updated_at     TIMESTAMP    NOT NULL COMMENT '最近一次额度变动时间（UTC）',
    invalidated_at TIMESTAMP    NULL COMMENT '失效时间（UTC），NULL=未失效',
    CONSTRAINT uk_recycle_quota_roll UNIQUE (roll_no)
);

-- 投料申请单：一次投料引用同一执行中订单、当前掺配版本与总浆量；
-- 回收合计须精确等于 总浆量×目标比例/100，任一卷额度不足整单回滚
CREATE TABLE IF NOT EXISTS feed_batch (
    id               BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    feed_no          VARCHAR(40)  NOT NULL COMMENT '投料批号，业务唯一键',
    order_no         VARCHAR(40)  NOT NULL COMMENT '改品种单号，投料时须为执行中',
    version_no       INT          NOT NULL COMMENT '投料时生效的掺配比例版本号',
    total_pulp_kg    DECIMAL(12,3) NOT NULL COMMENT '总浆量，单位 kg，正数最多三位小数',
    target_ratio_pct DECIMAL(6,3) NOT NULL COMMENT '投料时版本目标掺配比例快照，单位 %',
    required_kg      DECIMAL(12,3) NOT NULL COMMENT '应投回收量=总浆量×目标比例/100，单位 kg，超过三位小数精度的组合被拒绝',
    actual_kg        DECIMAL(12,3) NOT NULL COMMENT '实际回收合计，单位 kg，须精确等于应投回收量',
    roll_count       INT          NOT NULL COMMENT '回收卷数量，1 至 10 个不同卷',
    request_id       VARCHAR(64)  NOT NULL COMMENT '投料幂等 requestId',
    created_at       TIMESTAMP    NOT NULL COMMENT '投料登记时间（UTC）',
    CONSTRAINT uk_feed_batch_no UNIQUE (feed_no)
);

-- 投料明细：每卷扣量凭据；卷改判正品后已消费凭据保留可查
CREATE TABLE IF NOT EXISTS feed_batch_item (
    id        BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    feed_no   VARCHAR(40)  NOT NULL COMMENT '投料批号',
    roll_no   VARCHAR(40)  NOT NULL COMMENT '回收卷号，同一批内不得重复',
    weight_kg DECIMAL(12,3) NOT NULL COMMENT '本卷投料公斤数，单位 kg，正数最多三位小数',
    CONSTRAINT uk_feed_item_roll UNIQUE (feed_no, roll_no)
);

-- 幂等请求记录：指纹覆盖 requestId 与全部业务字段
CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id   VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '幂等键 requestId',
    biz_type     VARCHAR(24) NOT NULL COMMENT '业务类型：APPLY_ORDER/SUBMIT_INSPECTION/SUBMIT_REEXAM/ADJUST_BLEND_RATIO/SUBMIT_BLEND_READING/RESUME_EXECUTION/REGISTER_RECYCLE_QUOTA/ADJUST_RECYCLE_QUOTA/SUBMIT_FEED',
    fingerprint  VARCHAR(64) NOT NULL COMMENT 'SHA-256 指纹，覆盖 requestId 与全部业务字段',
    result_ref   VARCHAR(40) NULL COMMENT '首次结果引用（单号/卷号）',
    result_json  TEXT        NULL COMMENT '首次响应 JSON 原文，重放原样返回',
    created_at   TIMESTAMP   NOT NULL COMMENT '首次受理时间'
);

-- 迟到事件：归档后到达的检验数据只登记事件，不改任何终态
CREATE TABLE IF NOT EXISTS late_event (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    order_no    VARCHAR(40)  NOT NULL COMMENT '关联改品种单号',
    roll_no     VARCHAR(40)  NULL COMMENT '关联纸卷卷号，可空',
    event_type  VARCHAR(32)  NOT NULL COMMENT '迟到事件类型：LATE_INSPECTION=迟到检验，LATE_REEXAM=迟到复议申请',
    payload     TEXT         NOT NULL COMMENT '迟到请求原文（合成数据 JSON）',
    recorded_at TIMESTAMP    NOT NULL COMMENT '登记时间'
);
