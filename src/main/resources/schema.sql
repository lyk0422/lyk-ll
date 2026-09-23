-- 自动驾驶任务执行审计平台：建表脚本
-- 兼容 H2(MODE=MySQL) 与 MySQL 语法：列内联 COMMENT、TEXT 大文本
-- 时间列统一使用 BIGINT 存储 epoch 毫秒；状态为枚举字符串
-- SHA-256 摘要固定 64 位十六进制；相对路径使用正斜杠

CREATE TABLE IF NOT EXISTS audit_task (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_code       VARCHAR(64)  NOT NULL COMMENT '任务业务编码（调用方给定）',
    request_id      VARCHAR(128) COMMENT '幂等请求ID；同键同参重放、同键异参409，空表示未启用幂等',
    status          VARCHAR(16)  NOT NULL COMMENT '任务状态：PENDING/RUNNING/SUCCESS/FAILED/INTERRUPTED',
    code_version    VARCHAR(256) NOT NULL COMMENT '感知/规划等代码版本摘要，必填',
    config_version  VARCHAR(256) NOT NULL COMMENT '参数配置版本摘要，必填',
    map_version     VARCHAR(256) NOT NULL COMMENT '地图版本摘要，必填',
    rosbag_version  VARCHAR(256) NOT NULL COMMENT '输入 rosbag 内容摘要，必填',
    seed            BIGINT       NOT NULL COMMENT '随机种子（整数），确定性重放依据',
    fail_stage      VARCHAR(32)  COMMENT '模拟注入：失败阶段名，空表示不注入',
    fail_times      INT          NOT NULL DEFAULT 0 COMMENT '模拟注入：该阶段前 N 次执行失败，恢复后成功',
    interrupt_after VARCHAR(32)  COMMENT '模拟注入：该阶段成功检查点之后中断任务，空表示不注入',
    result_hash     VARCHAR(64)  COMMENT '阶段摘要规范化后的 SHA-256，相同绑定与种子重放必须一致',
    error_message   TEXT         COMMENT '失败或中断原因文本',
    started_at      BIGINT       COMMENT '首次开始执行时间，epoch 毫秒，UTC 时钟',
    finished_at     BIGINT       COMMENT '进入终态（成功/失败/中断）时间，epoch 毫秒',
    created_at      BIGINT       NOT NULL,
    updated_at      BIGINT       NOT NULL,
    CONSTRAINT uk_task_request UNIQUE (request_id)
) COMMENT='审计任务主表：记录绑定版本、种子、整体状态与结果哈希';

CREATE TABLE IF NOT EXISTS task_stage (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id         BIGINT       NOT NULL,
    stage           VARCHAR(32)  NOT NULL COMMENT '阶段名：PERCEPTION/LOCALIZATION/PLANNING/CONTROL',
    seq_no          INT          NOT NULL COMMENT '阶段依赖顺序，0 起始，禁止跳阶段',
    status          VARCHAR(16)  NOT NULL COMMENT '阶段状态：PENDING/RUNNING/SUCCESS/FAILED/INTERRUPTED',
    attempt         INT          NOT NULL DEFAULT 0 COMMENT '该阶段累计执行次数（恢复重跑会递增）',
    started_at      BIGINT       COMMENT '阶段开始时间，epoch 毫秒',
    finished_at     BIGINT       COMMENT '阶段结束时间，epoch 毫秒',
    metrics_json    TEXT         COMMENT '关键指标 JSON，键为指标名、值为数值，失败时可能为空',
    error_message   TEXT         COMMENT '阶段失败原因',
    artifact_path   VARCHAR(512) COMMENT '阶段产物相对路径（相对产物根目录，正斜杠）',
    artifact_sha256 VARCHAR(64)  COMMENT '阶段产物文件实际 SHA-256',
    replay_command  VARCHAR(2048) COMMENT '可重放命令（含绑定版本、种子与上游摘要）',
    CONSTRAINT fk_stage_task FOREIGN KEY (task_id) REFERENCES audit_task (id),
    CONSTRAINT uk_stage_task UNIQUE (task_id, stage)
) COMMENT='任务阶段执行记录：每个阶段一行，按 seq_no 依赖顺序执行';

CREATE TABLE IF NOT EXISTS stage_checkpoint (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id               BIGINT       NOT NULL,
    stage                 VARCHAR(32)  NOT NULL,
    checkpoint_seq        INT          NOT NULL COMMENT '检查点序号，等于阶段 seq_no',
    code_version          VARCHAR(256) NOT NULL COMMENT '检查点绑定的代码版本',
    config_version        VARCHAR(256) NOT NULL COMMENT '检查点绑定的配置版本',
    map_version           VARCHAR(256) NOT NULL COMMENT '检查点绑定的地图版本',
    rosbag_version        VARCHAR(256) NOT NULL COMMENT '检查点绑定的输入 rosbag 版本',
    seed                  BIGINT       NOT NULL COMMENT '检查点绑定的随机种子',
    upstream_digests_json TEXT         NOT NULL COMMENT '全部上游阶段产物摘要 JSON（阶段名->SHA-256）',
    stage_output_digest   VARCHAR(64)  NOT NULL COMMENT '本阶段输出摘要，等于产物 SHA-256',
    artifact_path         VARCHAR(512) COMMENT '阶段产物相对路径',
    artifact_sha256       VARCHAR(64)  COMMENT '阶段产物 SHA-256',
    metrics_json          TEXT         COMMENT '成功时的关键指标 JSON',
    replay_command        VARCHAR(2048) COMMENT '可重放命令',
    created_at            BIGINT       NOT NULL COMMENT '检查点落库时间，epoch 毫秒',
    CONSTRAINT fk_cp_task FOREIGN KEY (task_id) REFERENCES audit_task (id),
    CONSTRAINT uk_cp UNIQUE (task_id, stage, checkpoint_seq)
) COMMENT='成功检查点：保存绑定版本、上游产物摘要链与阶段输出';

CREATE TABLE IF NOT EXISTS request_record (
    request_id    VARCHAR(128) PRIMARY KEY COMMENT '幂等请求ID，唯一',
    task_id       BIGINT      NOT NULL COMMENT '该请求首次成功落库对应的任务ID',
    request_hash  VARCHAR(64) NOT NULL COMMENT '不含 requestId 的规范化创建参数 SHA-256',
    request_json  TEXT        NOT NULL COMMENT '规范化创建参数 JSON',
    created_at    BIGINT      NOT NULL
) COMMENT='幂等请求记录：requestId 唯一，保存规范化参数哈希';
