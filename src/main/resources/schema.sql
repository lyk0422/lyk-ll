-- LiDAR 点云块索引元数据（H2 内存库，MODE=MySQL）
CREATE TABLE IF NOT EXISTS pc_chunk (
    chunk_id    VARCHAR(190) PRIMARY KEY,
    version     BIGINT       NOT NULL,
    source      VARCHAR(190) NOT NULL,
    start_nanos BIGINT       NOT NULL,
    end_nanos   BIGINT       NOT NULL,
    min_x       DOUBLE       NOT NULL,
    min_y       DOUBLE       NOT NULL,
    min_z       DOUBLE       NOT NULL,
    max_x       DOUBLE       NOT NULL,
    max_y       DOUBLE       NOT NULL,
    max_z       DOUBLE       NOT NULL,
    point_count INT          NOT NULL,
    compression VARCHAR(10)  NOT NULL,
    checksum    VARCHAR(64)  NOT NULL,
    file_name   VARCHAR(255) NOT NULL,
    created_at  BIGINT       NOT NULL
);

COMMENT ON COLUMN pc_chunk.chunk_id IS '块唯一标识';
COMMENT ON COLUMN pc_chunk.version IS '块版本';
COMMENT ON COLUMN pc_chunk.source IS '数据来源（雷达型号/节点）';
COMMENT ON COLUMN pc_chunk.start_nanos IS '块内最早点时间戳，纳秒（含）';
COMMENT ON COLUMN pc_chunk.end_nanos IS '块内最晚点时间戳，纳秒（含）';
COMMENT ON COLUMN pc_chunk.min_x IS '包围盒最小X，米（含边界）';
COMMENT ON COLUMN pc_chunk.min_y IS '包围盒最小Y，米（含边界）';
COMMENT ON COLUMN pc_chunk.min_z IS '包围盒最小Z，米（含边界）';
COMMENT ON COLUMN pc_chunk.max_x IS '包围盒最大X，米（含边界）';
COMMENT ON COLUMN pc_chunk.max_y IS '包围盒最大Y，米（含边界）';
COMMENT ON COLUMN pc_chunk.max_z IS '包围盒最大Z，米（含边界）';
COMMENT ON COLUMN pc_chunk.point_count IS '块内点数';
COMMENT ON COLUMN pc_chunk.compression IS '实际落盘压缩方式：NONE/GZIP（含回退结果）';
COMMENT ON COLUMN pc_chunk.checksum IS '规范化未压缩内容的SHA-256，十六进制小写';
COMMENT ON COLUMN pc_chunk.file_name IS '已发布块文件名';
COMMENT ON COLUMN pc_chunk.created_at IS '入库时间，UTC 毫秒，仅用于重建时的新旧判定';
