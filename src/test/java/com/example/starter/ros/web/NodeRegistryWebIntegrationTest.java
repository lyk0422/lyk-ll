package com.example.starter.ros.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.starter.ros.AbstractIntegrationTest;
import com.example.starter.ros.TestDataCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 真实 HTTP 入口测试:校验注册/迁移/异常退出/事件订阅的状态码、
 * 机器可读错误码与 JSON 响应。
 */
@AutoConfigureMockMvc
class NodeRegistryWebIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        TestDataCleaner.cleanAll(jdbcTemplate);
    }

    @Test
    void registerTransitionCrashRecoverFlowOverHttp() throws Exception {
        // 注册:携带命名空间、节点名、TOPIC+SERVICE 资源与 requestId
        String registerBody = """
                {
                  "nodeId": "robot_talker",
                  "namespace": "/robot",
                  "nodeName": "talker",
                  "resources": [
                    {"category": "TOPIC", "name": "chatter",
                     "interfaceType": "std_msgs/msg/String"},
                    {"category": "SERVICE", "name": "set_speed",
                     "interfaceType": "example_interfaces/srv/SetBool"}
                  ],
                  "requestId": "reg-001"
                }
                """;
        mockMvc.perform(post("/api/ros/nodes")
                        .contentType(MediaType.APPLICATION_JSON).content(registerBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nodeId").value("robot_talker"))
                .andExpect(jsonPath("$.state").value("UNCONFIGURED"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.resources[0].fqName").exists());

        // 合法相邻迁移 UNCONFIGURED -> INACTIVE
        mockMvc.perform(post("/api/ros/nodes/transition")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"nodeId":"robot_talker","targetState":"INACTIVE",
                                 "expectedVersion":0,"requestId":"tr-001"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("INACTIVE"))
                .andExpect(jsonPath("$.version").value(1));

        // 非法相邻迁移 INACTIVE -> UNCONFIGURED? 合法;这里测 ACTIVE 需先到 ACTIVE
        mockMvc.perform(post("/api/ros/nodes/transition")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"nodeId":"robot_talker","targetState":"ACTIVE",
                                 "expectedVersion":1,"requestId":"tr-002"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ACTIVE"));

        // 异常退出
        mockMvc.perform(post("/api/ros/nodes/crash")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"nodeId":"robot_talker","requestId":"crash-001"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.crashed").value(true))
                .andExpect(jsonPath("$.resources").isEmpty());

        // 从检查点恢复
        mockMvc.perform(post("/api/ros/nodes/recover")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"nodeId":"robot_talker","requestId":"recover-001"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.crashed").value(false))
                .andExpect(jsonPath("$.resources.length()").value(2));
    }

    @Test
    void conflictErrorCodesAreMachineReadable() throws Exception {
        mockMvc.perform(post("/api/ros/nodes")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"nodeId":"n1","namespace":"/ns","nodeName":"a",
                                 "resources":[],"requestId":"r1"}
                                """))
                .andExpect(status().isCreated());

        // 重复注册
        mockMvc.perform(post("/api/ros/nodes")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"nodeId":"n1","namespace":"/ns","nodeName":"a",
                                 "resources":[],"requestId":"r2"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("NODE_ALREADY_REGISTERED"));

        // 异参重放
        mockMvc.perform(post("/api/ros/nodes")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"nodeId":"n1","namespace":"/ns","nodeName":"other",
                                 "resources":[],"requestId":"r1"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_CONFLICT"));

        // 非法迁移(UNCONFIGURED 直接到 ACTIVE)
        mockMvc.perform(post("/api/ros/nodes/transition")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"nodeId":"n1","targetState":"ACTIVE",
                                 "expectedVersion":0,"requestId":"t1"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ILLEGAL_TRANSITION"));

        // 版本冲突
        mockMvc.perform(post("/api/ros/nodes/transition")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"nodeId":"n1","targetState":"INACTIVE",
                                 "expectedVersion":99,"requestId":"t2"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("VERSION_CONFLICT"));

        // 节点不存在 -> 404
        mockMvc.perform(get("/api/ros/nodes/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NODE_NOT_FOUND"));
    }

    @Test
    void duplicateTransitionRequestDoesNotCreateExtraEvents() throws Exception {
        mockMvc.perform(post("/api/ros/nodes")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"nodeId":"n1","namespace":"/ns","nodeName":"a",
                                 "resources":[],"requestId":"r1"}
                                """))
                .andExpect(status().isCreated());

        String body = """
                {"nodeId":"n1","targetState":"INACTIVE",
                 "expectedVersion":0,"requestId":"t1"}
                """;
        mockMvc.perform(post("/api/ros/nodes/transition")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        // 同 requestId 同参重放:仍成功返回原结果,状态停在 INACTIVE
        mockMvc.perform(post("/api/ros/nodes/transition")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("INACTIVE"))
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(get("/api/ros/events").param("subscriberId", "s1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(2))
                .andExpect(jsonPath("$.events[0].revision").value(1))
                .andExpect(jsonPath("$.events[1].revision").value(2));
    }
}
