package com.example.starter;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * REST 入口测试：验证 HTTP 状态码与统一错误体 {code, message}。
 */
@AutoConfigureMockMvc
class ArbitrationApiTest extends AbstractArbitrationTest {

    @Autowired
    private MockMvc mvc;

    private static final String CLAIM_BODY = """
            {"controllerId":"ctrl-a","requestId":"req-1","resourceId":"joint1",
             "priority":10,"leaseMs":60000}
            """;

    @Test
    void claimReplayAndConflictSemanticsOverHttp() throws Exception {
        mvc.perform(post("/api/claims").contentType(MediaType.APPLICATION_JSON).content(CLAIM_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ACTIVE"))
                .andExpect(jsonPath("$.replayed").value(false));

        // 同参重放幂等
        mvc.perform(post("/api/claims").contentType(MediaType.APPLICATION_JSON).content(CLAIM_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));

        // 异参重放 409
        mvc.perform(post("/api/claims").contentType(MediaType.APPLICATION_JSON).content("""
                {"controllerId":"ctrl-a","requestId":"req-1","resourceId":"joint1",
                 "priority":99,"leaseMs":60000}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REPLAY_CONFLICT"));

        // 低优先级竞争 409
        mvc.perform(post("/api/claims").contentType(MediaType.APPLICATION_JSON).content("""
                {"controllerId":"ctrl-b","requestId":"req-2","resourceId":"joint1",
                 "priority":1,"leaseMs":1000}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_BUSY"));

        mvc.perform(get("/api/resources/joint1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holderControllerId").value("ctrl-a"));
    }

    @Test
    void commandValidationErrorCodesOverHttp() throws Exception {
        mvc.perform(post("/api/claims").contentType(MediaType.APPLICATION_JSON).content(CLAIM_BODY))
                .andExpect(status().isOk());
        long now = TestClockConfig.T0;

        mvc.perform(post("/api/commands").contentType(MediaType.APPLICATION_JSON).content("""
                {"controllerId":"ctrl-a","resourceId":"joint1","seq":1,"ts":%d,
                 "position":10.0,"velocity":1.0,"acceleration":1.0}
                """.formatted(now)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUEUED"));

        // 时钟回拨：可区分错误码
        mvc.perform(post("/api/commands").contentType(MediaType.APPLICATION_JSON).content("""
                {"controllerId":"ctrl-a","resourceId":"joint1","seq":2,"ts":%d,
                 "position":10.0,"velocity":1.0,"acceleration":1.0}
                """.formatted(now - 100)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CLOCK_ROLLBACK"));

        // 位置越界
        mvc.perform(post("/api/commands").contentType(MediaType.APPLICATION_JSON).content("""
                {"controllerId":"ctrl-a","resourceId":"joint1","seq":2,"ts":%d,
                 "position":999.0,"velocity":1.0,"acceleration":1.0}
                """.formatted(now + 100)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("POSITION_OUT_OF_BOUNDS"));
    }

    @Test
    void estopLocksClaimsUntilResumeAndDegradeEndpointWorks() throws Exception {
        mvc.perform(post("/api/claims").contentType(MediaType.APPLICATION_JSON).content(CLAIM_BODY))
                .andExpect(status().isOk());

        mvc.perform(post("/api/estop")).andExpect(status().isNoContent());

        mvc.perform(post("/api/claims").contentType(MediaType.APPLICATION_JSON).content("""
                {"controllerId":"ctrl-c","requestId":"req-9","resourceId":"joint2",
                 "priority":100,"leaseMs":1000}
                """))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("ESTOP_ACTIVE"));

        mvc.perform(post("/api/resume")).andExpect(status().isNoContent());

        mvc.perform(post("/api/claims").contentType(MediaType.APPLICATION_JSON).content("""
                {"controllerId":"ctrl-c","requestId":"req-9","resourceId":"joint2",
                 "priority":100,"leaseMs":1000}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ACTIVE"));

        // 触发降级检测：无到期租约时返回空列表
        mvc.perform(post("/api/maintenance/degrade"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        mvc.perform(get("/api/safe-events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void unknownResourceReturns404WithErrorBody() throws Exception {
        mvc.perform(get("/api/resources/no-such-joint"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }
}
