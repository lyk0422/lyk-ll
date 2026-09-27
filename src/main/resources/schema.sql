-- 粮情测控与通风作业联动服务建表脚本（H2 MySQL 兼容模式，可重复执行）
-- 时间字段均为本地时间，时区 Asia/Shanghai；金额/数值单位见各列注释。

CREATE TABLE IF NOT EXISTS silo (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    code VARCHAR(64) NOT NULL COMMENT '粮仓编码，全局唯一',
    name VARCHAR(128) NOT NULL COMMENT '粮仓名称',
    safe_temperature DECIMAL(5,2) NOT NULL COMMENT '安全温度，单位℃；任一点粮温超过即告警，恰等判正常',
    equilibrium_humidity DECIMAL(5,2) NOT NULL COMMENT '粮堆平衡湿度，单位%RH；外界湿度高于此值禁止通风，恰等允许',
    status VARCHAR(32) NOT NULL DEFAULT 'MONITORING' COMMENT '粮仓状态：MONITORING日常监测/ALARMING异常告警/VENTILATING通风作业/RECHECKING效果复测/MANUAL_HANDLING人工处置/FUMIGATING熏蒸作业/AERATING散气作业/ARCHIVED已归档(终态)',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，时区Asia/Shanghai',
    CONSTRAINT uk_silo_code UNIQUE (code)
) COMMENT='粮仓';

CREATE TABLE IF NOT EXISTS fan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    code VARCHAR(64) NOT NULL COMMENT '风机编码，全局唯一',
    name VARCHAR(128) NOT NULL COMMENT '风机名称',
    status VARCHAR(16) NOT NULL DEFAULT 'IDLE' COMMENT '风机状态：IDLE空闲/LOCKED已锁定；同一时刻只服务一个仓',
    locked_job_id BIGINT NULL COMMENT '当前锁定的通风作业ID；NULL表示未锁定通风作业',
    locked_fumigation_id BIGINT NULL COMMENT '当前锁定的熏蒸单ID；NULL表示未锁定熏蒸单',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，时区Asia/Shanghai',
    CONSTRAINT uk_fan_code UNIQUE (code)
) COMMENT='风机';

CREATE TABLE IF NOT EXISTS sensor_reading (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    silo_id BIGINT NOT NULL COMMENT '所属粮仓ID',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求ID，全局唯一',
    point_no INT NOT NULL COMMENT '测温点编号',
    temperature DECIMAL(5,2) NOT NULL COMMENT '粮温，单位℃',
    outside_humidity DECIMAL(5,2) NULL COMMENT '外界湿度，单位%RH；NULL表示本次未上报',
    measured_at TIMESTAMP NOT NULL COMMENT '采样时间，时区Asia/Shanghai',
    alarm_id BIGINT NULL COMMENT '触发告警的告警ID；NULL表示本次判正常',
    fingerprint VARCHAR(64) NOT NULL COMMENT '幂等指纹：requestId与全部业务字段的SHA-256',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间，时区Asia/Shanghai',
    CONSTRAINT uk_reading_request UNIQUE (request_id)
) COMMENT='温湿度采集记录';

CREATE TABLE IF NOT EXISTS alarm (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    silo_id BIGINT NOT NULL COMMENT '所属粮仓ID',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求ID，与采集记录一致，全局唯一',
    point_no INT NOT NULL COMMENT '超温测温点编号',
    temperature DECIMAL(5,2) NOT NULL COMMENT '告警粮温，单位℃',
    safe_temperature DECIMAL(5,2) NOT NULL COMMENT '告警时安全温度阈值，单位℃',
    fingerprint VARCHAR(64) NOT NULL COMMENT '幂等指纹：requestId与全部业务字段的SHA-256',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '告警时间，时区Asia/Shanghai',
    CONSTRAINT uk_alarm_request UNIQUE (request_id)
) COMMENT='粮温异常告警';

