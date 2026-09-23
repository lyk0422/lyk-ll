package com.example.starter.ctlog;

import com.example.starter.ctlog.service.CtLogService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Base64;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP API 层测试：追加、树头、证明、验证、观察者上报与告警查询。
 */
@AutoConfigureMockMvc
class CtLogApiTest extends AbstractCtLogIntegrationTest {

    @Autowired
    private MockMvc mvc;

    private static String leafJson(String requestId, int seed) {
        String data = Base64.getEncoder().encodeToString(TestSigning.syntheticDer(seed));
        return "{\"requestId\":\"" + requestId + "\",\"data\":\"" + data + "\"}";
    }

    private String addLeaf(String requestId, int seed) throws Exception {
        MvcResult result = mvc.perform(post("/ct/v1/add-leaf")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(leafJson(requestId, seed)))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    @Test
    void addLeafAndIdempotentReplayAndConflict() throws Exception {
        mvc.perform(post("/ct/v1/add-leaf").contentType(MediaType.APPLICATION_JSON)
                        .content(leafJson("api-1", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leafIndex").value(0))
                .andExpect(jsonPath("$.idempotent").value(false))
                .andExpect(jsonPath("$.entryHash").isString());

        mvc.perform(post("/ct/v1/add-leaf").contentType(MediaType.APPLICATION_JSON)
                        .content(leafJson("api-1", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leafIndex").value(0))
                .andExpect(jsonPath("$.idempotent").value(true));

        mvc.perform(post("/ct/v1/add-leaf").contentType(MediaType.APPLICATION_JSON)
                        .content(leafJson("api-1", 2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void treeHeadAndStableErrors() throws Exception {
        addLeaf("th-a", 1);
        addLeaf("th-b", 2);

        mvc.perform(get("/ct/v1/tree-head"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.treeSize").value(2))
                .andExpect(jsonPath("$.rootHash").isString())
                .andExpect(jsonPath("$.timestamp").isNumber());

        mvc.perform(get("/ct/v1/tree-head").param("treeSize", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_TREE_SIZE"));

        mvc.perform(get("/ct/v1/tree-head").param("treeSize", "3"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_TREE_SIZE"));
    }

    @Test
    void inclusionProofAndLocalVerification() throws Exception {
        for (int i = 0; i < 4; i++) {
            addLeaf("inc-" + i, i);
        }
        MvcResult proofResult = mvc.perform(get("/ct/v1/inclusion-proof")
                        .param("leafIndex", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leafIndex").value(2))
                .andExpect(jsonPath("$.treeSize").value(4))
                .andReturn();
        String body = proofResult.getResponse().getContentAsString();

        mvc.perform(post("/ct/v1/verify/inclusion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));

        // 篡改根哈希后验证失败。
        com.fasterxml.jackson.databind.JsonNode node =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        String badBody = "{\"leafHash\":\"" + node.get("leafHash").asText()
                + "\",\"leafIndex\":2,\"treeSize\":4,"
                + "\"rootHash\":\"" + "f".repeat(64) + "\","
                + "\"proof\":" + node.get("proof").toString() + "}";
        mvc.perform(post("/ct/v1/verify/inclusion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false));

        mvc.perform(get("/ct/v1/inclusion-proof").param("leafIndex", "4"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("LEAF_INDEX_OUT_OF_RANGE"));
    }

    @Test
    void consistencyProofAndLocalVerification() throws Exception {
        for (int i = 0; i < 5; i++) {
            addLeaf("con-" + i, i);
        }
        MvcResult proofResult = mvc.perform(get("/ct/v1/consistency-proof")
                        .param("firstTreeSize", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstTreeSize").value(2))
                .andExpect(jsonPath("$.secondTreeSize").value(5))
                .andReturn();
        String body = proofResult.getResponse().getContentAsString();

        mvc.perform(post("/ct/v1/verify/consistency")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));

        mvc.perform(get("/ct/v1/consistency-proof")
                        .param("firstTreeSize", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_TREE_SIZE"));

        mvc.perform(get("/ct/v1/consistency-proof")
                        .param("firstTreeSize", "4").param("secondTreeSize", "2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_TREE_RANGE"));
    }

    @Test
    void observeRejectsBadSignatureAndRecordsSplitViewAlert() throws Exception {
        for (int i = 0; i < 3; i++) {
            addLeaf("obs-" + i, i);
        }
        MvcResult headResult = mvc.perform(get("/ct/v1/tree-head"))
                .andExpect(status().isOk())
                .andReturn();
        com.fasterxml.jackson.databind.JsonNode head =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(headResult.getResponse().getContentAsString());
        String logRoot = head.get("rootHash").asText();

        // 无效签名被拒。
        String badReport = "{\"observerId\":\"obs-api\",\"treeSize\":3,\"rootHash\":\"" + logRoot
                + "\",\"timestamp\":1700000000000,\"signature\":\"" + "ab".repeat(64) + "\"}";
        mvc.perform(post("/ct/v1/observe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badReport))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_SIGNATURE"));

        // 有效签名但根分歧：触发 SPLIT_VIEW 告警；重复上报不重复告警。
        String fakeRoot = java.util.HexFormat.of().formatHex(
                com.example.starter.ctlog.merkle.MerkleTree.leafHash(new byte[]{0x55}));
        long timestamp = 1_700_000_000_000L;
        byte[] message = CtLogService.canonicalObserveMessage("obs-api", 3, fakeRoot, timestamp);
        String signature = TestSigning.signWithDemoKey(message);
        String report = "{\"observerId\":\"obs-api\",\"treeSize\":3,\"rootHash\":\"" + fakeRoot
                + "\",\"timestamp\":" + timestamp + ",\"signature\":\"" + signature + "\"}";

        mvc.perform(post("/ct/v1/observe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(report))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.alertCreated").value(true));

        mvc.perform(post("/ct/v1/observe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(report))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alertCreated").value(false));

        mvc.perform(get("/ct/v1/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts", hasSize(1)))
                .andExpect(jsonPath("$.alerts[0].alertType").value("SPLIT_VIEW"))
                .andExpect(jsonPath("$.alerts[0].observerId").value("obs-api"))
                .andExpect(jsonPath("$.alerts[0].treeSize").value(3))
                .andExpect(jsonPath("$.alerts[0].logRootHash").value(logRoot))
                .andExpect(jsonPath("$.alerts[0].observerRootHash").value(fakeRoot));
    }
}
