package com.example.starter.signing;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * HTTP 层测试：验证 REST 入口的状态码与响应结构（合成数据，H2 内存库）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class SigningApiTest {

    private static final String DOC_V1 = Hashing.sha256Hex("doc-v1");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM request_log");
        jdbc.update("DELETE FROM signing_event");
        jdbc.update("DELETE FROM signature");
        jdbc.update("DELETE FROM package_step");
        jdbc.update("DELETE FROM signing_package");
    }

    @Test
    void fullFlowOverHttp() throws Exception {
        String packageId = UUID.randomUUID().toString();
        String createBody = """
                {"packageId":"%s","docSha256":"%s","expiresAt":"2026-09-24T00:00:00Z",
                 "steps":[{"signers":["alice","bob"]},{"signers":["carol"]}]}
                """.formatted(packageId, DOC_V1);
        MvcResult created = mockMvc.perform(post("/api/signing-packages")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.currentRevision").value(1))
                .andExpect(jsonPath("$.currentStep").value(0))
                .andReturn();

        // 未完成前查询证据包返回 409。
        mockMvc.perform(get("/api/signing-packages/{id}/evidence", packageId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVIDENCE_NOT_FROZEN"));

        String[] signers = {"alice", "bob", "carol"};
        JsonNode state = objectMapper.readTree(created.getResponse().getContentAsString());
        for (String signer : signers) {
            String signature = SyntheticSignatures.sign(packageId,
                    state.get("currentRevision").asInt(), signer, DOC_V1);
            String submitBody = """
                    {"requestId":"%s","signerId":"%s","revision":%d,"docSha256":"%s",
                     "expectedVersion":%d,"signature":"%s"}
                    """.formatted(UUID.randomUUID(), signer, state.get("currentRevision").asInt(),
                    DOC_V1, state.get("version").asLong(), signature);
            MvcResult submitted = mockMvc.perform(post("/api/signing-packages/{id}/signatures", packageId)
                            .contentType(MediaType.APPLICATION_JSON).content(submitBody))
                    .andExpect(status().isOk())
                    .andReturn();
            state = objectMapper.readTree(submitted.getResponse().getContentAsString()).get("packageState");
        }
        org.assertj.core.api.Assertions.assertThat(state.get("status").asText()).isEqualTo("COMPLETED");

        mockMvc.perform(get("/api/signing-packages/{id}/evidence", packageId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.finalRevision").value(1))
                .andExpect(jsonPath("$.validSignatures.length()").value(3))
                .andExpect(jsonPath("$.evidenceHash").isString());
    }

    @Test
    void unknownPackageReturns404AndInvalidBodyReturns400() throws Exception {
        mockMvc.perform(get("/api/signing-packages/{id}", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        mockMvc.perform(post("/api/signing-packages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"docSha256\":\"too-short\",\"expiresAt\":null,\"steps\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void sameKeyDifferentParamsReturns409OverHttp() throws Exception {
        String packageId = UUID.randomUUID().toString();
        String createBody = """
                {"packageId":"%s","docSha256":"%s","expiresAt":"2026-09-24T00:00:00Z",
                 "steps":[{"signers":["alice","bob"]}]}
                """.formatted(packageId, DOC_V1);
        MvcResult created = mockMvc.perform(post("/api/signing-packages")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode state = objectMapper.readTree(created.getResponse().getContentAsString());

        String requestId = UUID.randomUUID().toString();
        String aliceBody = """
                {"requestId":"%s","signerId":"alice","revision":1,"docSha256":"%s",
                 "expectedVersion":%d,"signature":"%s"}
                """.formatted(requestId, DOC_V1, state.get("version").asLong(),
                SyntheticSignatures.sign(packageId, 1, "alice", DOC_V1));
        mockMvc.perform(post("/api/signing-packages/{id}/signatures", packageId)
                        .contentType(MediaType.APPLICATION_JSON).content(aliceBody))
                .andExpect(status().isOk());

        String bobSameKey = """
                {"requestId":"%s","signerId":"bob","revision":1,"docSha256":"%s",
                 "expectedVersion":%d,"signature":"%s"}
                """.formatted(requestId, DOC_V1, state.get("version").asLong() + 1,
                SyntheticSignatures.sign(packageId, 1, "bob", DOC_V1));
        mockMvc.perform(post("/api/signing-packages/{id}/signatures", packageId)
                        .contentType(MediaType.APPLICATION_JSON).content(bobSameKey))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_FINGERPRINT_MISMATCH"));
    }
}
