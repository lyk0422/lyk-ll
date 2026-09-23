-- 制造批次谱系与隔离传播：本地 H2（MODE=MySQL）自动建表脚本。
-- 数量一律 DECIMAL(19,3) 固定三位小数；历史表只追加、不更新。

-- 批次主表：produced_qty - consumed_qty 即当前可用余额。
CREATE TABLE IF NOT EXISTS lot (
    lot_id        VARCHAR(64)  NOT NULL PRIMARY KEY COMMENT '批次唯一编号',
    unit          VARCHAR(16)  NOT NULL COMMENT '计量单位，全谱系必须一致',
    initial_qty   DECIMAL(19,3) NOT NULL COMMENT '建档初始数量，三位小数，必须为正',
    produced_qty  DECIMAL(19,3) NOT NULL COMMENT '累计产出/建档数量，三位小数',
    consumed_qty  DECIMAL(19,3) NOT NULL COMMENT '累计被下游转换消费数量，三位小数',
    status        VARCHAR(16)  NOT NULL COMMENT '当前状态：AVAILABLE 可用 / QUARANTINED 隔离中',
    version       BIGINT       NOT NULL COMMENT '乐观版本号，仅数量台账变动（被消费）时递增',
    created_at    TIMESTAMP    NOT NULL COMMENT '建档时间，Asia/Shanghai'
);

-- 转换与隔离串行化使用的单行互斥锁。
CREATE TABLE IF NOT EXISTS tx_mutex (
    id TINYINT NOT NULL PRIMARY KEY COMMENT '恒为 1 的互斥锁占位行'
);
MERGE INTO tx_mutex (id) KEY(id) VALUES (1);

-- 转换请求台账（不可变）：request_id 幂等键 + 完整参数指纹。
CREATE TABLE IF NOT EXISTS transform_record (
    request_id     VARCHAR(96) NOT NULL PRIMARY KEY COMMENT '业务幂等键',
    fingerprint    VARCHAR(64) NOT NULL COMMENT '除 requestId 外完整参数的 SHA-256 指纹',
    unit           VARCHAR(16) NOT NULL COMMENT '本次转换统一计量单位',
    request_json   CLOB        NOT NULL COMMENT '首次完整请求报文（合成数据）',
    response_json  CLOB COMMENT '首次成功结果报文，供同键同参重放',
    total_consumed DECIMAL(19,3) NOT NULL COMMENT '父批次消费总量',
    total_produced DECIMAL(19,3) NOT NULL COMMENT '子批次产出总量',
    allowed_loss   DECIMAL(19,3) NOT NULL COMMENT '允许损耗上限，三位小数',
    created_at     TIMESTAMP    NOT NULL COMMENT '转换提交时间'
);

-- 转换投入产出明细（不可变）。
CREATE TABLE IF NOT EXISTS transform_io (
    id         BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '明细自增主键',
    request_id VARCHAR(96) NOT NULL COMMENT '所属转换 requestId',
    side       VARCHAR(8)  NOT NULL COMMENT 'PARENT 父批次消费 / CHILD 子批次产出',
    lot_id     VARCHAR(64) NOT NULL COMMENT '批次编号',
    quantity   DECIMAL(19,3) NOT NULL COMMENT '本笔消费量或产出量，三位小数',
    seq_no     INT NOT NULL COMMENT '请求中的原始序号'
);

-- 谱系边：一次转换的每个 (父, 子) 组合。
CREATE TABLE IF NOT EXISTS lot_link (
    id            BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '边自增主键',
    request_id    VARCHAR(96) NOT NULL COMMENT '产生该边的转换 requestId',
    parent_lot_id VARCHAR(64) NOT NULL COMMENT '父批次编号',
    child_lot_id  VARCHAR(64) NOT NULL COMMENT '子批次编号',
    CONSTRAINT uk_lot_link UNIQUE (request_id, parent_lot_id, child_lot_id)
);
CREATE INDEX IF NOT EXISTS idx_lot_link_parent ON lot_link (parent_lot_id);
CREATE INDEX IF NOT EXISTS idx_lot_link_child ON lot_link (child_lot_id);

-- 数量台账（不可变）：每次建档/消费/产出后留痕。
CREATE TABLE IF NOT EXISTS lot_ledger (
    id            BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '台账自增主键',
    request_id    VARCHAR(96) COMMENT '关联转换 requestId，建档时为空',
    lot_id        VARCHAR(64) NOT NULL COMMENT '批次编号',
    change_type   VARCHAR(16) NOT NULL COMMENT 'INITIAL 建档 / CONSUME 消费 / PRODUCE 产出',
    quantity      DECIMAL(19,3) NOT NULL COMMENT '变动数量，消费记为正的消费量',
    balance_after DECIMAL(19,3) NOT NULL COMMENT '变动后可用余额，三位小数',
    created_at    TIMESTAMP    NOT NULL COMMENT '台账记录时间'
);
CREATE INDEX IF NOT EXISTS idx_lot_ledger_lot ON lot_ledger (lot_id, id);

-- 隔离事件历史（不可变）：SET 设置 / RELEASE 解除，每次调用均追加。
CREATE TABLE IF NOT EXISTS quarantine_event (
    id         BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '事件自增主键',
    lot_id     VARCHAR(64) NOT NULL COMMENT '被直接操作的批次',
    action     VARCHAR(8)  NOT NULL COMMENT 'SET 设置隔离 / RELEASE 解除隔离',
    reason     VARCHAR(255) COMMENT '隔离/解除原因，可空',
    created_at TIMESTAMP    NOT NULL COMMENT '事件提交时间'
);
CREATE INDEX IF NOT EXISTS idx_q_event_lot ON quarantine_event (lot_id, id);

-- 当前仍生效的隔离来源集合：(lot_id, source_lot_id) 表示 lot_id 因祖先/自身
-- source_lot_id 处于隔离而被隔离；集合为空即 AVAILABLE。
CREATE TABLE IF NOT EXISTS lot_quarantine (
    lot_id      VARCHAR(64) NOT NULL COMMENT '被隔离的批次',
    source_lot_id VARCHAR(64) NOT NULL COMMENT '隔离来源批次（自身或祖先）',
    created_at  TIMESTAMP    NOT NULL COMMENT '该来源传播生效时间',
    CONSTRAINT pk_lot_quarantine PRIMARY KEY (lot_id, source_lot_id)
);
CREATE INDEX IF NOT EXISTS idx_lot_q_source ON lot_quarantine (source_lot_id);
