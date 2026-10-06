-- 校车线路安全服务建表脚本（H2 MySQL 兼容模式）
-- 时间约定：所有 DATE 字段均为 UTC 日界；资格在 valid_to 当日 24:00（UTC）失效。

CREATE TABLE IF NOT EXISTS vehicle (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plate_no VARCHAR(32) NOT NULL COMMENT '车牌号，全局唯一',
    seat_capacity INT NOT NULL COMMENT '车辆座位数，安全评估要求不小于线路所需座位数',
    CONSTRAINT uk_vehicle_plate UNIQUE (plate_no)
) COMMENT '候选车辆';

CREATE TABLE IF NOT EXISTS route (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    route_code VARCHAR(32) NOT NULL COMMENT '线路编码，全局唯一',
    name VARCHAR(64) NOT NULL COMMENT '线路名称',
    vehicle_id BIGINT NOT NULL COMMENT '候选车辆ID，关联 vehicle.id',
    station_spacing_m INT NOT NULL COMMENT '站点间距，单位米，须位于 [500, 3000]，恰等限值通过',
    road_grade INT NOT NULL COMMENT '道路等级，数值越小等级越高，须 <= 3，恰等限值通过',
    required_seats INT NOT NULL COMMENT '线路所需座位数，须不超过车辆座位数，恰等限值通过',
    status VARCHAR(16) NOT NULL COMMENT '线路状态：APPLIED 已申请 / QUALIFIED 评估通过 / IN_PROGRESS 执行中 / SUSPENDED 停运',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，UTC',
    CONSTRAINT uk_route_code UNIQUE (route_code),
    CONSTRAINT fk_route_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicle (id)
) COMMENT '校车线路申请';

CREATE TABLE IF NOT EXISTS safety_assessment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    route_id BIGINT NOT NULL COMMENT '线路ID，关联 route.id',
    assessment_date DATE NOT NULL COMMENT '评估日期，UTC 日界；同线路同日期仅允许一次评估',
    spacing_ok BOOLEAN NOT NULL COMMENT '站点间距是否通过',
    road_ok BOOLEAN NOT NULL COMMENT '道路等级是否通过',
    seats_ok BOOLEAN NOT NULL COMMENT '座位数是否通过',
    result VARCHAR(8) NOT NULL COMMENT '评估结果：PASSED 通过 / REJECTED 拒绝',
    reject_reason VARCHAR(255) NULL COMMENT '拒绝原因，通过时为空',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求ID',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，UTC',
    CONSTRAINT uk_assessment_route_date UNIQUE (route_id, assessment_date),
    CONSTRAINT fk_assessment_route FOREIGN KEY (route_id) REFERENCES route (id)
) COMMENT '安全评估记录';

CREATE TABLE IF NOT EXISTS qualification (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    route_id BIGINT NOT NULL COMMENT '线路ID，关联 route.id',
    valid_from DATE NOT NULL COMMENT '资格生效日期，UTC 日界，含当日',
    valid_to DATE NOT NULL COMMENT '资格到期日期，UTC 日界，当日 24:00 失效（含当日有效）',
    status VARCHAR(16) NOT NULL COMMENT '资格状态：ACTIVE 有效 / EXPIRED 已失效 / RENEWED 已续期',
    source VARCHAR(16) NOT NULL COMMENT '来源：INITIAL 首次评估 / RENEWAL 续期',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求ID',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，UTC',
    CONSTRAINT fk_qualification_route FOREIGN KEY (route_id) REFERENCES route (id)
) COMMENT '线路运行资格，有效期 1 年';

CREATE TABLE IF NOT EXISTS suspension_trace (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    route_id BIGINT NOT NULL COMMENT '停运线路ID，关联 route.id',
    qualification_id BIGINT NOT NULL COMMENT '触发停运的资格ID，关联 qualification.id',
    reason VARCHAR(64) NOT NULL COMMENT '停运原因，如 QUALIFICATION_EXPIRED 资格到期',
    suspended_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '停运时间，UTC',
    CONSTRAINT fk_trace_route FOREIGN KEY (route_id) REFERENCES route (id),
    CONSTRAINT fk_trace_qualification FOREIGN KEY (qualification_id) REFERENCES qualification (id)
) COMMENT '停运留痕记录';

