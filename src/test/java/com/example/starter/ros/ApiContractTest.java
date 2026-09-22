package com.example.starter.ros;

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
 * 注册中心 REST 契约测试：HTTP 状态码必须能区分各类失败语义。
 */
class ApiContractTest extends AbstractRegistryIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    private MockMvc mvc() {
        if (mvc == null) {
            mvc = MockMvcBuilders.webAppContextSetup(context).build();
        }
        return mvc;
    }

    @Test
    void full_lifecycle_and_error_codes_over_http() throws Exception {
        String registerBody = """
                {
                  "nodeId": "http-node",
                  "namespace": "/robot",
                  "nodeName": "http_node",
                  "resources": [
                    {"category": "SERVICE", "fullyQualifiedName": "/http/svc",
                     "interfaceType": "example/srv/Trigger"}
                  ],
                  "requestId": "http-req-reg"
                }
                """;

        // 注册成功 201
        mvc().perform(post("/api/ros/nodes").contentType(MediaType.APPLICATION_JSON).content(registerBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lifecycleState").value("UNCONFIGURED"))
                .andExpect(jsonPath("$.version").value(1));

        // 同参重放返回原结果（保持首请求的 201 状态码）
        mvc().perform(post("/api/ros/nodes").contentType(MediaType.APPLICATION_JSON).content(registerBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nodeId").value("http-node"));

        // 异参重放 409
        String differentBody = registerBody.replace("/http/svc", "/http/svc-other");
        mvc().perform(post("/api/ros/nodes").contentType(MediaType.APPLICATION_JSON).content(differentBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_PARAM_MISMATCH"));

        // 重复注册（新 requestId）409
        String duplicateBody = registerBody.replace("http-req-reg", "http-req-reg-2");
        mvc().perform(post("/api/ros/nodes").contentType(MediaType.APPLICATION_JSON).content(duplicateBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("NODE_ALREADY_REGISTERED"));

        // 合法迁移 configure：200
        mvc().perform(post("/api/ros/nodes/http-node/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetState": "INACTIVE", "expectedVersion": 1, "requestId": "http-req-cfg"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleState").value("INACTIVE"));

        // 非法相邻迁移 INACTIVE -> UNCONFIGURED 是合法的；这里测试跳级到 ACTIVE 需先在 INACTIVE，
        // 直接跳 FINALIZED 之外，使用过期版本验证 409 VERSION_CONFLICT
        mvc().perform(post("/api/ros/nodes/http-node/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetState": "ACTIVE", "expectedVersion": 1, "requestId": "http-req-stale"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("VERSION_CONFLICT"));

        // 非法跳级（当前 INACTIVE v2，试图退回 UNCONFIGURED 合法；改为 ACTIVE 正确版本后再 ACTIVE->FINALIZED）
        mvc().perform(post("/api/ros/nodes/http-node/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetState": "ACTIVE", "expectedVersion": 2, "requestId": "http-req-act"}
                                """))
                .andExpect(status().isOk());
        // ACTIVE -> UNCONFIGURED 非法相邻迁移 422
        mvc().perform(post("/api/ros/nodes/http-node/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetState": "UNCONFIGURED", "expectedVersion": 3, "requestId": "http-req-skip"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("ILLEGAL_TRANSITION"));

        // 终结 ACTIVE -> FINALIZED
        mvc().perform(post("/api/ros/nodes/http-node/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetState": "FINALIZED", "expectedVersion": 3, "requestId": "http-req-fin"}
                                """))
                .andExpect(status().isOk());
        // 已终结节点迁移 410
        mvc().perform(post("/api/ros/nodes/http-node/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetState": "INACTIVE", "expectedVersion": 4, "requestId": "http-req-dead"}
                                """))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.errorCode").value("NODE_FINALIZED"));

        // 未知节点 404
        mvc().perform(get("/api/ros/nodes/ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NODE_NOT_FOUND"));
    }

    @Test
    void crash_then_duplicate_crash_and_events_endpoint() throws Exception {
        String body = """
                {
                  "nodeId": "crash-http",
                  "namespace": "/robot",
                  "nodeName": "crash_http",
                  "resources": [
                    {"category": "TOPIC", "fullyQualifiedName": "/c/t",
                     "interfaceType": "std_msgs/msg/Empty"}
                  ],
                  "requestId": "c-req-reg"
                }
                """;
        mvc().perform(post("/api/ros/nodes").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        mvc().perform(post("/api/ros/nodes/crash-http/crash")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId": "c-req-crash"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alive").value(false));

        // 重复崩溃（新 requestId）410 NODE_CRASHED
        mvc().perform(post("/api/ros/nodes/crash-http/crash")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId": "c-req-crash-2"}
                                """))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.errorCode").value("NODE_CRASHED"));

        // 事件按 revision 递增返回，且只有一条崩溃清理事件
        mvc().perform(get("/api/ros/events?after=0&limit=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.eventType=='NODE_CRASH_CLEANUP')]")
                        .value(org.hamcrest.Matchers.hasSize(1)));
    }
}
