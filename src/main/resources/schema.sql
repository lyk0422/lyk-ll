-- 放疗计划剂量审核与分次执行核对服务：H2 内存库（MODE=MySQL）建表脚本
-- 所有剂量单位均为 cGy；时间列为 Asia/Shanghai 时区本地时间；仅保存合成数据。

CREATE TABLE IF NOT EXISTS patient (
    patient_id   VARCHAR(32)  NOT NULL PRIMARY KEY COMMENT '患者ID（合成，P开头）',
    patient_name VARCHAR(64)  NOT NULL COMMENT '患者姓名（合成）',
    created_at   TIMESTAMP    NOT NULL COMMENT '建档时间（Asia/Shanghai）'
);
COMMENT ON TABLE patient IS '患者表（合成数据）';

CREATE TABLE IF NOT EXISTS constraint_template (
    template_code VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '约束模板编码',
    template_name VARCHAR(64) NOT NULL COMMENT '约束模板名称',
    created_at    TIMESTAMP   NOT NULL COMMENT '创建时间（Asia/Shanghai）'
);
COMMENT ON TABLE constraint_template IS '危及器官剂量体积约束模板（合成参考数据）';

CREATE TABLE IF NOT EXISTS constraint_item (
    id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    template_code VARCHAR(32) NOT NULL COMMENT '所属约束模板编码',
    organ_code   VARCHAR(32)  NOT NULL COMMENT '危及器官编码，如 SPINAL_CORD',
    organ_name   VARCHAR(64)  NOT NULL COMMENT '危及器官名称',
    metric_type  VARCHAR(24)  NOT NULL COMMENT '指标类型：DMAX_CGY=最大剂量(cGy)；V_DOSE_PCT=不低于参照剂量的体积百分比(%)',
    metric_ref   BIGINT       NULL COMMENT '参照剂量(cGy)，V_DOSE_PCT 使用，DMAX_CGY 时为空',
    limit_value  DECIMAL(10,2) NOT NULL COMMENT '限值：DMAX_CGY 单位 cGy，V_DOSE_PCT 单位 %；恰等合法',
    CONSTRAINT uk_constraint_item UNIQUE (template_code, organ_code, metric_type, metric_ref)
);
COMMENT ON TABLE constraint_item IS '约束模板明细：逐项剂量体积限值';

CREATE TABLE IF NOT EXISTS plan_version (
    id                          BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '计划版本ID',
    patient_id                  VARCHAR(32) NOT NULL COMMENT '患者ID',
    plan_code                   VARCHAR(32) NOT NULL COMMENT '计划业务编码（同计划多次改版共用）',
    version_no                  INT         NOT NULL COMMENT '版本号，从1递增',
    template_code               VARCHAR(32) NOT NULL COMMENT '使用的约束模板编码',
    prescribed_total_dose_cgy   BIGINT      NOT NULL COMMENT '处方总剂量(cGy)，须=分次剂量×次数',
    fraction_dose_cgy           BIGINT      NOT NULL COMMENT '单次分次剂量(cGy)',
    fraction_count              INT         NOT NULL COMMENT '处方分次次数',
    status                      VARCHAR(16) NOT NULL COMMENT '状态：DESIGN设计/REVIEWED物理审核通过/APPROVED医师批准/VERIFIED模体验证通过/EXECUTING分次执行中/CLOSED疗程结案/INVALID旧版失效',
    created_at                  TIMESTAMP   NOT NULL COMMENT '版本创建时间',
    reviewed_at                 TIMESTAMP   NULL COMMENT '物理审核通过时间，未审核为空',
    approved_at                 TIMESTAMP   NULL COMMENT '医师批准时间，未批准为空',
    verified_at                 TIMESTAMP   NULL COMMENT '首次模体验证通过时间，未通过为空',
    first_fraction_at           TIMESTAMP   NULL COMMENT '首次分次执行时间，未执行为空',
    closed_at                   TIMESTAMP   NULL COMMENT '疗程结案时间，未结案为空',
    invalidated_at              TIMESTAMP   NULL COMMENT '旧版本失效时间，未失效为空',
    CONSTRAINT uk_plan_version UNIQUE (patient_id, plan_code, version_no)
);
COMMENT ON TABLE plan_version IS '放疗计划版本表：设计→审核→批准→验证→执行→结案，换版旧版本置INVALID';