CREATE TABLE IF NOT EXISTS student (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    student_no VARCHAR(32) NOT NULL COMMENT '学号，全局唯一',
    name VARCHAR(64) NOT NULL COMMENT '学生姓名（合成数据）',
    route_id BIGINT NULL COMMENT '当前所属线路ID，关联 route.id；为空表示待改派',
    CONSTRAINT uk_student_no UNIQUE (student_no),
    CONSTRAINT fk_student_route FOREIGN KEY (route_id) REFERENCES route (id)
) COMMENT '乘车学生';

CREATE TABLE IF NOT EXISTS check_in (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    student_id BIGINT NOT NULL COMMENT '学生ID，关联 student.id',
    route_id BIGINT NOT NULL COMMENT '打卡线路ID，关联 route.id',
    check_date DATE NOT NULL COMMENT '打卡日期，UTC 日界',
    check_type VARCHAR(8) NOT NULL COMMENT '打卡类型：BOARD 上车 / ALIGHT 下车',
    request_id VARCHAR(64) NOT NULL COMMENT '幂等请求ID',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，UTC',
    CONSTRAINT uk_checkin_student_date_type UNIQUE (student_id, check_date, check_type),
    CONSTRAINT fk_checkin_student FOREIGN KEY (student_id) REFERENCES student (id),
    CONSTRAINT fk_checkin_route FOREIGN KEY (route_id) REFERENCES route (id)
) COMMENT '学生上下车打卡，按学生+日期+类型去重';

CREATE TABLE IF NOT EXISTS notification (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    notice_type VARCHAR(24) NOT NULL COMMENT '通知类型：MISSED_BOARDING 漏乘 / REASSIGNMENT 改派',
    student_id BIGINT NOT NULL COMMENT '学生ID，关联 student.id',
    route_id BIGINT NULL COMMENT '相关线路ID，关联 route.id，可为空',
    notice_date DATE NOT NULL COMMENT '通知日期，UTC 日界',
    content VARCHAR(255) NOT NULL COMMENT '合成通知内容',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，UTC',
    CONSTRAINT uk_notification UNIQUE (notice_type, student_id, route_id, notice_date),
    CONSTRAINT fk_notification_student FOREIGN KEY (student_id) REFERENCES student (id)
) COMMENT '合成通知记录';

CREATE TABLE IF NOT EXISTS reassignment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    student_id BIGINT NOT NULL COMMENT '学生ID，关联 student.id',
    from_route_id BIGINT NOT NULL COMMENT '原线路ID（停运线路）',
    to_route_id BIGINT NULL COMMENT '改派目标线路ID；为空表示暂无可改派线路',
    reason VARCHAR(64) NOT NULL COMMENT '改派原因，如 QUALIFICATION_EXPIRED 资格到期停运',
    settle_date DATE NOT NULL COMMENT '日结归属日期，UTC 日界',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，UTC',
    CONSTRAINT uk_reassignment UNIQUE (student_id, from_route_id, reason, settle_date),
    CONSTRAINT fk_reassign_student FOREIGN KEY (student_id) REFERENCES student (id)
) COMMENT '停运线路学生改派记录，与通知同事务生成，可按日结日期复算';

CREATE TABLE IF NOT EXISTS idempotency_record (
    request_id VARCHAR(64) PRIMARY KEY COMMENT '幂等请求ID，全局唯一',
    endpoint VARCHAR(128) NOT NULL COMMENT '首次调用的接口标识',
    fingerprint VARCHAR(64) NOT NULL COMMENT '请求指纹，覆盖 requestId 与全部业务字段的 SHA-256',
    response_status INT NULL COMMENT '首次响应的 HTTP 状态码，业务执行完成前为空',
    response_body CLOB NULL COMMENT '首次响应体（JSON），同键同参原样返回；业务执行完成前为空',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，UTC'
) COMMENT '写操作幂等记录';
