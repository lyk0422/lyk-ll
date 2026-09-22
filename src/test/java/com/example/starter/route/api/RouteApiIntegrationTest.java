package com.example.starter.route.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实 HTTP 入口集成测试（嵌入式 H2 + MockMvc，不启动外部服务）：
 * 验证地图发布、匹配成功主流程及各稳定错误码对应的 HTTP 状态与响应体。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:route_api;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class RouteApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM route_match");
        jdbcTemplate.update("DELETE FROM route_candidate");
        jdbcTemplate.update("DELETE FROM route_request");
        jdbcTemplate.update("DELETE FROM map_segment");
        jdbcTemplate.update("DELETE FROM map_node");
        jdbcTemplate.update("DELETE FROM map_version");
    }

    private String chainMapJson(String version, boolean middleValid) {
        return """
                {
                  "version": "%s",
                  "nodes": [
                    {"nodeId": "N1", "x": 0, "y": 0},
                    {"nodeId": "N2", "x": 100, "y": 0},
                    {"nodeId": "N3", "x": 200, "y": 0},
                    {"nodeId": "N4", "x": 300, "y": 0}
                  ],
                  "segments": [
                    {"segmentId": "s1", "fromNodeId": "N1", "toNodeId": "N2", "lengthM": 100, "valid": true},
                    {"segmentId": "s2", "fromNodeId": "N2", "toNodeId": "N3", "lengthM": 100, "valid": %s},
                    {"segmentId": "s3", "fromNodeId": "N3", "toNodeId": "N4", "lengthM": 100, "valid": true}
                  ]
                }
                """.formatted(version, middleValid);
    }

    private String routeJson(String requestId, String version, double sx, double sy, double ex, double ey) {
        return """
                {
                  "requestId": "%s",
                  "mapVersion": "%s",
                  "start": {"x": %s, "y": %s, "heading": 90},
                  "end": {"x": %s, "y": %s, "heading": 90}
                }
                """.formatted(requestId, version, sx, sy, ex, ey);
    }

    @Test
    void publish_map_and_match_route_happy_path() throws Exception {
        mockMvc.perform(post("/api/maps")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chainMapJson("v1", true)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value("v1"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.nodeCount").value(4))
                .andExpect(jsonPath("$.segmentCount").value(3));

        mockMvc.perform(post("/api/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeJson("r1", "v1", 5, 2, 295, 2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("MATCHED"))
                .andExpect(jsonPath("$.boundMapVersion").value("v1"))
                .andExpect(jsonPath("$.errorCode").doesNotExist())
                .andExpect(jsonPath("$.match.startSegmentId").value("s1"))
                .andExpect(jsonPath("$.match.endSegmentId").value("s3"))
                .andExpect(jsonPath("$.match.pathSegmentIds[0]").value("s1"))
                .andExpect(jsonPath("$.match.pathSegmentIds[1]").value("s2"))
                .andExpect(jsonPath("$.match.pathSegmentIds[2]").value("s3"))
                .andExpect(jsonPath("$.startCandidates[0].selected").value(true))
                .andExpect(jsonPath("$.startCandidates[0].connectivityScore").value(100.0));

        mockMvc.perform(get("/api/routes/r1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("MATCHED"))
                .andExpect(jsonPath("$.match.pathSegmentIds[2]").value("s3"));
    }

    @Test
    void same_param_replay_returns_201_original_result() throws Exception {
        publishAndSubmit("r1", "v1", 5, 2, 295, 2);

        mockMvc.perform(post("/api/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeJson("r1", "v1", 5, 2, 295, 2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("MATCHED"))
                .andExpect(jsonPath("$.match.pathSegmentIds[1]").value("s2"));
    }

    @Test
    void different_param_replay_returns_409_with_distinct_code() throws Exception {
        publishAndSubmit("r1", "v1", 5, 2, 295, 2);

        mockMvc.perform(post("/api/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeJson("r1", "v1", 9, 2, 295, 2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("REPLAY_PARAM_CONFLICT"));
    }

    @Test
    void missing_map_version_returns_404() throws Exception {
        mockMvc.perform(post("/api/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeJson("r1", "ghost", 5, 2, 295, 2)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("MAP_VERSION_NOT_FOUND"));
    }

    @Test
    void missing_request_returns_404() throws Exception {
        mockMvc.perform(get("/api/routes/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_NOT_FOUND"));
    }

    @Test
    void deprecated_version_rejects_new_request_with_409() throws Exception {
        publishAndSubmit("r1", "v1", 5, 2, 295, 2);
        mockMvc.perform(post("/api/maps/v1/deprecate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEPRECATED"));

        mockMvc.perform(post("/api/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeJson("r2", "v1", 5, 2, 295, 2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("MAP_VERSION_DEPRECATED"));

        // 历史请求仍可同参重放
        mockMvc.perform(post("/api/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeJson("r1", "v1", 5, 2, 295, 2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("MATCHED"));
    }

    @Test
    void broken_path_returns_422_path_disconnected_with_candidate_reasons() throws Exception {
        mockMvc.perform(post("/api/maps")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chainMapJson("v-broken", false)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeJson("r1", "v-broken", 5, 2, 295, 2)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.state").value("FAILED"))
                .andExpect(jsonPath("$.errorCode").value("PATH_DISCONNECTED"))
                .andExpect(jsonPath("$.startCandidates[0].rejectReasons[0]").value("NOT_CONNECTED"))
                .andExpect(jsonPath("$.endCandidates[0].rejectReasons[0]").value("NOT_CONNECTED"))
                .andExpect(jsonPath("$.match").doesNotExist());
    }

    @Test
    void start_without_candidate_returns_422_start_no_candidate() throws Exception {
        mockMvc.perform(post("/api/maps")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chainMapJson("v1", true)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeJson("r1", "v1", 9000, 9000, 295, 2)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("START_NO_CANDIDATE"));
    }

    @Test
    void invalid_segment_returns_422_segment_invalid() throws Exception {
        String payload = """
                {
                  "version": "v-invalid",
                  "nodes": [
                    {"nodeId": "N1", "x": 0, "y": 0},
                    {"nodeId": "N2", "x": 100, "y": 0}
                  ],
                  "segments": [
                    {"segmentId": "s1", "fromNodeId": "N1", "toNodeId": "N2", "lengthM": 100, "valid": false}
                  ]
                }
                """;
        mockMvc.perform(post("/api/maps")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeJson("r1", "v-invalid", 50, 2, 50, 2)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("SEGMENT_INVALID"))
                .andExpect(jsonPath("$.startCandidates[0].rejectReasons[0]").value("SEGMENT_INVALID"));
    }

    @Test
    void invalid_body_returns_400_validation_failed() throws Exception {
        // 缺少 requestId
        String payload = """
                {
                  "mapVersion": "v1",
                  "start": {"x": 0, "y": 0, "heading": 90},
                  "end": {"x": 1, "y": 1, "heading": 90}
                }
                """;
        mockMvc.perform(post("/api/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    void duplicate_version_publish_returns_409() throws Exception {
        mockMvc.perform(post("/api/maps")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chainMapJson("v1", true)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/maps")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chainMapJson("v1", true)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("VERSION_ALREADY_EXISTS"));
    }

    private void publishAndSubmit(String requestId, String version,
                                  double sx, double sy, double ex, double ey) throws Exception {
        mockMvc.perform(post("/api/maps")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chainMapJson(version, true)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routeJson(requestId, version, sx, sy, ex, ey)))
                .andExpect(status().isCreated());
    }
}