CREATE TABLE IF NOT EXISTS ventilation_job (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    silo_id BIGINT NOT NULL COMMENT '所属粮仓ID',
    fan_id BIGINT NOT NULL COMMENT '占用风机ID',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求ID，全局唯一',
    purpose VARCHAR(32) NOT NULL COMMENT '通风目的：COOLING降温/DEHUMIDIFYING降水/CONDITIONING调质',
    window_start TIMESTAMP NOT NULL COMMENT '作业窗起点(含)，时区Asia/Shanghai；半开区间[start,end)',
    window_end TIMESTAMP NOT NULL COMMENT '作业窗终点(不含)，时区Asia/Shanghai；半开区间[start,end)',
    outside_humidity DECIMAL(5,2) NOT NULL COMMENT '下达时外界湿度，单位%RH',
    round_no INT NOT NULL COMMENT '通风轮次：1或2，最多两轮',
    status VARCHAR(16) NOT NULL DEFAULT 'EXECUTING' COMMENT '作业状态：EXECUTING执行中/COMPLETED已完成/ARCHIVED已归档(终态)',
    fingerprint VARCHAR(64) NOT NULL COMMENT '幂等指纹：requestId与全部业务字段的SHA-256',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下达时间，时区Asia/Shanghai',
    completed_at TIMESTAMP NULL COMMENT '完成时间；NULL表示未完成',
    archived_at TIMESTAMP NULL COMMENT '归档时间；NULL表示未归档',
    CONSTRAINT uk_job_request UNIQUE (request_id)
) COMMENT='通风作业';

CREATE TABLE IF NOT EXISTS recheck (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    job_id BIGINT NOT NULL COMMENT '所属通风作业ID',
    silo_id BIGINT NOT NULL COMMENT '所属粮仓ID',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求ID，全局唯一',
    gradient DECIMAL(5,2) NOT NULL COMMENT '复测粮温梯度，单位℃',
    gradient_limit DECIMAL(5,2) NOT NULL COMMENT '梯度限值，单位℃；超过判超标，恰等判合格',
    exceeded BOOLEAN NOT NULL COMMENT '是否超标：TRUE超标/FALSE合格',
    decision VARCHAR(32) NOT NULL COMMENT '处置决策：PASSED合格待归档/SECOND_VENTILATION二次通风/MANUAL_HANDLING转人工处置',
    fingerprint VARCHAR(64) NOT NULL COMMENT '幂等指纹：requestId与全部业务字段的SHA-256',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '复测时间，时区Asia/Shanghai',
    CONSTRAINT uk_recheck_request UNIQUE (request_id)
) COMMENT='通风效果复测';

CREATE TABLE IF NOT EXISTS late_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    silo_id BIGINT NOT NULL COMMENT '所属粮仓ID',
    request_id VARCHAR(64) NOT NULL COMMENT '迟到数据携带的请求ID，全局唯一',
    event_type VARCHAR(32) NOT NULL COMMENT '事件类型：LATE_READING归档后迟到温湿度数据/LATE_FUMIGATION_READING熏蒸解除后迟到气体读数',
    payload VARCHAR(512) NOT NULL COMMENT '迟到数据摘要',
    recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间，时区Asia/Shanghai',
    CONSTRAINT uk_late_request UNIQUE (request_id)
) COMMENT='归档后迟到事件';