CREATE TABLE IF NOT EXISTS plan_oar_dose (
    id               BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    plan_version_id  BIGINT      NOT NULL COMMENT '所属计划版本ID',
    organ_code       VARCHAR(32) NOT NULL COMMENT '危及器官编码',
    metric_type      VARCHAR(24) NOT NULL COMMENT '指标类型：DMAX_CGY / V_DOSE_PCT',
    metric_ref       BIGINT      NULL COMMENT '参照剂量(cGy)，V_DOSE_PCT 使用',
    metric_value     DECIMAL(10,2) NOT NULL COMMENT '计划申报剂量值：DMAX_CGY 单位 cGy，V_DOSE_PCT 单位 %',
    CONSTRAINT uk_plan_oar_dose UNIQUE (plan_version_id, organ_code, metric_type, metric_ref)
);
COMMENT ON TABLE plan_oar_dose IS '计划申报的危及器官剂量体积数据，按模板逐项核对';

CREATE TABLE IF NOT EXISTS physics_audit (
    id                   BIGINT    NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    plan_version_id      BIGINT    NOT NULL COMMENT '通过审核的计划版本ID',
    request_id           VARCHAR(64) NOT NULL COMMENT '审核幂等 requestId',
    total_dose_check_ok  BOOLEAN   NOT NULL COMMENT '总剂量勾稽是否通过：分次剂量×次数=处方总剂量',
    passed               BOOLEAN   NOT NULL COMMENT '是否全部约束项通过（含恰等限值）',
    audited_at           TIMESTAMP NOT NULL COMMENT '审核通过时间',
    CONSTRAINT uk_physics_audit_version UNIQUE (plan_version_id),
    CONSTRAINT uk_physics_audit_request UNIQUE (request_id)
);
COMMENT ON TABLE physics_audit IS '物理审核记录（仅保存通过审核的快照，失败可由申报数据复算）';

CREATE TABLE IF NOT EXISTS physics_audit_item (
    id             BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    audit_id       BIGINT      NOT NULL COMMENT '所属物理审核记录ID',
    organ_code     VARCHAR(32) NOT NULL COMMENT '危及器官编码',
    organ_name     VARCHAR(64) NOT NULL COMMENT '危及器官名称',
    metric_type    VARCHAR(24) NOT NULL COMMENT '指标类型',
    metric_ref     BIGINT      NULL COMMENT '参照剂量(cGy)',
    planned_value  DECIMAL(10,2) NOT NULL COMMENT '计划申报值',
    limit_value    DECIMAL(10,2) NOT NULL COMMENT '模板限值',
    within_limit   BOOLEAN     NOT NULL COMMENT '是否未超限（恰等限值为合法true）'
);
COMMENT ON TABLE physics_audit_item IS '物理审核逐项结果快照，可与复算结果相互核对';

CREATE TABLE IF NOT EXISTS verification (
    id                 BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    plan_version_id    BIGINT      NOT NULL COMMENT '所属计划版本ID',
    request_id         VARCHAR(64) NOT NULL COMMENT '验证/业务幂等 requestId',
    planned_dose_cgy   BIGINT      NOT NULL COMMENT '模体计划参考剂量(cGy)',
    measured_dose_cgy  BIGINT      NOT NULL COMMENT '模体实测剂量(cGy)',
    deviation_pct      DECIMAL(10,4) NOT NULL COMMENT '剂量偏差百分比=|实测-计划|/计划×100，展示用',
    passed             BOOLEAN     NOT NULL COMMENT '偏差是否≤3%：恰3%合法true，>3%为false',
    created_at         TIMESTAMP   NOT NULL COMMENT '验证时间（失败也留痕，构成验证链）',
    CONSTRAINT uk_verification_request UNIQUE (request_id)
);
COMMENT ON TABLE verification IS '首次治疗前模体验证链：成功与失败记录均保存';

