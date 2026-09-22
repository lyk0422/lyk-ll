-- TF2 风格坐标系变换缓存的 H2（MODE=MySQL）建表脚本
-- 数据仅保留在 JVM 生命周期内的命名内存库，重启后丢失。

CREATE TABLE IF NOT EXISTS tf_frame (
    frame_name       VARCHAR(128) NOT NULL,
    created_at_nanos BIGINT       NOT NULL
);
ALTER TABLE tf_frame ADD CONSTRAINT IF NOT EXISTS pk_tf_frame PRIMARY KEY (frame_name);

COMMENT ON TABLE tf_frame IS '坐标系注册表，森林中的每个顶点一行';
COMMENT ON COLUMN tf_frame.frame_name IS '坐标系唯一名称';
COMMENT ON COLUMN tf_frame.created_at_nanos IS '坐标系首次登记时间，epoch 纳秒';

CREATE TABLE IF NOT EXISTS tf_edge (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    parent_frame        VARCHAR(128) NOT NULL,
    child_frame         VARCHAR(128) NOT NULL,
    edge_type           VARCHAR(16)  NOT NULL,
    source              VARCHAR(128) NOT NULL,
    created_at_nanos    BIGINT       NOT NULL,
    first_sample_nanos  BIGINT,
    evicted_upto_nanos  BIGINT
);
ALTER TABLE tf_edge ADD CONSTRAINT IF NOT EXISTS pk_tf_edge PRIMARY KEY (id);
ALTER TABLE tf_edge ADD CONSTRAINT IF NOT EXISTS uk_edge_pair UNIQUE (parent_frame, child_frame);
ALTER TABLE tf_edge ADD CONSTRAINT IF NOT EXISTS uk_edge_child UNIQUE (child_frame);
ALTER TABLE tf_edge ADD CONSTRAINT IF NOT EXISTS fk_edge_parent_frame
    FOREIGN KEY (parent_frame) REFERENCES tf_frame (frame_name);
ALTER TABLE tf_edge ADD CONSTRAINT IF NOT EXISTS fk_edge_child_frame
    FOREIGN KEY (child_frame) REFERENCES tf_frame (frame_name);

COMMENT ON TABLE tf_edge IS '坐标系有向森林的边：parent_frame -> child_frame，每个子坐标系至多一条入边';
COMMENT ON COLUMN tf_edge.id IS '边自增主键';
COMMENT ON COLUMN tf_edge.parent_frame IS '父坐标系名称';
COMMENT ON COLUMN tf_edge.child_frame IS '子坐标系名称，全表唯一以保证森林语义';
COMMENT ON COLUMN tf_edge.edge_type IS '边类型：STATIC-静态变换全时刻有效；DYNAMIC-动态变换按纳秒时间戳采样';
COMMENT ON COLUMN tf_edge.source IS '最近一次写入该边的变换来源标识';
COMMENT ON COLUMN tf_edge.created_at_nanos IS '边创建时间，epoch 纳秒';
COMMENT ON COLUMN tf_edge.first_sample_nanos IS '动态边历史上最早样本时间戳（epoch 纳秒），乱序补录更早样本时取最小值单调下修；用于区分早于最早采样与缓存窗口淘汰，静态边为 NULL';
COMMENT ON COLUMN tf_edge.evicted_upto_nanos IS '动态边已被缓存淘汰的最大样本时间戳（epoch 纳秒）；NULL 表示从未淘汰，静态边恒为 NULL';

CREATE TABLE IF NOT EXISTS tf_static_transform (
    edge_id          BIGINT       NOT NULL,
    source           VARCHAR(128) NOT NULL,
    tx               DOUBLE       NOT NULL,
    ty               DOUBLE       NOT NULL,
    tz               DOUBLE       NOT NULL,
    qx               DOUBLE       NOT NULL,
    qy               DOUBLE       NOT NULL,
    qz               DOUBLE       NOT NULL,
    qw               DOUBLE       NOT NULL,
    created_at_nanos BIGINT       NOT NULL
);
ALTER TABLE tf_static_transform ADD CONSTRAINT IF NOT EXISTS pk_tf_static_transform PRIMARY KEY (edge_id);
ALTER TABLE tf_static_transform ADD CONSTRAINT IF NOT EXISTS fk_static_edge
    FOREIGN KEY (edge_id) REFERENCES tf_edge (id);

