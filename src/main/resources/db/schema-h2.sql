-- 自动驾驶任务执行审计平台建表脚本（H2，MODE=MySQL）
-- 时间列均为 UTC 时间戳；空值含义见各列注释。

CREATE TABLE IF NOT EXISTS task_run (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    request_id      VARCHAR(128) COMMENT '幂等请求键；为空表示显式重放之外的内部任务',
    request_hash    VARCHAR(64)  COMMENT '创建参数（绑定版本与种子）摘要，用于同键异参冲突判定',
    code_version    VARCHAR(255) COMMENT '绑定的代码版本（提交哈希或版本标签）',
    config_version  VARCHAR(255) COMMENT '绑定的配置版本或内容摘要',
    map_version     VARCHAR(255) COMMENT '绑定的地图版本或内容摘要',
    rosbag_version  VARCHAR(255) COMMENT '绑定的输入 rosbag 版本或内容摘要',
    seed            BIGINT       COMMENT '随机种子；相同绑定与种子须确定性重放',
    metrics_profile VARCHAR(32)  COMMENT '指标口径：default 或 extended；仅影响合成指标集合',
    fault_plan      VARCHAR(64)  COMMENT '合成故障注入：fail:<阶段> 或 interrupt:<阶段>，仅首批执行生效，模拟可恢复瞬时故障',
    state           VARCHAR(16)  COMMENT '任务状态：PENDING/RUNNING/SUCCESS/FAILED/INTERRUPTED',
    result_hash     VARCHAR(64)  COMMENT '全部阶段成功后的结果哈希；未完成为空',
    error_message   CLOB         COMMENT '任务级失败或中断原因；成功时为空',
    created_at      TIMESTAMP(6) COMMENT '创建时刻(UTC)',
    started_at      TIMESTAMP(6) COMMENT '首次开始执行时刻(UTC)；未开始为空',
    finished_at     TIMESTAMP(6) COMMENT '终态时刻(UTC)；未结束为空',
    CONSTRAINT uk_task_request_id UNIQUE (request_id)
);

CREATE TABLE IF NOT EXISTS task_stage (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    task_id          BIGINT NOT NULL COMMENT '所属任务 ID',
    stage            VARCHAR(32) NOT NULL COMMENT '阶段代码：perception/localization/planning/control',
    attempt          INT NOT NULL COMMENT '执行批次；初始为 1，恢复重跑递增',
    state            VARCHAR(16) NOT NULL COMMENT '阶段状态：PENDING/RUNNING/SUCCESS/FAILED/INTERRUPTED',
    started_at       TIMESTAMP(6) COMMENT '阶段开始时刻(UTC)；未开始为空',
    finished_at      TIMESTAMP(6) COMMENT '阶段终态时刻(UTC)；未结束为空',
    metrics_json     CLOB COMMENT '关键指标 JSON 文本；未产出为空',
    error_message    CLOB COMMENT '阶段失败或中断原因；成功时为空',
    artifact_path    VARCHAR(512) COMMENT '产物相对路径；未产出为空',
    artifact_sha256  VARCHAR(64) COMMENT '产物内容 SHA-256；未产出为空',
    output_digest    VARCHAR(64) COMMENT '阶段输出摘要，供下游与检查点核对',
    replay_command   CLOB COMMENT '可重放命令文本',
    CONSTRAINT uk_task_stage UNIQUE (task_id, stage, attempt)
);

CREATE TABLE IF NOT EXISTS task_checkpoint (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    task_id          BIGINT NOT NULL COMMENT '所属任务 ID',
    stage            VARCHAR(32) NOT NULL COMMENT '检查点对应阶段',
    attempt          INT NOT NULL COMMENT '检查点对应执行批次',
    code_version     VARCHAR(255) NOT NULL COMMENT '检查点时刻绑定的代码版本',
    config_version   VARCHAR(255) NOT NULL COMMENT '检查点时刻绑定的配置版本',
    map_version      VARCHAR(255) NOT NULL COMMENT '检查点时刻绑定的地图版本',
    rosbag_version   VARCHAR(255) NOT NULL COMMENT '检查点时刻绑定的输入 rosbag 版本',
    seed             BIGINT NOT NULL COMMENT '检查点时刻绑定的随机种子',
    upstream_digest  VARCHAR(64) NOT NULL COMMENT '上游产物摘要；首阶段为固定根摘要',
    output_digest    VARCHAR(64) NOT NULL COMMENT '本阶段输出摘要',
    artifact_sha256  VARCHAR(64) NOT NULL COMMENT '本阶段产物 SHA-256',
    metrics_json     CLOB NOT NULL COMMENT '本阶段关键指标 JSON 文本',
    created_at       TIMESTAMP(6) NOT NULL COMMENT '检查点创建时刻(UTC)',
    CONSTRAINT uk_checkpoint_task_stage UNIQUE (task_id, stage)
);

CREATE INDEX IF NOT EXISTS idx_stage_task ON task_stage (task_id);
CREATE INDEX IF NOT EXISTS idx_checkpoint_task ON task_checkpoint (task_id);
