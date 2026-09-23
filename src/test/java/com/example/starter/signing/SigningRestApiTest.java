package com.example.starter.signing;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.example.starter.signing.service.Hashes;
import com.example.starter.signing.support.TestClockConfiguration;

/**
 * REST API 端到端测试：真实 HTTP 入口上的状态码、响应结构与错误码。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfiguration.class)
class SigningRestApiTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM sign_request");
        jdbc.update("DELETE FROM evidence_package");
        jdbc.update("DELETE FROM sign_event");
        jdbc.update("DELETE FROM signature_record");
        jdbc.update("DELETE FROM signing_step");
        jdbc.update("DELETE FROM signing_package");
    }

    private String docHash(String content) {
        return Hashes.sha256(content);
    }

    private String createPackageJson(String hash) {
        return """
                {
                  "documentHash": "%s",
                  "expiresAt": "2030-01-01T01:00:00Z",
                  "steps": [
                    {"stepOrder": 1, "signers": ["alice", "bob"]},
                    {"stepOrder": 2, "signers": ["carol"]}
                  ]
                }
                """.formatted(hash);
    }

    @Test
    void fullHappyPathOverHttp() throws Exception {
        String hash = docHash("rest-doc");

        // 1. 建包：201 + Location
        String location = mockMvc.perform(post("/api/signing/packages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPackageJson(hash)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.packageId").isNotEmpty())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(header().string("Location",
                        org.hamcrest.Matchers.containsString("/status")))
                .andReturn().getResponse().getHeader("Location");
        String pkgId = location.replace("/api/signing/packages/", "").replace("/status", "");

        String aliceSig = syntheticSignature("alice", 1, hash);
        mockMvc.perform(post("/api/signing/packages/" + pkgId + "/signatures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signatureJson("rest-1", "alice", 1, hash, 1, aliceSig)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.completed").value(false));

        // 第二步未开放
        String carolSig = syntheticSignature("carol", 1, hash);
        mockMvc.perform(post("/api/signing/packages/" + pkgId + "/signatures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signatureJson("rest-2", "carol", 1, hash, 1, carolSig)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("STEP_NOT_OPEN"));

        mockMvc.perform(post("/api/signing/packages/" + pkgId + "/signatures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signatureJson("rest-3", "bob", 1, hash, 1,
                                syntheticSignature("bob", 1, hash))))
                .andExpect(status().isOk());

        // carol 最后签名 -> 完成冻结
        mockMvc.perform(post("/api/signing/packages/" + pkgId + "/signatures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signatureJson("rest-4", "carol", 1, hash, 1, carolSig)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(true))
                .andExpect(jsonPath("$.evidenceDigest").isNotEmpty());

        mockMvc.perform(get("/api/signing/packages/" + pkgId + "/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        mockMvc.perform(get("/api/signing/packages/" + pkgId + "/evidence"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.validSignatures.length()").value(3))
                .andExpect(jsonPath("$.events.length()").value(5))
                .andExpect(jsonPath("$.digest").isNotEmpty());
    }

    @Test
    void invalidRequestBodyReturns400() throws Exception {
        mockMvc.perform(post("/api/signing/packages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentHash\":\"abc\",\"steps\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void unknownPackageReturns404() throws Exception {
        mockMvc.perform(get("/api/signing/packages/unknown/status"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PACKAGE_NOT_FOUND"));
    }

    @Test
    void revisionAndCancelEndpointsWorkOverHttp() throws Exception {
        String hash = docHash("rest-rev");
        String body = mockMvc.perform(post("/api/signing/packages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPackageJson(hash)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String pkgId = objectMapper.readTree(body).get("packageId").asText();

        String v2 = docHash("rest-rev-v2");
        mockMvc.perform(post("/api/signing/packages/" + pkgId + "/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newDocumentHash\":\"" + v2 + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2));

        mockMvc.perform(post("/api/signing/packages/" + pkgId + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"撤销\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));

        mockMvc.perform(get("/api/signing/packages/" + pkgId + "/status"))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    private String signatureJson(String requestId, String signer, int revision,
                                 String hash, int expectedVersion, String signature) {
        return """
                {
                  "requestId": "%s",
                  "signer": "%s",
                  "revision": %d,
                  "documentHash": "%s",
                  "expectedVersion": %d,
                  "signature": "%s"
                }
                """.formatted(requestId, signer, revision, hash, expectedVersion, signature);
    }

    private String syntheticSignature(String signer, int revision, String hash) {
        // 与 SyntheticSignatureVerifier 相同的合成方案
        return Hashes.sha256("SYNTHETIC-SIGNATURE-v1|" + signer + "|" + revision + "|" + hash);
    }
}
