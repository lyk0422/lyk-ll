package com.example.starter.planningscene.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REST 契约测试：提交成功返回新版本，版本过期/幂等异参返回 409 及冲突对象列表，历史快照可读取。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:web_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
class SceneControllerTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetScene() {
        jdbcTemplate.execute("DELETE FROM scene_request");
        jdbcTemplate.execute("DELETE FROM scene_object");
        jdbcTemplate.execute("DELETE FROM scene_joint");
        jdbcTemplate.execute("DELETE FROM scene_robot_state");
        jdbcTemplate.execute("DELETE FROM scene_meta");
        jdbcTemplate.update("INSERT INTO scene_meta (id, current_version) VALUES (0, 0)");
        jdbcTemplate.update("INSERT INTO scene_robot_state "
                + "(version, pos_x, pos_y, pos_z, qx, qy, qz, qw) VALUES (0,0,0,0,0,0,0,1)");
        jdbcTemplate.update("INSERT INTO scene_joint (version, joint_name, position, lower_bound, upper_bound) "
                + "SELECT 0, joint_name, 0.0, lower_bound, upper_bound FROM robot_joint");
    }

    private String url(String path) {
        return "http://localhost:" + port + "/api/planning-scene" + path;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<Map> response) {
        return response.getBody();
    }

    @Test
    @SuppressWarnings("unchecked")
    void commitConflictAndHistoryEndpointsFollowContract() {
        Map<String, Object> first = Map.of(
                "requestId", "web-req-1",
                "expectedVersion", 0,
                "jointUpdates", List.of(Map.of("name", "joint_1", "position", 0.4)),
                "operations", List.of(Map.of(
                        "type", "ADD",
                        "objectId", "table",
                        "frameId", "world",
                        "geometry", "{\"shape\":\"box\"}",
                        "pose", Map.of("x", 0, "y", 0, "z", 0.3,
                                "qx", 0, "qy", 0, "qz", 0, "qw", 1))));

        ResponseEntity<Map> ok = restTemplate.postForEntity(url("/commit"), first, Map.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(ok).get("committed")).isEqualTo(true);
        assertThat(body(ok).get("version")).isEqualTo(1);

        // 过期版本提交：409 + 当前版本 + 变化对象/关节
        Map<String, Object> stale = Map.of(
                "requestId", "web-req-stale",
                "expectedVersion", 0,
                "operations", List.of());
        ResponseEntity<Map> conflict = restTemplate.postForEntity(url("/commit"), stale, Map.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        Map<String, Object> conflictBody = body(conflict);
        assertThat(conflictBody.get("reason")).isEqualTo("VERSION_CONFLICT");
        assertThat(conflictBody.get("currentVersion")).isEqualTo(1);
        assertThat((List<String>) conflictBody.get("changedObjectIds")).containsExactly("table");
        assertThat((List<String>) conflictBody.get("changedJointNames")).containsExactly("joint_1");

        // 同键异参：409 IDEMPOTENCY_CONFLICT
        Map<String, Object> different = Map.of(
                "requestId", "web-req-1",
                "expectedVersion", 0,
                "operations", List.of());
        ResponseEntity<Map> idempotencyConflict =
                restTemplate.postForEntity(url("/commit"), different, Map.class);
        assertThat(idempotencyConflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(body(idempotencyConflict).get("error")).isEqualTo("IDEMPOTENCY_CONFLICT");

        // 当前快照与历史快照
        ResponseEntity<Map> current = restTemplate.getForEntity(url(""), Map.class);
        assertThat(current.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(current).get("version")).isEqualTo(1);

        ResponseEntity<Map> history = restTemplate.getForEntity(url("/versions/0"), Map.class);
        assertThat(history.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(history).get("version")).isEqualTo(0);
        assertThat((List<?>) body(history).get("objects")).isEmpty();

        ResponseEntity<Map> missing = restTemplate.getForEntity(url("/versions/99"), Map.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // 参数校验失败：400
        Map<String, Object> invalid = Map.of("requestId", "", "expectedVersion", 0);
        ResponseEntity<Map> bad = restTemplate.postForEntity(url("/commit"), invalid, Map.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