CREATE TABLE IF NOT EXISTS fraction_execution (
    id                    BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    patient_id            VARCHAR(32) NOT NULL COMMENT '患者ID',
    plan_code             VARCHAR(32) NOT NULL COMMENT '计划业务编码',
    plan_version_id       BIGINT      NOT NULL COMMENT '执行时所属计划版本ID',
    fraction_no           INT         NOT NULL COMMENT '分次号，从1连续，跳次拒绝',
    delivered_dose_cgy    BIGINT      NOT NULL COMMENT '实际投递剂量(cGy)',
    request_id            VARCHAR(64) NOT NULL COMMENT '分次记录幂等 requestId',
    executed_at           TIMESTAMP   NOT NULL COMMENT '执行时间（Asia/Shanghai）',
    CONSTRAINT uk_fraction_no UNIQUE (patient_id, plan_code, fraction_no),
    CONSTRAINT uk_fraction_request UNIQUE (request_id)
);
COMMENT ON TABLE fraction_execution IS '分次执行台账：患者+计划+分次号唯一去重';

CREATE TABLE IF NOT EXISTS late_fraction_event (
    id               BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    patient_id       VARCHAR(32) NOT NULL COMMENT '患者ID',
    plan_code        VARCHAR(32) NOT NULL COMMENT '计划业务编码',
    plan_version_id  BIGINT      NOT NULL COMMENT '迟到记录指向的计划版本ID',
    fraction_no      INT         NOT NULL COMMENT '迟到分次号（不入台账）',
    request_id       VARCHAR(64) NOT NULL COMMENT '迟到事件 requestId',
    created_at       TIMESTAMP   NOT NULL COMMENT '事件记录时间（结案后）',
    CONSTRAINT uk_late_fraction_request UNIQUE (request_id)
);
COMMENT ON TABLE late_fraction_event IS '疗程结案后迟到的分次事件，仅登记不执行';

CREATE TABLE IF NOT EXISTS patient_active_plan (
    patient_id      VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '患者ID，一人同时刻仅一个可执行计划',
    plan_code       VARCHAR(32) NOT NULL COMMENT '当前可执行计划编码',
    plan_version_id BIGINT      NOT NULL COMMENT '当前可执行计划版本ID',
    status          VARCHAR(16) NOT NULL COMMENT 'ACTIVE执行中 / CLOSED已结案终态',
    updated_at      TIMESTAMP   NOT NULL COMMENT '最近换版/结案时间'
);
COMMENT ON TABLE patient_active_plan IS '患者在执行计划守卫表，主键保证并发批准唯一成功';

CREATE TABLE IF NOT EXISTS idempotent_request (
    request_id    VARCHAR(64)  NOT NULL PRIMARY KEY COMMENT '业务幂等键 requestId',
    operation     VARCHAR(32)  NOT NULL COMMENT '操作类型：AUDIT/APPROVAL/VERIFICATION/FRACTION/CLOSURE/DEVICE_REGISTER/DEVICE_UPDATE/SCHEDULE_PUBLISH/SCHEDULE_CANCEL/TREATMENT',
    fingerprint   VARCHAR(64)  NOT NULL COMMENT 'SHA-256指纹，覆盖requestId与全部业务字段',
    http_status   INT          NOT NULL COMMENT '首次成功响应HTTP状态码',
    response_json CLOB         NOT NULL COMMENT '首次成功响应体JSON，重放原样返回',
    created_at    TIMESTAMP    NOT NULL COMMENT '首次请求时间'
);
COMMENT ON TABLE idempotent_request IS '审核/批准/验证/分次/结案/设备/排程/治疗幂等记录';

