-- 校车线路安全服务建表脚本（H2，MODE=MySQL；时间均为 UTC，日期为 UTC 日界）
CREATE TABLE IF NOT EXISTS vehicle (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  plate_no VARCHAR(32) NOT NULL UNIQUE COMMENT '车牌号（合成数据）',
  seats INT NOT NULL COMMENT '座位数，上限 56，须不小于线路学生数'
);

CREATE TABLE IF NOT EXISTS student (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  student_no VARCHAR(32) NOT NULL UNIQUE COMMENT '学号（合成数据）',
  name VARCHAR(64) NOT NULL COMMENT '姓名（合成数据）'
);

CREATE TABLE IF NOT EXISTS route (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  code VARCHAR(32) NOT NULL UNIQUE COMMENT '线路编码',
  vehicle_id BIGINT NULL COMMENT '候选车辆 ID；为空表示尚未指定车辆',
  status VARCHAR(16) NOT NULL DEFAULT 'APPLIED' COMMENT '线路状态：APPLIED 已申请 / RUNNING 执行中 / STOPPED 已停运',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）'
);

-- 同一时刻一辆车只允许一条执行中线路：由生成列 + 唯一索引强制（NULL 不参与唯一约束）
ALTER TABLE route ADD COLUMN IF NOT EXISTS running_vehicle_id BIGINT
  GENERATED ALWAYS AS (CASE WHEN status = 'RUNNING' THEN vehicle_id ELSE NULL END);
CREATE UNIQUE INDEX IF NOT EXISTS uk_route_running_vehicle ON route (running_vehicle_id);

CREATE TABLE IF NOT EXISTS route_student (
  route_id BIGINT NOT NULL COMMENT '线路 ID',
  student_id BIGINT NOT NULL COMMENT '学生 ID',
  PRIMARY KEY (route_id, student_id)
);

CREATE TABLE IF NOT EXISTS assessment (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  route_id BIGINT NOT NULL COMMENT '线路 ID',
  assess_date DATE NOT NULL COMMENT '评估日期（UTC 日界）；同线路同日期仅允许一次评估',
  request_id VARCHAR(64) NOT NULL COMMENT '幂等请求 ID',
  road_grade INT NOT NULL COMMENT '道路等级，下限 3，恰等通过',
  stop_count INT NOT NULL COMMENT '站点数',
  min_distance_m INT NOT NULL COMMENT '相邻站点最小间距（米），下限 500，恰等通过',
  max_distance_m INT NOT NULL COMMENT '相邻站点最大间距（米），上限 3000，恰等通过',
  vehicle_id BIGINT NOT NULL COMMENT '候选车辆 ID',
  vehicle_seats INT NOT NULL COMMENT '候选车辆座位数',
  student_count INT NOT NULL COMMENT '线路学生数，座位数须不小于该值',
  passed BOOLEAN NOT NULL COMMENT '是否通过',
  reject_reasons VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '拒绝原因，分号分隔；通过时为空串',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
  CONSTRAINT uk_assessment_route_date UNIQUE (route_id, assess_date)
);

CREATE TABLE IF NOT EXISTS qualification (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  route_id BIGINT NOT NULL UNIQUE COMMENT '线路 ID，一条线路一份有效资格',
  valid_from DATE NOT NULL COMMENT '生效日期（UTC）',
  valid_to DATE NOT NULL COMMENT '到期日期（UTC）；恰满该日 24:00（UTC 日界）失效',
  status VARCHAR(16) NOT NULL COMMENT '资格状态：ACTIVE 有效 / EXPIRED 已失效',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间（UTC）'
);

CREATE TABLE IF NOT EXISTS qualification_renewal (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  qualification_id BIGINT NOT NULL COMMENT '资格 ID',
  apply_date DATE NOT NULL COMMENT '申请日期（UTC）；须在到期前 30 日内，恰第 30 日受理',
  old_valid_to DATE NOT NULL COMMENT '续期前到期日',
  new_valid_to DATE NOT NULL COMMENT '续期后到期日（顺延 1 年）',
  request_id VARCHAR(64) NOT NULL COMMENT '幂等请求 ID',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）'
);

CREATE TABLE IF NOT EXISTS check_in (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  route_id BIGINT NOT NULL COMMENT '线路 ID',
  student_id BIGINT NOT NULL COMMENT '学生 ID',
  check_date DATE NOT NULL COMMENT '打卡日期（UTC 日界）',
  request_id VARCHAR(64) NOT NULL COMMENT '幂等请求 ID',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '打卡时间（UTC）',
  CONSTRAINT uk_checkin_student_date UNIQUE (student_id, check_date)
);

CREATE TABLE IF NOT EXISTS notification (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  route_id BIGINT NOT NULL COMMENT '线路 ID',
  student_id BIGINT NOT NULL COMMENT '学生 ID',
  notify_date DATE NOT NULL COMMENT '通知日期（UTC 日界）',
  type VARCHAR(32) NOT NULL COMMENT '通知类型：MISSED_RIDE 漏乘 / ROUTE_STOPPED_REASSIGN 停运改派',
  message VARCHAR(500) NOT NULL COMMENT '合成通知内容',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
  CONSTRAINT uk_notification UNIQUE (route_id, student_id, notify_date, type)
);

CREATE TABLE IF NOT EXISTS idempotency_record (
  request_id VARCHAR(64) PRIMARY KEY COMMENT '幂等请求 ID',
  fingerprint VARCHAR(128) NOT NULL COMMENT '指纹：requestId 与全部业务字段的 SHA-256；同键异参返回 409',
  http_status INT NULL COMMENT '首次执行的 HTTP 状态码；NULL 表示执行中',
  response_body CLOB NULL COMMENT '首次执行的响应体（JSON）',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）'
);

CREATE TABLE IF NOT EXISTS route_status_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  route_id BIGINT NOT NULL COMMENT '线路 ID',
  from_status VARCHAR(16) NOT NULL COMMENT '变更前状态',
  to_status VARCHAR(16) NOT NULL COMMENT '变更后状态',
  reason VARCHAR(500) NOT NULL COMMENT '变更原因（留痕）',
  changed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '变更时间（UTC）'
);

CREATE TABLE IF NOT EXISTS reassignment (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  route_id BIGINT NOT NULL COMMENT '停运线路 ID',
  student_id BIGINT NOT NULL COMMENT '被改派学生 ID',
  stop_date DATE NOT NULL COMMENT '停运日期（UTC 日界）',
  target_route_id BIGINT NULL COMMENT '改派目标线路 ID；NULL 表示无可接收线路，状态为 UNASSIGNED',
  status VARCHAR(16) NOT NULL COMMENT '改派状态：ASSIGNED 已改派 / UNASSIGNED 待改派',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
  CONSTRAINT uk_reassignment UNIQUE (route_id, student_id, stop_date)
);

CREATE TABLE IF NOT EXISTS daily_settlement (
  settle_date DATE NOT NULL COMMENT '日结日期（UTC 日界）',
  route_id BIGINT NOT NULL COMMENT '线路 ID',
  reassigned_count INT NOT NULL COMMENT '当日改派学生数（可复算）',
  notified_count INT NOT NULL COMMENT '当日通知数（可复算）',
  computed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '计算时间（UTC）',
  PRIMARY KEY (settle_date, route_id)
);
