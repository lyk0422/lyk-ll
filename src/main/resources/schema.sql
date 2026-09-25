-- 造纸机改品种过渡料控制服务：H2（MODE=MySQL）建表脚本，仅合成数据。
-- 所有数值列单位、时区与空值含义见列注释；时间列均为 Asia/Shanghai 本地时间。

CREATE TABLE IF NOT EXISTS grade (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    grade_code      VARCHAR(64)  NOT NULL UNIQUE COMMENT '品种编码，合成数据主键',
    basis_weight    DECIMAL(10,2) NOT NULL COMMENT '目标克重，单位 g/m²',
    ash_level       INT          NOT NULL COMMENT '灰分等级，整数；等级差按绝对值计算',
    speed_min       DECIMAL(10,2) NOT NULL COMMENT '新品种车速工艺窗口下限，单位 m/min，边界含（恰边界合格）',
    speed_max       DECIMAL(10,2) NOT NULL COMMENT '新品种车速工艺窗口上限，单位 m/min，边界含（恰边界合格）',
    consistency_min DECIMAL(8,4)  NOT NULL COMMENT '新品种上料浓度窗口下限，单位 %，边界含（恰边界合格）',
    consistency_max DECIMAL(8,4)  NOT NULL COMMENT '新品种上料浓度窗口上限，单位 %，边界含（恰边界合格）',
    tensile_min     DECIMAL(10,2) NOT NULL COMMENT '合成抗张指数正品门槛，单位 N·m/g，恰达门槛视为合格',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间，Asia/Shanghai'
);

CREATE TABLE IF NOT EXISTS transition_order (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no            VARCHAR(64) NOT NULL UNIQUE COMMENT '改品种单号，业务主键',
    machine_id          VARCHAR(64) NOT NULL COMMENT '纸机编号',
    from_grade_code     VARCHAR(64) NOT NULL COMMENT '改前品种编码',
    to_grade_code       VARCHAR(64) NOT NULL COMMENT '改后品种编码',
    basis_weight_from   DECIMAL(10,2) NOT NULL COMMENT '改前克重快照，单位 g/m²',
    basis_weight_to     DECIMAL(10,2) NOT NULL COMMENT '改后克重快照，单位 g/m²',
    ash_from            INT NOT NULL COMMENT '改前灰分等级快照',
    ash_to              INT NOT NULL COMMENT '改后灰分等级快照',
    status              VARCHAR(24) NOT NULL COMMENT '流转状态：APPLIED 已申请/EXECUTING 执行中/DISPOSED 已处置/COMPLETED 已完成/ARCHIVED 已归档（终态）',
    switch_mode         VARCHAR(24) NULL COMMENT '切换方式：DIRECT 直接切换/STOP_CLEAN 停机清洗；申请时判定，未判定为 NULL',
    wire_flush          TINYINT NOT NULL DEFAULT 0 COMMENT '是否须冲洗网部：1 是 0 否；灰分等级差>2 时为 1',
    switch_seconds      INT NULL COMMENT '实际切换用时，单位秒；与车速共同决定过渡料长度，未执行为 NULL',
    machine_speed       DECIMAL(10,2) NULL COMMENT '执行实际车速，单位 m/min，未执行为 NULL',
    consistency         DECIMAL(8,4) NULL COMMENT '执行实际上料浓度，单位 %，未执行为 NULL',
    transition_length_m DECIMAL(14,3) NULL COMMENT '过渡料长度=车速(m/min)×切换秒数/60，单位米，未执行为 NULL',
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '申请时间，Asia/Shanghai',
    executed_at         TIMESTAMP NULL COMMENT '执行（占用资源）登记时间，未执行为 NULL',
    completed_at        TIMESTAMP NULL COMMENT '完成时间（释放纸机占用），未完成为 NULL',
    archived_at         TIMESTAMP NULL COMMENT '归档时间，终态时间戳，未归档为 NULL'
);
CREATE INDEX IF NOT EXISTS idx_order_machine_status ON transition_order (machine_id, status);

-- 纸机执行占用：同一纸机 machine_id 唯一，保证同时刻只有一张执行中改品种单。
CREATE TABLE IF NOT EXISTS machine_execution_lock (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    machine_id  VARCHAR(64) NOT NULL UNIQUE COMMENT '纸机编号，唯一约束即并发唯一受理依据',
    order_no    VARCHAR(64) NOT NULL COMMENT '占用该纸机的改品种单号',
    acquired_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用取得时间，Asia/Shanghai'
);

