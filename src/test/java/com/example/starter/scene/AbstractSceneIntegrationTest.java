package com.example.starter.scene;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 基于真实 H2（MODE=MySQL）与 Spring 上下文的集成测试基类。
 * 每个用例前把业务表重置为版本 0 的初始场景，避免用例间顺序依赖；
 * 机器人模型表 ps_joint/ps_link 为静态种子，保留不动。
 */
@SpringBootTest
public abstract class AbstractSceneIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void resetScene() {
        jdbc.execute("DELETE FROM attachment_history");
        jdbc.execute("DELETE FROM object_history");
        jdbc.execute("DELETE FROM joint_state_history");
        jdbc.execute("DELETE FROM scene_version");
        jdbc.execute("DELETE FROM request_log");
        jdbc.execute("DELETE FROM attachment");
        jdbc.execute("DELETE FROM collision_object");
        jdbc.execute("DELETE FROM joint_state");

        jdbc.update("""
                INSERT INTO joint_state (joint_name, position)
                SELECT joint_name, 0.0 FROM ps_joint
                """);
        jdbc.update("""
                UPDATE scene_meta SET current_version = 0,
                  pose_x = 0.0, pose_y = 0.0, pose_z = 0.0,
                  pose_qx = 0.0, pose_qy = 0.0, pose_qz = 0.0, pose_qw = 1.0
                WHERE id = 1
                """);
        jdbc.update("""
                INSERT INTO scene_version (version, request_id, created_at,
                  pose_x, pose_y, pose_z, pose_qx, pose_qy, pose_qz, pose_qw)
                VALUES (0, '__initial__', 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0)
                """);
        jdbc.update("""
                INSERT INTO joint_state_history (version, joint_name, position)
                SELECT 0, joint_name, position FROM joint_state
                """);
    }

    /**
     * 当前版本号（直接读库，绕过业务接口）。
     */
    protected long currentVersionInDb() {
        return jdbc.queryForObject(
                "SELECT current_version FROM scene_meta WHERE id = 1", Long.class);
    }

    /**
     * 指定表的行数。
     */
    protected int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(1) FROM " + table, Integer.class);
    }
}
