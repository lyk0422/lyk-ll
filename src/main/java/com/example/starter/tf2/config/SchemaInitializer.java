package com.example.starter.tf2.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 启动时自动建表（H2 MySQL 兼容模式 / MySQL 两套 DDL），幂等执行。
 * 数据仅保留在内存库生命周期内，不做跨进程持久化。
 */
@Component
public class SchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public SchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS tf_transform_sample (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    parent_frame VARCHAR(255) NOT NULL,
                    child_frame VARCHAR(255) NOT NULL,
                    edge_type VARCHAR(16) NOT NULL,
                    stamp_nanos BIGINT NOT NULL,
                    tx DOUBLE NOT NULL,
                    ty DOUBLE NOT NULL,
                    tz DOUBLE NOT NULL,
                    qx DOUBLE NOT NULL,
                    qy DOUBLE NOT NULL,
                    qz DOUBLE NOT NULL,
                    qw DOUBLE NOT NULL,
                    source VARCHAR(255) NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    CONSTRAINT uq_tf_sample UNIQUE (parent_frame, child_frame, edge_type, stamp_nanos, source)
                )
                """);

        String product = detectProductName();
        if (product != null && product.toUpperCase().contains("H2")) {
            applyH2Comments();
        } else {
            applyMySqlComments();
        }
    }

    private String detectProductName() {
        Connection connection = DataSourceUtils.getConnection(jdbcTemplate.getDataSource());
        try {
            return connection.getMetaData().getDatabaseProductName();
        } catch (SQLException e) {
            return null;
        } finally {
            DataSourceUtils.releaseConnection(connection, jdbcTemplate.getDataSource());
        }
    }

    private void applyH2Comments() {
        jdbcTemplate.execute("COMMENT ON TABLE tf_transform_sample IS 'TF2 风格坐标变换样本表：静态边每父子关系一行，动态边每个纳秒时间戳样本一行'");
        jdbcTemplate.execute("COMMENT ON COLUMN tf_transform_sample.id IS '自增主键'");
        jdbcTemplate.execute("COMMENT ON COLUMN tf_transform_sample.parent_frame IS '父坐标系名称，边方向 parent -> child'");
        jdbcTemplate.execute("COMMENT ON COLUMN tf_transform_sample.child_frame IS '子坐标系名称'");
        jdbcTemplate.execute("COMMENT ON COLUMN tf_transform_sample.edge_type IS '边语义：STATIC=静态（全时刻有效）；DYNAMIC=动态样本'");
        jdbcTemplate.execute("COMMENT ON COLUMN tf_transform_sample.stamp_nanos IS '动态样本时间戳（纳秒，单调时钟语义）；静态边固定为 0'");
        jdbcTemplate.execute("COMMENT ON COLUMN tf_transform_sample.tx IS '平移 X 分量（米）'");
        jdbcTemplate.execute("COMMENT ON COLUMN tf_transform_sample.ty IS '平移 Y 分量（米）'");
        jdbcTemplate.execute("COMMENT ON COLUMN tf_transform_sample.tz IS '平移 Z 分量（米）'");
        jdbcTemplate.execute("COMMENT ON COLUMN tf_transform_sample.qx IS '旋转四元数虚部 x（单位四元数）'");
        jdbcTemplate.execute("COMMENT ON COLUMN tf_transform_sample.qy IS '旋转四元数虚部 y（单位四元数）'");
        jdbcTemplate.execute("COMMENT ON COLUMN tf_transform_sample.qz IS '旋转四元数虚部 z（单位四元数）'");
        jdbcTemplate.execute("COMMENT ON COLUMN tf_transform_sample.qw IS '旋转四元数实部 w（单位四元数）'");
        jdbcTemplate.execute("COMMENT ON COLUMN tf_transform_sample.source IS '样本来源标识；同父子/时间戳/来源为同参重放幂等键'");
        jdbcTemplate.execute("COMMENT ON COLUMN tf_transform_sample.created_at IS '入库时间（审计，JVM 默认时区）'");
    }

    private void applyMySqlComments() {
        jdbcTemplate.execute("""
                ALTER TABLE tf_transform_sample
                MODIFY parent_frame VARCHAR(255) NOT NULL COMMENT '父坐标系名称，边方向 parent -> child',
                MODIFY child_frame VARCHAR(255) NOT NULL COMMENT '子坐标系名称',
                MODIFY edge_type VARCHAR(16) NOT NULL COMMENT '边语义：STATIC=静态（全时刻有效）；DYNAMIC=动态样本',
                MODIFY stamp_nanos BIGINT NOT NULL COMMENT '动态样本时间戳（纳秒，单调时钟语义）；静态边固定为 0',
                MODIFY tx DOUBLE NOT NULL COMMENT '平移 X 分量（米）',
                MODIFY ty DOUBLE NOT NULL COMMENT '平移 Y 分量（米）',
                MODIFY tz DOUBLE NOT NULL COMMENT '平移 Z 分量（米）',
                MODIFY qx DOUBLE NOT NULL COMMENT '旋转四元数虚部 x（单位四元数）',
                MODIFY qy DOUBLE NOT NULL COMMENT '旋转四元数虚部 y（单位四元数）',
                MODIFY qz DOUBLE NOT NULL COMMENT '旋转四元数虚部 z（单位四元数）',
                MODIFY qw DOUBLE NOT NULL COMMENT '旋转四元数实部 w（单位四元数）',
                MODIFY source VARCHAR(255) NOT NULL COMMENT '样本来源标识；同父子/时间戳/来源为同参重放幂等键',
                MODIFY created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间（审计，JVM 默认时区）'
                """);
    }
}
