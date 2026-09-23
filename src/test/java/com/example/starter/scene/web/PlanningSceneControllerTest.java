package com.example.starter.scene.web;

import com.example.starter.scene.AbstractSceneIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Planning Scene REST 入口的 HTTP 语义测试：201/409/400/404 与响应体内容。
 */
class PlanningSceneControllerTest extends AbstractSceneIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    private MockMvc mvc() {
        if (mockMvc == null) {
            mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        }
        return mockMvc;
    }

    @Test
    void createConflictAndReadHistoryOverHttp() throws Exception {
        // 版本 0 初始快照可读。
        mvc().perform(get("/api/planning-scene/versions/0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.worldObjects").isArray())
                .andExpect(jsonPath("$.joints.length()").value(6));

        // 场景创建：新增世界对象 + 关节更新。
        String firstBody = """
                {
                  "requestId": "http-req-1",
                  "expectedVersion": 0,
                  "objects": [
                    {"op": "ADD", "objectId": "box_http", "frameId": "base_link",
                     "payload": {"shape": "box", "size": 0.1}}
                  ],
                  "joints": [{"jointName": "joint_1", "position": 0.25}]
                }
                """;
        mvc().perform(post("/api/planning-scene/updates")
                        .contentType(MediaType.APPLICATION_JSON).content(firstBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.requestId").value("http-req-1"));

        // 同 requestId 同参数幂等重放。
        mvc().perform(post("/api/planning-scene/updates")
                        .contentType(MediaType.APPLICATION_JSON).content(firstBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.replayed").value(true));

        // 同 requestId 异参数 -> 409。
        String differentParams = """
                {
                  "requestId": "http-req-1",
                  "expectedVersion": 1,
                  "objects": [
                    {"op": "ADD", "objectId": "box_other", "frameId": "tool0"}
                  ]
                }
                """;
        mvc().perform(post("/api/planning-scene/updates")
                        .contentType(MediaType.APPLICATION_JSON).content(differentParams))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("IDEMPOTENT_PARAM_MISMATCH"))
                .andExpect(jsonPath("$.currentVersion").value(1));

        // 版本过期提交 -> 409 且带变化对象/关节列表。
        String stale = """
                {
                  "requestId": "http-req-stale",
                  "expectedVersion": 0,
                  "objects": [
                    {"op": "ADD", "objectId": "box_late", "frameId": "base_link"}
                  ]
                }
                """;
        mvc().perform(post("/api/planning-scene/updates")
                        .contentType(MediaType.APPLICATION_JSON).content(stale))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("VERSION_MISMATCH"))
                .andExpect(jsonPath("$.currentVersion").value(1))
                .andExpect(jsonPath("$.changedObjects[0]").value("box_http"))
                .andExpect(jsonPath("$.changedJoints[0]").value("joint_1"));

        // 非法操作 -> 400（引用不存在的坐标系）。
        String invalid = """
                {
                  "requestId": "http-req-invalid",
                  "expectedVersion": 1,
                  "objects": [
                    {"op": "ADD", "objectId": "box_bad", "frameId": "no_frame"}
                  ]
                }
                """;
        mvc().perform(post("/api/planning-scene/updates")
                        .contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isString());

        // Bean Validation：requestId 为空 -> 400。
        String blankId = """
                {"requestId": "", "expectedVersion": 1}
                """;
        mvc().perform(post("/api/planning-scene/updates")
                        .contentType(MediaType.APPLICATION_JSON).content(blankId))
                .andExpect(status().isBadRequest());

        // 当前快照与历史快照。
        mvc().perform(get("/api/planning-scene/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.worldObjects[0].objectId").value("box_http"))
                .andExpect(jsonPath("$.worldObjects[0].payload")
                        .value(org.hamcrest.Matchers.containsString("\"shape\"")));
        mvc().perform(get("/api/planning-scene/versions/0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.worldObjects").isArray());

        // 不存在的历史版本 -> 404。
        mvc().perform(get("/api/planning-scene/versions/42"))
                .andExpect(status().isNotFound());
    }

    @Test
    void attachOverHttpIsVisibleInSnapshot() throws Exception {
        String body = """
                {
                  "requestId": "http-attach",
                  "expectedVersion": 0,
                  "objects": [
                    {"op": "ADD", "objectId": "gripper_box", "frameId": "base_link"}
                  ],
                  "attachments": [
                    {"op": "ATTACH", "objectId": "gripper_box", "linkName": "tool0"}
                  ]
                }
                """;
        mvc().perform(post("/api/planning-scene/updates")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));

        mvc().perform(get("/api/planning-scene/current"))
                .andExpect(jsonPath("$.worldObjects").isArray())
                .andExpect(jsonPath("$.worldObjects.length()").value(0))
                .andExpect(jsonPath("$.attachments.length()").value(1))
                .andExpect(jsonPath("$.attachments[0].objectId").value("gripper_box"))
                .andExpect(jsonPath("$.attachments[0].linkName").value("tool0"));
    }
}
