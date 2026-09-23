package com.example.starter.ct;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * REST API 端到端测试：追加叶、树头、两类证明与验证、观察者上报、告警查询及稳定错误语义。
 * 同时固定交付用的合成演示向量。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CtApiIntegrationTest {

    private static final String DEMO_DER_BASE64 = "ZGVtby1jZXJ0LTAwMQ==";
    private static final String DEMO_ENTRY_HASH = "e075cc8e937ebb3687b8508f1f1badf5646b993f89b0fbc2810f248bd9dfbc0d";
    private static final String DEMO_ROOT_SIZE1 = "7900c095ae9a3e04ef3c7217cac4bf80e56ad9209b3f68960e14e3b1f1befdbd";
    private static final String DEMO_FAKE_ROOT = "a40f1b0bc021ecac43726047536a9e7e3e52a9e6825f2b92d1dc264e8619752f";
    private static final long DEMO_TIMESTAMP = 1760000000000L;
    private static final String DEMO_OK_SIGNATURE =
            "JLzEFyrF+TxyO8MQPxDkUMBXAmNKKkkynscDJ856ZpK96h7+IR7EAMJVVBfS0gQQ5Y7qzGfYlgcO0ZZwmO20Aw==";
    private static final String DEMO_CONFLICT_SIGNATURE =
            "LHt1CV0dXu1yfUkA/pT4N5YimwtPIGypGOtyiS3G2p+6Q1lNxQcv/zjD4XRwfPKr0lCSMWRGWlmC8IEC3YyyAg==";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM split_view_alert");
        jdbc.update("DELETE FROM observer_report");
        jdbc.update("DELETE FROM observer_state");
        jdbc.update("DELETE FROM ct_leaf");
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    @Test
    void demoSignaturesMatchPinnedVectors() {
        // 固定交付演示用的签名向量（Ed25519 为确定性签名）。
        assertEquals(DEMO_OK_SIGNATURE, TestKeys.sign("observer-a", 1, DEMO_ROOT_SIZE1, DEMO_TIMESTAMP));
        assertEquals(DEMO_CONFLICT_SIGNATURE, TestKeys.sign("observer-a", 1, DEMO_FAKE_ROOT, DEMO_TIMESTAMP));
    }

    @Test
    void demoFlowAppendVerifyAndTriggerAlert() throws Exception {
        // 1. 追加叶。
        mockMvc.perform(post("/api/ct/leaves").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("requestId", "demo-req-1", "derBase64", DEMO_DER_BASE64))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leafIndex").value(0))
                .andExpect(jsonPath("$.treeSize").value(1))
                .andExpect(jsonPath("$.entryHash").value(DEMO_ENTRY_HASH))
                .andExpect(jsonPath("$.leafHash").value(DEMO_ROOT_SIZE1));

        // 2. 树头。
        mockMvc.perform(get("/api/ct/tree-head"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.treeSize").value(1))
                .andExpect(jsonPath("$.rootHash").value(DEMO_ROOT_SIZE1));

        // 3. 包含证明（单叶树路径为空）并本地验证。
        mockMvc.perform(get("/api/ct/proofs/inclusion").param("leafIndex", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leafHash").value(DEMO_ROOT_SIZE1))
                .andExpect(jsonPath("$.auditPath").isEmpty());

        mockMvc.perform(post("/api/ct/proofs/inclusion/verify").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("leafHash", DEMO_ROOT_SIZE1, "leafIndex", 0, "treeSize", 1,
                        "auditPath", List.of(), "rootHash", DEMO_ROOT_SIZE1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));

        // 4. 匹配根的上报：接受且无告警。
        mockMvc.perform(post("/api/ct/observers/reports").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("observerId", "observer-a", "treeSize", 1, "rootHash", DEMO_ROOT_SIZE1,
                        "timestamp", DEMO_TIMESTAMP, "signature", DEMO_OK_SIGNATURE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.alertCreated").value(false))
                .andExpect(jsonPath("$.observerPosition").value(1));

        // 5. 同一 treeSize 的不同有效根：触发唯一 SPLIT_VIEW 告警。
        mockMvc.perform(post("/api/ct/observers/reports").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("observerId", "observer-a", "treeSize", 1, "rootHash", DEMO_FAKE_ROOT,
                        "timestamp", DEMO_TIMESTAMP, "signature", DEMO_CONFLICT_SIGNATURE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.alertCreated").value(true))
                .andExpect(jsonPath("$.alertId").isNumber());

        // 6. 告警查询保留双方证据。
        mockMvc.perform(get("/api/ct/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts.length()").value(1))
                .andExpect(jsonPath("$.alerts[0].type").value("SPLIT_VIEW"))
                .andExpect(jsonPath("$.alerts[0].observerId").value("observer-a"))
                .andExpect(jsonPath("$.alerts[0].treeSize").value(1))
                .andExpect(jsonPath("$.alerts[0].localRootHash").value(DEMO_ROOT_SIZE1))
                .andExpect(jsonPath("$.alerts[0].reportedRootHash").value(DEMO_FAKE_ROOT));
    }

    @Test
    void multiLeafProofsVerifyOverApi() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/ct/leaves").contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("requestId", "req-" + i, "derBase64",
                            java.util.Base64.getEncoder().encodeToString(("leaf-" + i).getBytes())))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.leafIndex").value(i));
        }

        var head = mockMvc.perform(get("/api/ct/tree-head")).andExpect(status().isOk())
                .andExpect(jsonPath("$.treeSize").value(5)).andReturn();
        String root5 = objectMapper.readTree(head.getResponse().getContentAsString()).get("rootHash").asText();

        var proof = mockMvc.perform(get("/api/ct/proofs/inclusion")
                .param("leafIndex", "2").param("treeSize", "5"))
                .andExpect(status().isOk()).andReturn();
        var proofJson = objectMapper.readTree(proof.getResponse().getContentAsString());
        List<String> auditPath = objectMapper.convertValue(proofJson.get("auditPath"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));

        mockMvc.perform(post("/api/ct/proofs/inclusion/verify").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("leafHash", proofJson.get("leafHash").asText(), "leafIndex", 2, "treeSize", 5,
                        "auditPath", auditPath, "rootHash", root5))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));

        // 反例：篡改根后验证失败。
        mockMvc.perform(post("/api/ct/proofs/inclusion/verify").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("leafHash", proofJson.get("leafHash").asText(), "leafIndex", 2, "treeSize", 5,
                        "auditPath", auditPath, "rootHash", DEMO_FAKE_ROOT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false));

        // 一致性证明 2 -> 5。
        String root2 = objectMapper.readTree(mockMvc.perform(get("/api/ct/tree-head")
                .param("treeSize", "2")).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString()).get("rootHash").asText();
        var consistency = mockMvc.perform(get("/api/ct/proofs/consistency")
                .param("first", "2").param("second", "5"))
                .andExpect(status().isOk()).andReturn();
        List<String> consistencyPath = objectMapper.convertValue(
                objectMapper.readTree(consistency.getResponse().getContentAsString()).get("consistencyPath"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));

        mockMvc.perform(post("/api/ct/proofs/consistency/verify").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("first", 2, "second", 5, "firstRoot", root2, "secondRoot", root5,
                        "consistencyPath", consistencyPath))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));

        // 反例：旧根被替换后验证失败。
        mockMvc.perform(post("/api/ct/proofs/consistency/verify").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("first", 2, "second", 5, "firstRoot", DEMO_FAKE_ROOT, "secondRoot", root5,
                        "consistencyPath", consistencyPath))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false));
    }

    @Test
    void stableErrorSemanticsOverApi() throws Exception {
        // 空日志树头。
        mockMvc.perform(get("/api/ct/tree-head"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_TREE_SIZE"));

        // 缺字段。
        mockMvc.perform(post("/api/ct/leaves").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("derBase64", DEMO_DER_BASE64))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));

        // 非法 Base64。
        mockMvc.perform(post("/api/ct/leaves").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("requestId", "r1", "derBase64", "!!not-base64!!"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_DER_ENCODING"));

        mockMvc.perform(post("/api/ct/leaves").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("requestId", "r1", "derBase64", DEMO_DER_BASE64))))
                .andExpect(status().isOk());

        // 同键异条目 409。
        mockMvc.perform(post("/api/ct/leaves").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("requestId", "r1", "derBase64",
                        java.util.Base64.getEncoder().encodeToString("other".getBytes())))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REQUEST_ID_CONFLICT"));

        // 越界索引与非法大小。
        mockMvc.perform(get("/api/ct/proofs/inclusion").param("leafIndex", "5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("LEAF_INDEX_OUT_OF_RANGE"));
        mockMvc.perform(get("/api/ct/tree-head").param("treeSize", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_TREE_SIZE"));
        mockMvc.perform(get("/api/ct/proofs/consistency").param("first", "3").param("second", "2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_TREE_SIZE_RANGE"));

        // 签名非法 401。
        mockMvc.perform(post("/api/ct/observers/reports").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("observerId", "observer-a", "treeSize", 1, "rootHash", DEMO_ROOT_SIZE1,
                        "timestamp", DEMO_TIMESTAMP, "signature", DEMO_CONFLICT_SIGNATURE))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("SIGNATURE_INVALID"));

        // 告警初始为空。
        mockMvc.perform(get("/api/ct/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts.length()").value(0));
    }
}