-- 停机清洗窗口占用：仅 STOP_CLEAN 方式登记，DIRECT 方式不得出现记录。
CREATE TABLE IF NOT EXISTS cleaning_window (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    machine_id   VARCHAR(64) NOT NULL COMMENT '纸机编号',
    order_no     VARCHAR(64) NOT NULL COMMENT '关联改品种单号',
    window_start TIMESTAMP NOT NULL COMMENT '清洗窗口开始时间，Asia/Shanghai',
    window_end   TIMESTAMP NULL COMMENT '清洗窗口结束时间，未结束为 NULL',
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登记时间，与执行占用同一事务写入',
    CONSTRAINT uq_cleaning_order UNIQUE (order_no)
);

CREATE TABLE IF NOT EXISTS transition_roll (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    roll_no               VARCHAR(64) NOT NULL UNIQUE COMMENT '过渡纸卷卷号，全库唯一登记',
    order_no              VARCHAR(64) NOT NULL COMMENT '所属改品种单号',
    machine_id            VARCHAR(64) NOT NULL COMMENT '纸机编号',
    length_m              DECIMAL(14,3) NOT NULL COMMENT '纸卷长度，单位米',
    final_grade           VARCHAR(16) NOT NULL COMMENT '终态等级：DOWNGRADED 降级/QUALIFIED 正品（仅一次复议改判可达）',
    inspection_status     VARCHAR(24) NOT NULL COMMENT '检验状态：PENDING 待检/INSPECTED 已检/LATE 归档后迟到',
    gate_passed           TINYINT NULL COMMENT '合成检验是否达正品门槛（含恰达边界）：1 是 0 否，未检为 NULL',
    measured_basis_weight DECIMAL(10,2) NULL COMMENT '实测克重，单位 g/m²，未检为 NULL',
    measured_ash_level    INT NULL COMMENT '实测灰分等级，未检为 NULL',
    measured_tensile      DECIMAL(10,2) NULL COMMENT '实测抗张指数，单位 N·m/g，未检为 NULL',
    appeal_count          INT NOT NULL DEFAULT 0 COMMENT '已用复议次数，仅允许 0 或 1',
    created_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登记时间（强制降级），Asia/Shanghai',
    inspected_at          TIMESTAMP NULL COMMENT '首次检验判定时间，未检为 NULL',
    appealed_at           TIMESTAMP NULL COMMENT '复议改判时间，未复议为 NULL'
);

CREATE TABLE IF NOT EXISTS roll_judgement (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    roll_no     VARCHAR(64) NOT NULL COMMENT '过渡纸卷卷号',
    round_no    INT NOT NULL COMMENT '判定轮次：1 首次判定（强制降级），2 复议改判',
    result      VARCHAR(16) NOT NULL COMMENT '本轮判定结果：DOWNGRADED/QUALIFIED',
    gate_passed TINYINT NULL COMMENT '本轮检验是否达正品门槛（含边界），复议无新检验时沿用上轮',
    reason      VARCHAR(255) NOT NULL COMMENT '判定理由',
    request_id  VARCHAR(96) NOT NULL COMMENT '触发本轮判定的幂等 requestId',
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '判定时间，Asia/Shanghai',
    CONSTRAINT uq_roll_round UNIQUE (roll_no, round_no)
);

CREATE TABLE IF NOT EXISTS late_inspection_event (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no    VARCHAR(64) NOT NULL COMMENT '已归档改品种单号',
    roll_no     VARCHAR(64) NULL COMMENT '迟到检验携带的卷号，可能为空',
    request_id  VARCHAR(96) NULL COMMENT '迟到提交携带的 requestId，可能为空',
    payload     CLOB NOT NULL COMMENT '迟到检验原始报文，仅登记事件不产生判定',
    received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '迟到事件接收时间，Asia/Shanghai'
);

-- 幂等登记表：request_id 全局唯一；指纹覆盖 requestId、业务类型与全部业务字段。
CREATE TABLE IF NOT EXISTS request_registry (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id    VARCHAR(96) NOT NULL UNIQUE COMMENT '客户端幂等键，唯一',
    biz_type      VARCHAR(40) NOT NULL COMMENT '业务类型：APPLY/EXECUTE/REGISTER_ROLL/INSPECTION/APPEAL/ARCHIVE 等',
    fingerprint   CHAR(64) NOT NULL COMMENT 'SHA-256 指纹，覆盖 requestId 与全部业务字段',
    ref_key       VARCHAR(96) NULL COMMENT '首次成功结果关联业务主键（单号/卷号）',
    response_json CLOB NULL COMMENT '首次成功响应快照，重放原样返回；处理中为 NULL',
    status        VARCHAR(12) NOT NULL COMMENT 'PROCESSING 处理中/DONE 已完成',
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次受理时间，Asia/Shanghai',
    finished_at   TIMESTAMP NULL COMMENT '首次处理完成时间，未完成为 NULL'
);