CREATE TABLE IF NOT EXISTS device (
    device_id   VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '设备ID（合成，DEV开头）',
    device_name VARCHAR(64) NOT NULL COMMENT '设备名称（合成）',
    max_level   INT         NOT NULL COMMENT '可执行复杂度等级上限：1-3；下调只阻断尚未开始项，不影响已执行实绩',
    version     INT         NOT NULL COMMENT '乐观锁版本：从1开始，每次成功变更加1',
    created_at  TIMESTAMP   NOT NULL COMMENT '登记时间（Asia/Shanghai）',
    updated_at  TIMESTAMP   NOT NULL COMMENT '最近变更时间（Asia/Shanghai）'
);
COMMENT ON TABLE device IS '合成放疗设备登记表：复杂度等级能力1-3';

CREATE TABLE IF NOT EXISTS plan_schedule (
    id              BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '排程批次ID',
    patient_id      VARCHAR(32) NOT NULL COMMENT '患者ID',
    plan_code       VARCHAR(32) NOT NULL COMMENT '计划业务编码',
    plan_version_id BIGINT      NOT NULL COMMENT '发布时冻结的计划版本ID',
    level           INT         NOT NULL COMMENT '复杂度等级1-3，对应每分次10/20/30分钟',
    device_id       VARCHAR(32) NOT NULL COMMENT '发布时冻结的设备ID',
    version         INT         NOT NULL COMMENT '排程版本：从1开始，成功变更（取消）加1',
    status          VARCHAR(16) NOT NULL COMMENT '状态：ACTIVE生效中/CANCELLED已取消',
    request_id      VARCHAR(64) NOT NULL COMMENT '发布幂等 requestId',
    created_at      TIMESTAMP   NOT NULL COMMENT '发布时间（Asia/Shanghai）',
    updated_at      TIMESTAMP   NOT NULL COMMENT '最近变更时间（Asia/Shanghai）'
);
COMMENT ON TABLE plan_schedule IS '分次排程批次：发布即冻结计划版本/等级/设备/窗口，单次批量最多10个连续分次';

CREATE TABLE IF NOT EXISTS schedule_item (
    id                    BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '排程项ID',
    schedule_id           BIGINT      NOT NULL COMMENT '所属排程批次ID',
    patient_id            VARCHAR(32) NOT NULL COMMENT '患者ID',
    plan_code             VARCHAR(32) NOT NULL COMMENT '计划业务编码',
    plan_version_id       BIGINT      NOT NULL COMMENT '发布时冻结的计划版本ID',
    fraction_no           INT         NOT NULL COMMENT '分次号，批次内连续',
    device_id             VARCHAR(32) NOT NULL COMMENT '占用设备ID',
    level                 INT         NOT NULL COMMENT '发布时冻结的复杂度等级',
    window_start_utc      TIMESTAMP   NOT NULL COMMENT '占用窗口开始（UTC，半开区间含起点）',
    window_end_utc        TIMESTAMP   NOT NULL COMMENT '占用窗口结束（UTC，半开区间不含终点），长度=等级对应分钟数',
    status                VARCHAR(16) NOT NULL COMMENT '状态：SCHEDULED占用中/CANCELLED已取消释放/COMPLETED已完成释放',
    delivered_dose_cgy    BIGINT      NULL COMMENT '实绩投递剂量(cGy)，未完成为空',
    executed_at           TIMESTAMP   NULL COMMENT '实绩执行时间（UTC），未完成为空',
    fraction_execution_id BIGINT      NULL COMMENT '关联分次台账ID，未完成为空',
    created_at            TIMESTAMP   NOT NULL COMMENT '创建时间（Asia/Shanghai）',
    updated_at            TIMESTAMP   NOT NULL COMMENT '最近变更时间（Asia/Shanghai）'
);
COMMENT ON TABLE schedule_item IS '排程占用明细：同设备窗口重叠或同分次双占拒绝；完成/取消即释放占用';
