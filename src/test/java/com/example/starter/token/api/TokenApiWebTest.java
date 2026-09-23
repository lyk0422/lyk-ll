package com.example.starter.token.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 端到端 HTTP 语义测试：使用默认配置（嵌入式 H2 自动建表）验证真实 API 契约。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TokenApiWebTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        for (String table : List.of("security_event", "rotation_record", "refresh_token", "token_family")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult createSession(String userId, String clientId) throws Exception {
        return mockMvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + userId + "\",\"clientId\":\"" + clientId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.familyId").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.replayed").value(false))
                .andReturn();
    }

    @Test
    void fullLifecycleOverHttpIncludingReplayAndEvents() throws Exception {
        JsonNode session = json(createSession("user-web", "client-web"));
        String familyId = session.get("familyId").asText();
        String rootToken = session.get("refreshToken").asText();

        // 首次轮换成功。
        MvcResult firstRotate = mockMvc.perform(post("/api/sessions/rotate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rotateBody("req-web-1", rootToken, "client-web")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(false))
                .andReturn();
        String childToken = json(firstRotate).get("refreshToken").asText();

        // 同键安全重试 → 200 且 replayed=true，返回同一子令牌。
        mockMvc.perform(post("/api/sessions/rotate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rotateBody("req-web-1", rootToken, "client-web")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.refreshToken").value(childToken));

        // 同键改客户端 → 409。
        mockMvc.perform(post("/api/sessions/rotate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rotateBody("req-web-1", rootToken, "other-client")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        // 异键重放已消费令牌 → 403，全族撤销。
        mockMvc.perform(post("/api/sessions/rotate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rotateBody("req-web-attacker", rootToken, "client-web")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        // 族状态终态。
        mockMvc.perform(get("/api/sessions/" + familyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.revokeReason").value("REPLAY_DETECTED"))
                .andExpect(jsonPath("$.tokenCount").value(2));

        // 安全事件查询：唯一 REPLAY_DETECTED。
        mockMvc.perform(get("/api/sessions/" + familyId + "/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventType").value("REPLAY_DETECTED"))
                .andExpect(jsonPath("$[0].requestId").value("req-web-attacker"))
                .andExpect(jsonPath("$.length()").value(1));

        // 撤销族中的子代令牌再轮换 → 403。
        mockMvc.perform(post("/api/sessions/rotate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rotateBody("req-web-2", childToken, "client-web")))
                .andExpect(status().isForbidden());

        // 登出已撤销族 → 200 幂等无副作用。
        mockMvc.perform(post("/api/sessions/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(logoutBody(rootToken, "client-web")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/sessions/" + familyId + "/events"))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void logoutFreshFamilyRevokesOverHttp() throws Exception {
        JsonNode session = json(createSession("user-logout", "client-web"));
        String familyId = session.get("familyId").asText();

        mockMvc.perform(post("/api/sessions/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(logoutBody(session.get("refreshToken").asText(), "client-web")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/sessions/" + familyId))
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.revokeReason").value("LOGOUT"));
    }

    @Test
    void invalidBodiesAndUnknownResourcesAreRejectedOverHttp() throws Exception {
        // 缺失必填字段 → 400。
        mockMvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"user-1\"}"))
                .andExpect(status().isBadRequest());

        // 未知令牌轮换 → 404。
        mockMvc.perform(post("/api/sessions/rotate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rotateBody("req-x", "rt.does-not-exist", "client-web")))
                .andExpect(status().isNotFound());

        // 未知族查询 → 404。
        mockMvc.perform(get("/api/sessions/unknown-family"))
                .andExpect(status().isNotFound());
    }

    private String rotateBody(String requestId, String token, String clientId) {
        return "{\"requestId\":\"" + requestId + "\",\"refreshToken\":\"" + token
                + "\",\"clientId\":\"" + clientId + "\"}";
    }

    private String logoutBody(String token, String clientId) {
        return "{\"refreshToken\":\"" + token + "\",\"clientId\":\"" + clientId + "\"}";
    }
}
