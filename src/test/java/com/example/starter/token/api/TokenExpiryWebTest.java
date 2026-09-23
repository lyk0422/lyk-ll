package com.example.starter.token.api;

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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * HTTP 到期边界测试：TTL=0 时令牌签发即到期，now &gt;= expiresAt 必须返回 410。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "refresh-tokens.default-ttl-seconds=0")
class TokenExpiryWebTest {

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

    @Test
    void alreadyExpiredTokenIsRejectedWith410AndDoesNotTriggerReplayEvent() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"user-exp\",\"clientId\":\"client-exp\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(created.getResponse().getContentAsString());
        String token = body.get("refreshToken").asText();
        String familyId = body.get("familyId").asText();

        mockMvc.perform(post("/api/sessions/rotate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-exp\",\"refreshToken\":\"" + token
                                + "\",\"clientId\":\"client-exp\"}"))
                .andExpect(status().is(410))
                .andExpect(jsonPath("$.status").value(410));

        // 到期不是重放：族保持 ACTIVE，无安全事件；异键再次使用仍按到期处理。
        mockMvc.perform(post("/api/sessions/rotate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-exp-2\",\"refreshToken\":\"" + token
                                + "\",\"clientId\":\"client-exp\"}"))
                .andExpect(status().is(410));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/sessions/" + familyId))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/sessions/" + familyId + "/events"))
                .andExpect(jsonPath("$.length()").value(0));
    }
}
