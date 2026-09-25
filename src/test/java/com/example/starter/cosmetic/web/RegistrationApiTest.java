package com.example.starter.cosmetic.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Web 层集成测试：备案提交、幂等重放、同键异参 409、参数校验与查询接口。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RegistrationApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void cleanup() {
        jdbc.sql("DELETE FROM objection").update();
        jdbc.sql("DELETE FROM review_record").update();
        jdbc.sql("DELETE FROM test_report").update();
        jdbc.sql("DELETE FROM registration_claim").update();
        jdbc.sql("DELETE FROM registration_ingredient").update();
        jdbc.sql("DELETE FROM registration").update();
        jdbc.sql("DELETE FROM idempotency_record").update();
        jdbc.sql("DELETE FROM product").update();
    }

    private long createProduct() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"合成产品A\",\"applicant\":\"合成备案人A\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("id").asLong();
    }

    private static String submitBody(String requestId, long productId) {
        return """
                {
                  "requestId": "%s",
                  "productId": %d,
                  "packagingText": "合成包装文字",
                  "claims": ["MOISTURIZING"],
                  "ingredients": [{"ingredientCode": "GLYCERIN", "concentration": 5.0}],
                  "reports": []
                }
                """.formatted(requestId, productId);
    }

    @Test
    void submitAndQueryViaApi() throws Exception {
        long productId = createProduct();
        String requestId = UUID.randomUUID().toString();
        MvcResult submit = mockMvc.perform(post("/api/registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(requestId, productId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLICITY"))
                .andExpect(jsonPath("$.reviewPassed").value(true))
                .andExpect(jsonPath("$.publicityDeadline").exists())
                .andReturn();
        long registrationId = objectMapper.readTree(submit.getResponse().getContentAsString())
                .get("registrationId").asLong();

        mockMvc.perform(get("/api/registrations/{id}", registrationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLICITY"))
                .andExpect(jsonPath("$.claims[0]").value("MOISTURIZING"));
        mockMvc.perform(get("/api/registrations/{id}/review-records", registrationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].result").value("PASS"));
        mockMvc.perform(get("/api/products/{id}/registrations", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void idempotentReplayReturnsFirstResult() throws Exception {
        long productId = createProduct();
        String requestId = UUID.randomUUID().toString();
        MvcResult first = mockMvc.perform(post("/api/registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(requestId, productId)))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult replayed = mockMvc.perform(post("/api/registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(requestId, productId)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode firstBody = objectMapper.readTree(first.getResponse().getContentAsString());
        JsonNode replayedBody = objectMapper.readTree(replayed.getResponse().getContentAsString());
        org.assertj.core.api.Assertions.assertThat(replayedBody.get("registrationId").asLong())
                .isEqualTo(firstBody.get("registrationId").asLong());
    }

    @Test
    void sameKeyDifferentParamsReturns409() throws Exception {
        long productId = createProduct();
        String requestId = UUID.randomUUID().toString();
        mockMvc.perform(post("/api/registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody(requestId, productId)))
                .andExpect(status().isOk());
        String tampered = submitBody(requestId, productId).replace("合成包装文字", "篡改包装文字");
        mockMvc.perform(post("/api/registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tampered))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void missingRequestIdReturns400() throws Exception {
        long productId = createProduct();
        mockMvc.perform(post("/api/registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\": %d, \"packagingText\": \"合成包装文字\"}".formatted(productId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownRegistrationReturns404() throws Exception {
        mockMvc.perform(get("/api/registrations/{id}", 999999L))
                .andExpect(status().isNotFound());
    }
}