COMMENT ON TABLE tf_static_transform IS '静态边的唯一位姿；同参重放幂等，异参冲突';
COMMENT ON COLUMN tf_static_transform.edge_id IS '所属边 ID，与 tf_edge.id 一一对应';
COMMENT ON COLUMN tf_static_transform.source IS '静态变换来源标识';
COMMENT ON COLUMN tf_static_transform.tx IS '平移 X 分量，单位米';
COMMENT ON COLUMN tf_static_transform.ty IS '平移 Y 分量，单位米';
COMMENT ON COLUMN tf_static_transform.tz IS '平移 Z 分量，单位米';
COMMENT ON COLUMN tf_static_transform.qx IS '旋转四元数 X 分量';
COMMENT ON COLUMN tf_static_transform.qy IS '旋转四元数 Y 分量';
COMMENT ON COLUMN tf_static_transform.qz IS '旋转四元数 Z 分量';
COMMENT ON COLUMN tf_static_transform.qw IS '旋转四元数 W 分量';
COMMENT ON COLUMN tf_static_transform.created_at_nanos IS '写入时间，epoch 纳秒';

CREATE TABLE IF NOT EXISTS tf_dynamic_sample (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    edge_id          BIGINT       NOT NULL,
    stamp_nanos      BIGINT       NOT NULL,
    source           VARCHAR(128) NOT NULL,
    tx               DOUBLE       NOT NULL,
    ty               DOUBLE       NOT NULL,
    tz               DOUBLE       NOT NULL,
    qx               DOUBLE       NOT NULL,
    qy               DOUBLE       NOT NULL,
    qz               DOUBLE       NOT NULL,
    qw               DOUBLE       NOT NULL,
    created_at_nanos BIGINT       NOT NULL
);
ALTER TABLE tf_dynamic_sample ADD CONSTRAINT IF NOT EXISTS pk_tf_dynamic_sample PRIMARY KEY (id);
ALTER TABLE tf_dynamic_sample ADD CONSTRAINT IF NOT EXISTS uk_dynamic_sample
    UNIQUE (edge_id, stamp_nanos, source);
ALTER TABLE tf_dynamic_sample ADD CONSTRAINT IF NOT EXISTS uk_dynamic_sample_time
    UNIQUE (edge_id, stamp_nanos);
ALTER TABLE tf_dynamic_sample ADD CONSTRAINT IF NOT EXISTS fk_dynamic_sample_edge
    FOREIGN KEY (edge_id) REFERENCES tf_edge (id);
CREATE INDEX IF NOT EXISTS idx_dynamic_sample_edge_time
    ON tf_dynamic_sample (edge_id, stamp_nanos);

COMMENT ON TABLE tf_dynamic_sample IS '动态边的时间戳采样，允许乱序补录';
COMMENT ON COLUMN tf_dynamic_sample.id IS '样本自增主键';
COMMENT ON COLUMN tf_dynamic_sample.edge_id IS '所属动态边 ID';
COMMENT ON COLUMN tf_dynamic_sample.stamp_nanos IS '样本时间戳，epoch 纳秒';
COMMENT ON COLUMN tf_dynamic_sample.source IS '样本来源标识；与边、时间戳共同构成幂等键';
COMMENT ON COLUMN tf_dynamic_sample.tx IS '平移 X 分量，单位米';
COMMENT ON COLUMN tf_dynamic_sample.ty IS '平移 Y 分量，单位米';
COMMENT ON COLUMN tf_dynamic_sample.tz IS '平移 Z 分量，单位米';
COMMENT ON COLUMN tf_dynamic_sample.qx IS '旋转四元数 X 分量';
COMMENT ON COLUMN tf_dynamic_sample.qy IS '旋转四元数 Y 分量';
COMMENT ON COLUMN tf_dynamic_sample.qz IS '旋转四元数 Z 分量';
COMMENT ON COLUMN tf_dynamic_sample.qw IS '旋转四元数 W 分量';
COMMENT ON COLUMN tf_dynamic_sample.created_at_nanos IS '样本落库时间，epoch 纳秒，可晚于样本时间戳';
