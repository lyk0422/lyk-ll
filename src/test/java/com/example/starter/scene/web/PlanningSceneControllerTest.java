package com.example.starter.scene.web;

import com.example.starter.scene.db.SchemaInitializer;
import com.example.starter.scene.model.SceneApi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Planning Scene REST 入口测试：校验 HTTP 状态码、409 冲突体与历史快照读取。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlanningSceneControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SchemaInitializer schemaInitializer;

    @BeforeEach
    void resetDatabase() {
        for (String table : List.of(
                "scene_idempotent_request", "scene_joint_snapshot", "scene_object_snapshot",
                "scene_version", "scene_object_current", "scene_joint_current",
                "scene_joint_def", "scene_link", "scene_meta")) {
            jdbc.update("DELETE FROM " + table);
        }
        schemaInitializer.initialize();
    }

    private void initModel() throws Exception {
        SceneApi.RobotModelRequest model = new SceneApi.RobotModelRequest(
                List.of("base_link", "arm_link"),
                List.of(new SceneApi.JointSpec("joint_a", -1.5, 1.5, 0.0)),
                null);
        mockMvc.perform(post("/api/scene/robot-model")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(model)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(0));
    }

    private SceneApi.OperationRequest upsert(String id, double x) {
        return new SceneApi.OperationRequest(SceneApi.OperationType.OBJECT_UPSERT,
                id, null, "world", "box", 0.1, 0.1, 0.1, x, 0.0, 0.0,
                0.0, 0.0, 0.0, 1.0, null, null, null);
    }

    private String change(String requestId, long expectedVersion, SceneApi.OperationRequest... ops)
            throws Exception {
        SceneApi.ChangeRequest request = new SceneApi.ChangeRequest(
                requestId, expectedVersion, List.of(ops), null);
        return objectMapper.writeValueAsString(request);
    }

    @Test
    @DisplayName("REST 主流程：初始化、提交、读取当前与历史快照")
    void restHappyPath() throws Exception {
        initModel();

        mockMvc.perform(post("/api/scene/changes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(change("req-1", 0, upsert("box_a", 1.0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.committed").value(true))
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.requestId").value("req-1"));

        mockMvc.perform(get("/api/scene/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.objects[0].objectId").value("box_a"));

        mockMvc.perform(get("/api/scene/snapshots/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.objects[0].x").value(1.0));
    }

    @Test
    @DisplayName("REST 幂等：同键同参重放返回 committed=false/replayed=true")
    void restIdempotentReplay() throws Exception {
        initModel();
        String body = change("idem", 0, upsert("box_a", 1.0));
        mockMvc.perform(post("/api/scene/changes").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/scene/changes").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.committed").value(false))
                .andExpect(jsonPath("$.replayed").value(true));
    }

    @Test
    @DisplayName("REST 冲突：版本过期返回 409、当前版本与变化对象 ID 列表")
    void restVersionConflictBody() throws Exception {
        initModel();
        mockMvc.perform(post("/api/scene/changes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(change("a-1", 0, upsert("box_a", 1.0))))
                .andExpect(status().isOk());

        MvcResult result = mockMvc.perform(post("/api/scene/changes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(change("b-stale", 0, upsert("box_b", 2.0))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.expectedVersion").value(0))
                .andExpect(jsonPath("$.currentVersion").value(1))
                .andExpect(jsonPath("$.changedObjectIds[0]").value("box_a"))
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        org.assertj.core.api.Assertions.assertThat(
                body.get("changedObjectIds").get(0).asText()).isEqualTo("box_a");
    }

    @Test
    @DisplayName("REST 校验失败：附着不存在的 Link 返回 400，且版本不变")
    void restValidationFailure() throws Exception {
        initModel();
        SceneApi.OperationRequest badAttach = new SceneApi.OperationRequest(
                SceneApi.OperationType.ATTACH,
                "ghost", null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, "no_link");
        mockMvc.perform(post("/api/scene/changes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(change("bad", 0, badAttach)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_OPERATION"));

        mockMvc.perform(get("/api/scene/current"))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    @DisplayName("REST 历史版本不存在返回 404；未初始化时变更返回 409")
    void restNotFoundAndUninitialized() throws Exception {
        // 全新空库，未初始化
        mockMvc.perform(get("/api/scene/snapshots/7"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SNAPSHOT_NOT_FOUND"));

        mockMvc.perform(post("/api/scene/changes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(change("x", 0, upsert("a", 1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROBOT_MODEL_MISSING"));
    }
}