CREATE TABLE IF NOT EXISTS fumigation_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    silo_id BIGINT NOT NULL COMMENT '所属粮仓ID',
    request_id VARCHAR(64) NOT NULL COMMENT '创建幂等请求ID，全局唯一',
    window_start TIMESTAMP NOT NULL COMMENT '熏蒸作业窗起点(含)，UTC；半开区间[start,end)',
    window_end TIMESTAMP NOT NULL COMMENT '熏蒸作业窗终点(不含)，UTC；半开区间[start,end)',
    safe_threshold DECIMAL(12,4) NOT NULL COMMENT '合成气体安全阈值，无量纲合成浓度单位；读数≤阈值判安全，恰等合法',
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING_APPROVAL' COMMENT '熏蒸单状态：PENDING_APPROVAL待批准/APPROVED已批准/FUMIGATING熏蒸中/AERATING散气中/RELEASED已解除(终态)',
    version INT NOT NULL DEFAULT 1 COMMENT '乐观版本号，从1开始，每次成功变更加1',
    fan_id BIGINT NULL COMMENT '散气阶段绑定的风机ID；NULL表示未进入散气',
    prior_silo_status VARCHAR(32) NULL COMMENT '锁仓前粮仓状态，解除时恢复；NULL表示尚未开始',
    frozen_plan_version INT NULL COMMENT '开始熏蒸时冻结的方案版本号；NULL表示尚未开始',
    approver_count INT NOT NULL DEFAULT 0 COMMENT '已批准人数，两名不同审批人批准后进入APPROVED',
    release_reviewer_one VARCHAR(64) NULL COMMENT '解除复核人一；NULL表示未解除',
    release_reviewer_two VARCHAR(64) NULL COMMENT '解除复核人二；NULL表示未解除',
    fingerprint VARCHAR(64) NOT NULL COMMENT '创建幂等指纹：requestId与全部业务字段的结构化SHA-256',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '申请时间，时区Asia/Shanghai',
    started_at TIMESTAMP NULL COMMENT '开始熏蒸时间，UTC；NULL表示未开始',
    finished_at TIMESTAMP NULL COMMENT '结束熏蒸转散气时间，UTC；NULL表示未结束',
    released_at TIMESTAMP NULL COMMENT '解除时间，UTC；NULL表示未解除',
    CONSTRAINT uk_fumigation_request UNIQUE (request_id)
) COMMENT='熏蒸作业单';

CREATE TABLE IF NOT EXISTS fumigation_approval (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    fumigation_id BIGINT NOT NULL COMMENT '所属熏蒸单ID',
    approver VARCHAR(64) NOT NULL COMMENT '审批人标识；同一单内两名审批人必须不同',
    request_id VARCHAR(64) NOT NULL COMMENT '本次批准的幂等请求ID',
    decided_at TIMESTAMP NOT NULL COMMENT '批准时刻，UTC，由请求方携带的精确时刻',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间，时区Asia/Shanghai',
    CONSTRAINT uk_approval_approver UNIQUE (fumigation_id, approver)
) COMMENT='熏蒸批准记录';

CREATE TABLE IF NOT EXISTS fumigation_reading (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    fumigation_id BIGINT NOT NULL COMMENT '所属熏蒸单ID',
    request_id VARCHAR(64) NOT NULL COMMENT '采样幂等请求ID',
    seq_no INT NOT NULL COMMENT '读数序号；与单号联合唯一，缺号不视为安全',
    concentration DECIMAL(12,4) NOT NULL COMMENT '合成气体浓度，有限非负小数，无量纲合成单位',
    measured_at TIMESTAMP NOT NULL COMMENT '采样时刻，UTC；同一单内严格递增',
    fingerprint VARCHAR(64) NOT NULL COMMENT '采样幂等指纹：requestId与全部业务字段的结构化SHA-256',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间，时区Asia/Shanghai',
    CONSTRAINT uk_fum_reading_seq UNIQUE (fumigation_id, seq_no)
) COMMENT='散气合成气体读数';

CREATE TABLE IF NOT EXISTS fumigation_idempotency (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求ID，全局唯一',
    operation VARCHAR(16) NOT NULL COMMENT '操作类型：CREATE/APPROVE/START/FINISH/SAMPLE/RELEASE',
    fumigation_id BIGINT NOT NULL COMMENT '所属熏蒸单ID',
    fingerprint VARCHAR(64) NOT NULL COMMENT '幂等指纹：requestId与完整参数(含版本、集合、精确时刻)的结构化SHA-256',
    response_body VARCHAR(2048) NOT NULL COMMENT '首次成功响应的冻结JSON，重放时原样返回',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间，时区Asia/Shanghai',
    CONSTRAINT uk_fum_idem_request UNIQUE (request_id)
) COMMENT='熏蒸写操作幂等记录';
