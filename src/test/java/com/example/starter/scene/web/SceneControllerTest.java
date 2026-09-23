package com.example.starter.scene.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Planning Scene REST 接口测试，验证 HTTP 状态码、409 冲突负载与历史快照入口。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SceneControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetScene() {
        jdbc.update("DELETE FROM scene_event");
        jdbc.update("DELETE FROM scene_snapshot");
        jdbc.update("DELETE FROM applied_request");
        jdbc.update("DELETE FROM world_object");
        jdbc.update("DELETE FROM attached_object");
        jdbc.update("UPDATE robot_joint SET position = 0.0");
        jdbc.update("UPDATE scene_meta SET version = 0 WHERE id = 0");
        jdbc.update("INSERT INTO scene_snapshot (version, payload) VALUES (0, "
                + "'{\"version\":0,\"joints\":[],\"worldObjects\":[],\"attachedObjects\":[]}')");
    }

    @Test
    void applyThenConflictThenReplayOverHttp() throws Exception {
        String request = """
                {
                  "requestId": "http-1",
                  "expectedVersion": 0,
                  "jointUpdates": [{"name": "joint_1", "position": 0.3}],
                  "objectOps": [{
                    "type": "ADD",
                    "object": {
                      "id": "http_box",
                      "frameId": "world",
                      "pose": {"x": 1.0, "y": 0.0, "z": 0.0, "qx": 0.0, "qy": 0.0, "qz": 0.0, "qw": 1.0},
                      "size": {"x": 0.2, "y": 0.2, "z": 0.2}
                    }
                  }],
                  "attachOps": []
                }
                """;

        mockMvc.perform(post("/api/scene/apply").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.replayed").value(false));

        // 同键同参：返回首次结果。
        mockMvc.perform(post("/api/scene/apply").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.replayed").value(false));

        // 过期版本：409，包含当前版本与变化对象/关节列表。
        MvcResult conflictResult = mockMvc.perform(
                        post("/api/scene/apply").contentType(MediaType.APPLICATION_JSON).content("""
                                {
                                  "requestId": "http-stale",
                                  "expectedVersion": 0,
                                  "jointUpdates": [],
                                  "objectOps": [{
                                    "type": "ADD",
                                    "object": {
                                      "id": "stale_box",
                                      "frameId": "world",
                                      "pose": {"x": 0.0, "y": 0.0, "z": 0.0, "qx": 0.0, "qy": 0.0, "qz": 0.0, "qw": 1.0},
                                      "size": {"x": 0.1, "y": 0.1, "z": 0.1}
                                    }
                                  }],
                                  "attachOps": []
                                }
                                """))
                .andExpect(status().isConflict())
                .andReturn();
        JsonNode conflict = objectMapper.readTree(conflictResult.getResponse().getContentAsString());
        assertThat(conflict.path("error").asText()).isEqualTo("VERSION_CONFLICT");
        assertThat(conflict.path("currentVersion").asLong()).isEqualTo(1L);
        assertThat(conflict.path("changedObjects")).containsExactly(objectMapper.getNodeFactory().textNode("http_box"));
        assertThat(conflict.path("changedJoints")).containsExactly(objectMapper.getNodeFactory().textNode("joint_1"));
    }

    @Test
    void invalidBatchReturnsBadRequestAndNoPartialWrites() throws Exception {
        mockMvc.perform(post("/api/scene/apply").contentType(MediaType.APPLICATION_JSON).content("""
                {
                  "requestId": "http-bad",
                  "expectedVersion": 0,
                  "jointUpdates": [{"name": "joint_1", "position": 99.0}],
                  "objectOps": [{
                    "type": "ADD",
                    "object": {
                      "id": "partial_box",
                      "frameId": "world",
                      "pose": {"x": 0.0, "y": 0.0, "z": 0.0, "qx": 0.0, "qy": 0.0, "qz": 0.0, "qw": 1.0},
                      "size": {"x": 0.1, "y": 0.1, "z": 0.1}
                    }
                  }],
                  "attachOps": []
                }
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/api/scene/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.worldObjects").isArray())
                .andExpect(jsonPath("$.worldObjects").isEmpty());
    }

    @Test
    void unknownHistoryVersionReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/scene/versions/999"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/scene/versions/0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0));
    }
}
