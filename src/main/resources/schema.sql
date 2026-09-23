-- 制造批次谱系与隔离传播：H2（MODE=MySQL）自动建表脚本。
-- 数量统一 DECIMAL(19,3)，单位与状态以字符串存储；所有台账仅追加、不覆盖。

-- 批次主表：status 取值 AVAILABLE（可用）/ QUARANTINED（隔离）；
-- consumed_qty 为累计已消费量，可用余额 = initial_qty - consumed_qty；
-- version 为乐观锁版本，每次被转换消费后递增。
CREATE TABLE IF NOT EXISTS lot (
    lot_id       VARCHAR(64)    NOT NULL PRIMARY KEY COMMENT '批次唯一标识',
    unit         VARCHAR(16)    NOT NULL COMMENT '计量单位，全谱系须一致',
    initial_qty  DECIMAL(19,3)  NOT NULL COMMENT '初始/产出数量，三位小数、必须为正',
    consumed_qty DECIMAL(19,3)  NOT NULL DEFAULT 0 COMMENT '累计消费数量（数量台账，只增不改历史）',
    status       VARCHAR(16)    NOT NULL COMMENT '状态：AVAILABLE 可用 / QUARANTINED 隔离',
    version      INT            NOT NULL DEFAULT 0 COMMENT '乐观锁版本号，每次被消费加一',
    created_at   TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（Asia/Shanghai）',
    remark       VARCHAR(255)   NULL COMMENT '备注（合成数据用途）',
    CONSTRAINT ck_lot_qty_positive CHECK (initial_qty > 0),
    CONSTRAINT ck_lot_consumed_nonneg CHECK (consumed_qty >= 0),
    CONSTRAINT ck_lot_consumed_within_initial CHECK (consumed_qty <= initial_qty),
    CONSTRAINT ck_lot_status CHECK (status IN ('AVAILABLE', 'QUARANTINED'))
);

-- 全局写顺序锁：转换与隔离提交前必须持有唯一行锁，
-- 以数据库互斥保证两类写操作的线性一致提交顺序（读操作不加锁）。
CREATE TABLE IF NOT EXISTS service_lock (
    id        TINYINT   NOT NULL PRIMARY KEY COMMENT '固定为 1 的单行锁',
    locked_at TIMESTAMP NULL COMMENT '仅作占位，无业务含义'
);
INSERT INTO service_lock (id, locked_at)
SELECT 1, CURRENT_TIMESTAMP WHERE NOT EXISTS (SELECT 1 FROM service_lock WHERE id = 1);

-- 转换单（一次拆并批）：request_id 幂等键唯一；fingerprint 为完整请求指纹，同键异参冲突。
CREATE TABLE IF NOT EXISTS conversion (
    id               BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '转换单内部主键',
    request_id       VARCHAR(64)   NOT NULL COMMENT '幂等请求键',
    fingerprint      VARCHAR(64)   NOT NULL COMMENT '完整转换请求指纹（SHA-256）',
    request_json     CLOB          NOT NULL COMMENT '首次完整请求快照（历史不可覆盖）',
    response_json    CLOB          NOT NULL COMMENT '首次完整响应快照（同键同参原样返回）',
    total_input_qty  DECIMAL(19,3) NOT NULL COMMENT '父批次消费总量',
    total_output_qty DECIMAL(19,3) NOT NULL COMMENT '子批次产出总量',
    allowed_loss     DECIMAL(19,3) NOT NULL COMMENT '本次允许损耗，消费-产出差额不得超过该值',
    unit             VARCHAR(16)   NOT NULL COMMENT '转换计量单位',
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间（Asia/Shanghai）',
    CONSTRAINT uq_conversion_request UNIQUE (request_id),
    CONSTRAINT ck_conversion_loss_nonneg CHECK (allowed_loss >= 0)
);

-- 转换父批次明细（不可变台账）。
CREATE TABLE IF NOT EXISTS conversion_input (
    conversion_id    BIGINT        NOT NULL COMMENT '转换单主键',
    lot_id           VARCHAR(64)   NOT NULL COMMENT '父批次 ID',
    seq              INT           NOT NULL COMMENT '请求中的顺序（指纹稳定性用）',
    consume_qty      DECIMAL(19,3) NOT NULL COMMENT '本次消费量',
    expected_version INT           NOT NULL COMMENT '请求携带的父批次期望版本',
    PRIMARY KEY (conversion_id, lot_id)
);

-- 转换子批次明细（不可变台账）。
CREATE TABLE IF NOT EXISTS conversion_output (
    conversion_id BIGINT        NOT NULL COMMENT '转换单主键',
    lot_id        VARCHAR(64)   NOT NULL COMMENT '子批次 ID',
    seq           INT           NOT NULL COMMENT '请求中的顺序（指纹稳定性用）',
    output_qty    DECIMAL(19,3) NOT NULL COMMENT '本次产出量',
    PRIMARY KEY (conversion_id, lot_id)
);

-- 谱系边：父 -> 子；一个子批次只由一次转换产生。
CREATE TABLE IF NOT EXISTS genealogy_edge (
    parent_lot_id VARCHAR(64)   NOT NULL COMMENT '父批次 ID',
    child_lot_id  VARCHAR(64)   NOT NULL COMMENT '子批次 ID',
    conversion_id BIGINT        NOT NULL COMMENT '产生该边的转换单',
    qty           DECIMAL(19,3) NOT NULL COMMENT '该父子关系上的投入量',
    PRIMARY KEY (parent_lot_id, child_lot_id)
);
CREATE INDEX IF NOT EXISTS idx_edge_child ON genealogy_edge (child_lot_id);

-- 隔离事件流水（仅追加）：action 为 QUARANTINE（设置隔离）/ RELEASE（解除）。
CREATE TABLE IF NOT EXISTS quarantine_event (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '事件内部主键',
    source_lot_id VARCHAR(64)  NOT NULL COMMENT '被直接设置/解除隔离的批次（隔离来源）',
    action        VARCHAR(16)  NOT NULL COMMENT '动作：QUARANTINE / RELEASE',
    reason        VARCHAR(255) NULL COMMENT '原因（合成数据）',
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '事件时间（Asia/Shanghai）',
    CONSTRAINT ck_qevent_action CHECK (action IN ('QUARANTINE', 'RELEASE'))
);
CREATE INDEX IF NOT EXISTS idx_qevent_source ON quarantine_event (source_lot_id);

-- 每个后代当前生效的隔离来源集合：active=1 表示仍在生效；
-- 解除只把 active 置 0 并回填 released_at，历史行不删除不覆盖。
CREATE TABLE IF NOT EXISTS quarantine_source (
    id            BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '记录内部主键',
    lot_id        VARCHAR(64) NOT NULL COMMENT '被隔离的（后代）批次 ID',
    source_lot_id VARCHAR(64) NOT NULL COMMENT '隔离来源批次 ID（沿谱系传播）',
    active        TINYINT     NOT NULL DEFAULT 1 COMMENT '是否仍生效：1 生效 / 0 已解除',
    quarantined_at TIMESTAMP  NOT NULL COMMENT '来源生效时间（Asia/Shanghai）',
    released_at   TIMESTAMP   NULL COMMENT '来源解除时间（Asia/Shanghai），未解除为空',
    CONSTRAINT ck_qsource_active CHECK (active IN (0, 1))
);
CREATE INDEX IF NOT EXISTS idx_qsource_lot ON quarantine_source (lot_id, active);
CREATE INDEX IF NOT EXISTS idx_qsource_source ON quarantine_source (source_lot_id, active);
