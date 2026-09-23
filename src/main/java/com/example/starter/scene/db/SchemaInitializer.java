package com.example.starter.scene.db;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 进程启动时在 H2（MODE=MySQL）内存库中创建 Planning Scene 所需表结构。
 * 数据仅存于内存，进程退出即丢失；重复执行 CREATE TABLE IF NOT EXISTS 保持幂等。
 */
@Component
public class SchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public SchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        initialize();
    }

    /** 建表入口，同时供测试在需要时显式调用。 */
    public void initialize() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS scene_meta (
                    id INT PRIMARY KEY,
                    initialized BOOLEAN NOT NULL,
                    scene_version BIGINT NOT NULL,
                    rp_frame VARCHAR(255),
                    rp_x DOUBLE,
                    rp_y DOUBLE,
                    rp_z DOUBLE,
                    rp_qx DOUBLE,
                    rp_qy DOUBLE,
                    rp_qz DOUBLE,
                    rp_qw DOUBLE
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS scene_link (
                    link_name VARCHAR(255) PRIMARY KEY
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS scene_joint_def (
                    joint_name VARCHAR(255) PRIMARY KEY,
                    min_val DOUBLE NOT NULL,
                    max_val DOUBLE NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS scene_joint_current (
                    joint_name VARCHAR(255) PRIMARY KEY,
                    val DOUBLE NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS scene_object_current (
                    object_id VARCHAR(255) PRIMARY KEY,
                    frame_id VARCHAR(255) NOT NULL,
                    shape VARCHAR(64) NOT NULL,
                    size_x DOUBLE NOT NULL,
                    size_y DOUBLE NOT NULL,
                    size_z DOUBLE NOT NULL,
                    pos_x DOUBLE NOT NULL,
                    pos_y DOUBLE NOT NULL,
                    pos_z DOUBLE NOT NULL,
                    qx DOUBLE NOT NULL,
                    qy DOUBLE NOT NULL,
                    qz DOUBLE NOT NULL,
                    qw DOUBLE NOT NULL,
                    attached_link VARCHAR(255)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS scene_version (
                    version BIGINT PRIMARY KEY,
                    request_id VARCHAR(255) NOT NULL,
                    created_ts TIMESTAMP NOT NULL,
                    rp_frame VARCHAR(255),
                    rp_x DOUBLE,
                    rp_y DOUBLE,
                    rp_z DOUBLE,
                    rp_qx DOUBLE,
                    rp_qy DOUBLE,
                    rp_qz DOUBLE,
                    rp_qw DOUBLE
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS scene_object_snapshot (
                    version BIGINT NOT NULL,
                    object_id VARCHAR(255) NOT NULL,
                    frame_id VARCHAR(255) NOT NULL,
                    shape VARCHAR(64) NOT NULL,
                    size_x DOUBLE NOT NULL,
                    size_y DOUBLE NOT NULL,
                    size_z DOUBLE NOT NULL,
                    pos_x DOUBLE NOT NULL,
                    pos_y DOUBLE NOT NULL,
                    pos_z DOUBLE NOT NULL,
                    qx DOUBLE NOT NULL,
                    qy DOUBLE NOT NULL,
                    qz DOUBLE NOT NULL,
                    qw DOUBLE NOT NULL,
                    attached_link VARCHAR(255),
                    PRIMARY KEY (version, object_id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS scene_joint_snapshot (
                    version BIGINT NOT NULL,
                    joint_name VARCHAR(255) NOT NULL,
                    val DOUBLE NOT NULL,
                    PRIMARY KEY (version, joint_name)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS scene_idempotent_request (
                    request_id VARCHAR(255) PRIMARY KEY,
                    version BIGINT NOT NULL,
                    payload_hash CHAR(64) NOT NULL,
                    created_ts TIMESTAMP NOT NULL
                )
                """);

        comment("scene_meta", "initialized", "机器人模型是否已初始化（true 后模型不可变更）");
        comment("scene_meta", "scene_version", "当前单调递增场景版本号，初始为 0");
        comment("scene_meta", "rp_frame", "机器人姿态参考坐标系；为空表示未设置姿态");
        comment("scene_joint_def", "min_val", "关节角度下限，单位弧度");
        comment("scene_joint_def", "max_val", "关节角度上限，单位弧度");
        comment("scene_joint_current", "val", "关节当前角度，单位弧度");
        comment("scene_object_current", "frame_id", "对象位姿参考坐标系，必须是 world 或已存在的机器人 Link");
        comment("scene_object_current", "shape", "碰撞体形状，如 box/sphere/cylinder");
        comment("scene_object_current", "size_x", "碰撞体尺寸 X，单位米");
        comment("scene_object_current", "pos_x", "对象位置 X，单位米");
        comment("scene_object_current", "qw", "对象姿态四元数 w 分量，须为有限数");
        comment("scene_object_current", "attached_link", "非空表示对象附着在该机器人 Link；为空表示对象位于世界中");
        comment("scene_version", "request_id", "产生该版本的幂等请求 ID");
        comment("scene_object_snapshot", "version", "该快照行所属的不可变场景版本");
        comment("scene_joint_snapshot", "val", "该版本下关节角度，单位弧度");
        comment("scene_idempotent_request", "payload_hash", "请求参数规范化后的 SHA-256，同键异参据此返回 409");

        ensureMetaSeed();
    }

    /**
     * 确保 scene_meta 始终存在唯一占位行（initialized=FALSE），
     * 使模型初始化也能通过该行悲观锁串行化；已存在时不覆盖。
     */
    private void ensureMetaSeed() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM scene_meta WHERE id = 1", Integer.class);
        if (count == null || count == 0) {
            jdbcTemplate.update("INSERT INTO scene_meta (id, initialized, scene_version) "
                    + "VALUES (1, FALSE, 0)");
        }
    }

    private void comment(String table, String column, String text) {
        jdbcTemplate.execute("COMMENT ON COLUMN " + table + "." + column + " IS '" + text + "'");
    }
}
