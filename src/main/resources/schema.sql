-- 粮情测控与通风作业联动服务建表脚本（H2 MySQL 兼容模式，可重复执行）
-- 时间字段均为本地时间，时区 Asia/Shanghai；金额/数值单位见各列注释。

CREATE TABLE IF NOT EXISTS silo (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    code VARCHAR(64) NOT NULL COMMENT '粮仓编码，全局唯一',
    name VARCHAR(128) NOT NULL COMMENT '粮仓名称',
    safe_temperature DECIMAL(5,2) NOT NULL COMMENT '安全温度，单位℃；任一点粮温超过即告警，恰等判正常',
    equilibrium_humidity DECIMAL(5,2) NOT NULL COMMENT '粮堆平衡湿度，单位%RH；外界湿度高于此值禁止通风，恰等允许',
    status VARCHAR(32) NOT NULL DEFAULT 'MONITORING' COMMENT '粮仓状态：MONITORING日常监测/ALARMING异常告警/VENTILATING通风作业/RECHECKING效果复测/MANUAL_HANDLING人工处置/FUMIGATING熏蒸散气作业中(禁止人员进入与普通通风)/ARCHIVED已归档(终态)',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，时区Asia/Shanghai',
    CONSTRAINT uk_silo_code UNIQUE (code)
) COMMENT='粮仓';

CREATE TABLE IF NOT EXISTS fan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    code VARCHAR(64) NOT NULL COMMENT '风机编码，全局唯一',
    name VARCHAR(128) NOT NULL COMMENT '风机名称',
    status VARCHAR(16) NOT NULL DEFAULT 'IDLE' COMMENT '风机状态：IDLE空闲/LOCKED已锁定；同一时刻只服务一个仓',
    locked_job_id BIGINT NULL COMMENT '当前锁定的作业ID（通风作业或熏蒸单）；NULL表示空闲',
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
    event_type VARCHAR(32) NOT NULL COMMENT '事件类型：LATE_READING归档后迟到温湿度数据/LATE_GAS_READING熏蒸解除后迟到气体读数',
    payload VARCHAR(512) NOT NULL COMMENT '迟到数据摘要',
    recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间，时区Asia/Shanghai',
    CONSTRAINT uk_late_request UNIQUE (request_id)
) COMMENT='归档后迟到事件';

CREATE TABLE IF NOT EXISTS fumigation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    silo_id BIGINT NOT NULL COMMENT '所属粮仓ID',
    window_start TIMESTAMP NOT NULL COMMENT '作业窗起点(含)，时区UTC；半开区间[start,end)，与普通通风窗按半开区间互斥',
    window_end TIMESTAMP NOT NULL COMMENT '作业窗终点(不含)，时区UTC；半开区间[start,end)，端点相接可共存',
    safety_threshold DECIMAL(12,4) NOT NULL COMMENT '合成安全阈值，单位ppm(合成规则，非真实药剂)；读数≤阈值判安全，恰等合法',
    plan_steps VARCHAR(1024) NOT NULL COMMENT '合成熏蒸方案步骤JSON数组；仅合成数据，不计算真实药剂剂量',
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING_APPROVAL' COMMENT '状态：PENDING_APPROVAL待审批/APPROVED已批准/FUMIGATING熏蒸中/AERATING散气中/RELEASED已解除(终态)',
    version INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本：从1开始，每次成功变更加1，修改须携带expectedVersion',
    frozen_version INT NULL COMMENT '开始熏蒸时冻结的方案版本；NULL表示尚未开始',
    fan_id BIGINT NULL COMMENT '散气绑定的风机ID；NULL表示未绑定',
    prev_silo_status VARCHAR(32) NULL COMMENT '锁仓前的粮仓状态，解除时恢复；NULL表示未锁仓',
    release_reviewer1 VARCHAR(64) NULL COMMENT '第一名解除复核人；NULL表示尚无复核人确认',
    started_at TIMESTAMP NULL COMMENT '熏蒸开始时间，时区UTC；NULL表示未开始',
    aeration_started_at TIMESTAMP NULL COMMENT '散气开始时间，时区UTC；NULL表示未转散气',
    released_at TIMESTAMP NULL COMMENT '解除时间，时区UTC；NULL表示未解除',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，时区Asia/Shanghai'
) COMMENT='熏蒸作业单';

CREATE TABLE IF NOT EXISTS fumigation_approval (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    fumigation_id BIGINT NOT NULL COMMENT '所属熏蒸单ID',
    approver VARCHAR(64) NOT NULL COMMENT '审批人标识；同一单须两名不同审批人',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '审批时间，时区Asia/Shanghai',
    CONSTRAINT uk_approval_approver UNIQUE (fumigation_id, approver)
) COMMENT='熏蒸审批记录';

CREATE TABLE IF NOT EXISTS fumigation_request (
    request_id VARCHAR(64) PRIMARY KEY COMMENT '幂等请求ID，全局唯一；失败请求不占键',
    action VARCHAR(32) NOT NULL COMMENT '写操作类型：CREATE/APPROVE/START/FINISH/RELEASE_CONFIRM',
    fumigation_id BIGINT NULL COMMENT '操作的熏蒸单ID；NULL表示CREATE占位尚未落单',
    fingerprint VARCHAR(64) NOT NULL COMMENT '幂等指纹：requestId与全部业务字段(含版本、集合、精确时刻)的SHA-256',
    response_body VARCHAR(8192) NOT NULL COMMENT '首次成功结果的冻结JSON，重放原样返回',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间，时区Asia/Shanghai'
) COMMENT='熏蒸写操作幂等记录';

CREATE TABLE IF NOT EXISTS gas_reading (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    fumigation_id BIGINT NOT NULL COMMENT '所属熏蒸单ID',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求ID，全局唯一',
    seq INT NOT NULL COMMENT '读数序号，从1开始；同一单内唯一，缺号不视为安全',
    concentration DECIMAL(12,4) NOT NULL COMMENT '合成气体浓度，单位ppm(合成规则)；有限非负小数',
    measured_at TIMESTAMP NOT NULL COMMENT '采样时刻，时区UTC；同一单内严格递增',
    fingerprint VARCHAR(64) NOT NULL COMMENT '幂等指纹：requestId与全部业务字段的SHA-256',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间，时区Asia/Shanghai',
    CONSTRAINT uk_gas_request UNIQUE (request_id),
    CONSTRAINT uk_gas_seq UNIQUE (fumigation_id, seq)
) COMMENT='散气合成气体读数';
