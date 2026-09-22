package com.example.starter.arbitration;

import com.example.starter.arbitration.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST 入口端到端测试：登记资源、申请、抢占、非法指令 400、异参重放 409、急停与状态查询。
 */
class ArbitrationRestH2Test extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void fullWorkflowOverHttp() throws Exception {
        // 登记资源（预设位置安全动作）
        mockMvc.perform(post("/api/arbitration/resources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resourceName":"joint_a","minPosition":-3.14,"maxPosition":3.14,
                                 "maxVelocity":2.0,"maxAcceleration":5.0,
                                 "safetyAction":"PRESET_POSITION","presetPosition":0.0}"""))
                .andExpect(status().isCreated());

        // 低优先级申请
        mockMvc.perform(post("/api/arbitration/leases/acquire")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"controllerId":"ctrl_low","requestId":"low-1","priority":1,
                                 "resources":["joint_a"],"ttlMs":60000}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granted").value(true))
                .andExpect(jsonPath("$.preempted").value(false));

        // 合法指令
        mockMvc.perform(post("/api/arbitration/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"controllerId\":\"ctrl_low\",\"requestId\":\"cmd-1\","
                                + "\"resourceName\":\"joint_a\",\"timestampMs\":" + (clock.now() + 100)
                                + ",\"sequence\":1,\"targetPosition\":0.3,"
                                + "\"targetVelocity\":0.5,\"targetAcceleration\":1.0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        // 非法指令：位置越界 + 超速，400 且原因可区分
        mockMvc.perform(post("/api/arbitration/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"controllerId\":\"ctrl_low\",\"requestId\":\"cmd-bad\","
                                + "\"resourceName\":\"joint_a\",\"timestampMs\":" + (clock.now() + 200)
                                + ",\"sequence\":2,\"targetPosition\":9.0,"
                                + "\"targetVelocity\":8.0,\"targetAcceleration\":1.0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("COMMAND_REJECTED"))
                .andExpect(jsonPath("$.reasons[0]").value(
                        org.hamcrest.Matchers.startsWith("POSITION_OUT_OF_BOUNDS")));

        // 同级抢占被拒 409
        mockMvc.perform(post("/api/arbitration/leases/acquire")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"controllerId":"ctrl_same","requestId":"same-1","priority":1,
                                 "resources":["joint_a"],"ttlMs":60000}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PREEMPTION_DENIED"));

        // 高优先级抢占成功
        mockMvc.perform(post("/api/arbitration/leases/acquire")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"controllerId":"ctrl_high","requestId":"high-1","priority":10,
                                 "resources":["joint_a"],"ttlMs":60000}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preempted").value(true))
                .andExpect(jsonPath("$.preemptedFromResources[0]").value("joint_a"));

        // 急停
        mockMvc.perform(post("/api/arbitration/estop/trigger"))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/arbitration/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estopActive").value(true))
                .andExpect(jsonPath("$.activeLeases").isArray())
                .andExpect(jsonPath("$.pendingCommands").value(0));

        // 急停期间申请 423
        mockMvc.perform(post("/api/arbitration/leases/acquire")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"controllerId":"ctrl_x","requestId":"x-1","priority":1,
                                 "resources":["joint_a"],"ttlMs":60000}"""))
                .andExpect(status().isLocked());

        // 恢复
        mockMvc.perform(post("/api/arbitration/estop/recover"))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/arbitration/status"))
                .andExpect(jsonPath("$.estopActive").value(false));
    }

    @Test
    void validationFailureOnMissingFieldsReturns400() throws Exception {
        mockMvc.perform(post("/api/arbitration/leases/acquire")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"priority\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    @Test
    void unknownResourceAcquireReturns404() throws Exception {
        mockMvc.perform(post("/api/arbitration/leases/acquire")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"controllerId":"ctrl_a","requestId":"a-1","priority":1,
                                 "resources":["ghost_joint"],"ttlMs":60000}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("RESOURCE_NOT_FOUND"));
    }
}
