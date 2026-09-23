package com.example.starter.evidence;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 两阶段清除 API 测试：到期边界、原子标记、确认冻结清单、标记后救回与请求指纹。
 */
class PurgeApiTest extends AbstractIntegrationTest {

    private static final String CUTOFF = "2026-09-23T00:00:00Z";

    private void register(String evidenceId, String expiresAt) throws Exception {
        mockMvc.perform(post("/api/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "evidenceId", evidenceId,
                                "sha256", digestOf(evidenceId),
                                "collectedAt", "2026-09-20T10:00:00Z",
                                "expiresAt", expiresAt))))
                .andExpect(status().isCreated());
    }

    private String markBody(String requestId, List<String> evidenceIds, String cutoff) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "requestId", requestId, "evidenceIds", evidenceIds, "cutoff", cutoff));
    }

    private String markAndGetBatchId(String requestId, List<String> evidenceIds) throws Exception {
        String response = mockMvc.perform(post("/api/purge-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(markBody(requestId, evidenceIds, CUTOFF)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("batchId").asText();
    }

    private String confirmAndGetManifest(String batchId, String requestId) throws Exception {
        return mockMvc.perform(post("/api/purge-batches/{batchId}/confirm", batchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", requestId, "expectedVersion", 1))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void markExpiredEvidenceCreatesMarkedBatch() throws Exception {
        register("EV-P1", "2026-09-22T00:00:00Z");
        mockMvc.perform(post("/api/purge-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(markBody("m-p1", List.of("EV-P1"), CUTOFF)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("MARKED"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.candidates", hasSize(1)))
                .andExpect(jsonPath("$.candidates[0].evidenceId").value("EV-P1"))
                .andExpect(jsonPath("$.candidates[0].finalStatus").doesNotExist());

        mockMvc.perform(get("/api/evidence/EV-P1"))
                .andExpect(jsonPath("$.status").value("MARKED"));
    }

    @Test
    void markEligibilityBoundaryAtCutoff() throws Exception {
        register("EV-P2-EQ", CUTOFF);
        register("EV-P2-AFTER", "2026-09-23T00:00:01Z");

        mockMvc.perform(post("/api/purge-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(markBody("m-p2a", List.of("EV-P2-EQ"), CUTOFF)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/purge-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(markBody("m-p2b", List.of("EV-P2-AFTER"), CUTOFF)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EVIDENCE_NOT_ELIGIBLE"));
    }

    @Test
    void markWithIneligibleCandidateRollsBackAtomically() throws Exception {
        register("EV-P3-OK", "2026-09-22T00:00:00Z");
        register("EV-P3-NOT-EXPIRED", "2026-09-24T00:00:00Z");

        mockMvc.perform(post("/api/purge-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(markBody("m-p3", List.of("EV-P3-OK", "EV-P3-NOT-EXPIRED"), CUTOFF)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EVIDENCE_NOT_ELIGIBLE"));

        // 原子性：合格证据也不被标记，批次与候选清单均未写入。
        mockMvc.perform(get("/api/evidence/EV-P3-OK"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM purge_batch", Integer.class).intValue());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM purge_batch_item", Integer.class).intValue());
    }

    @Test
    void activeHoldBlocksMarkUntilReleased() throws Exception {
        register("EV-P4", "2026-09-22T00:00:00Z");
        String holdResponse = mockMvc.perform(post("/api/holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", "h-p4", "caseId", "CASE-1",
                                "evidenceIds", List.of("EV-P4")))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String holdId = objectMapper.readTree(holdResponse).get("holdId").asText();

        mockMvc.perform(post("/api/purge-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(markBody("m-p4a", List.of("EV-P4"), CUTOFF)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EVIDENCE_NOT_ELIGIBLE"));

        mockMvc.perform(post("/api/holds/{holdId}/release", holdId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", "rel-p4", "expectedVersion", 1))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/purge-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(markBody("m-p4b", List.of("EV-P4"), CUTOFF)))
                .andExpect(status().isCreated());
    }

    @Test
    void markReplayAndFingerprintConflict() throws Exception {
        register("EV-P5", "2026-09-22T00:00:00Z");
        String batchId = markAndGetBatchId("m-p5", List.of("EV-P5"));

        mockMvc.perform(post("/api/purge-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(markBody("m-p5", List.of("EV-P5"), CUTOFF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value(batchId))
                .andExpect(jsonPath("$.replayed").value(true));

        mockMvc.perform(post("/api/purge-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(markBody("m-p5", List.of("EV-P5"), "2026-09-24T00:00:00Z")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_FINGERPRINT_CONFLICT"));
    }

    @Test
    void markWithStaleExpectedVersionReturns409() throws Exception {
        register("EV-P6", "2026-09-22T00:00:00Z");
        mockMvc.perform(post("/api/purge-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", "m-p6", "evidenceIds", List.of("EV-P6"),
                                "cutoff", CUTOFF, "expectedVersion", 99))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REGISTRY_VERSION_CONFLICT"));
    }

    @Test
    void confirmPurgesCandidatesAndFreezesManifest() throws Exception {
        register("EV-P7", "2026-09-22T00:00:00Z");
        String batchId = markAndGetBatchId("m-p7", List.of("EV-P7"));

        mockMvc.perform(post("/api/purge-batches/{batchId}/confirm", batchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", "c-p7", "expectedVersion", 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.manifest.items[0].evidenceId").value("EV-P7"))
                .andExpect(jsonPath("$.manifest.items[0].result").value("PURGED"));

        mockMvc.perform(get("/api/evidence/EV-P7"))
                .andExpect(jsonPath("$.status").value("PURGED"));
        mockMvc.perform(get("/api/evidence/EV-P7/custody-events"))
                .andExpect(jsonPath("$[1].eventType").value("MARKED"))
                .andExpect(jsonPath("$[2].eventType").value("PURGED"));
    }

    @Test
    void repeatedConfirmReturnsFrozenManifestAndManifestIsImmutable() throws Exception {
        register("EV-P8", "2026-09-22T00:00:00Z");
        String batchId = markAndGetBatchId("m-p8", List.of("EV-P8"));
        String first = confirmAndGetManifest(batchId, "c-p8");
        String firstManifest = objectMapper.readTree(first).get("manifest").toString();

        String replay = confirmAndGetManifest(batchId, "c-p8");
        org.junit.jupiter.api.Assertions.assertTrue(objectMapper.readTree(replay).get("replayed").asBoolean());
        assertEquals(firstManifest, objectMapper.readTree(replay).get("manifest").toString());

        String queried = mockMvc.perform(get("/api/purge-batches/{batchId}", batchId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(firstManifest, objectMapper.readTree(queried).get("manifest").toString());

        mockMvc.perform(post("/api/purge-batches/{batchId}/confirm", batchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", "c-p8-other", "expectedVersion", 1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PURGE_BATCH_ALREADY_CONFIRMED"));
    }

    @Test
    void confirmWithWrongVersionLeavesBatchUntouched() throws Exception {
        register("EV-P9", "2026-09-22T00:00:00Z");
        String batchId = markAndGetBatchId("m-p9", List.of("EV-P9"));

        mockMvc.perform(post("/api/purge-batches/{batchId}/confirm", batchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", "c-p9", "expectedVersion", 5))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PURGE_BATCH_VERSION_CONFLICT"));

        // 确认失败不产生任何变更：批次仍 MARKED、无清单、证据仍 MARKED。
        mockMvc.perform(get("/api/purge-batches/{batchId}", batchId))
                .andExpect(jsonPath("$.status").value("MARKED"))
                .andExpect(jsonPath("$.manifest").doesNotExist());
        mockMvc.perform(get("/api/evidence/EV-P9"))
                .andExpect(jsonPath("$.status").value("MARKED"));
    }

    @Test
    void holdAfterMarkRescuesEvidenceAndExcludesItFromPurge() throws Exception {
        register("EV-P10", "2026-09-22T00:00:00Z");
        String batchId = markAndGetBatchId("m-p10", List.of("EV-P10"));

        // 标记后、最终确认前保全生效：证据被原子救回。
        mockMvc.perform(post("/api/holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", "h-p10", "caseId", "CASE-RESCUE",
                                "evidenceIds", List.of("EV-P10")))))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/evidence/EV-P10"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 确认时该候选被排除出清除结果，清单记录为救回。
        mockMvc.perform(post("/api/purge-batches/{batchId}/confirm", batchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", "c-p10", "expectedVersion", 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manifest.items[0].result").value("RESCUED"));

        mockMvc.perform(get("/api/evidence/EV-P10"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mockMvc.perform(get("/api/evidence/EV-P10/custody-events"))
                .andExpect(jsonPath("$[1].eventType").value("MARKED"))
                .andExpect(jsonPath("$[2].eventType").value("RESCUED"))
                .andExpect(jsonPath("$[3].eventType").value("HOLD_CREATED"));
    }

    @Test
    void holdOnPurgedEvidenceFailsEntirely() throws Exception {
        register("EV-P11", "2026-09-22T00:00:00Z");
        register("EV-P12", "2026-09-22T00:00:00Z");
        String batchId = markAndGetBatchId("m-p11", List.of("EV-P11"));
        confirmAndGetManifest(batchId, "c-p11");

        mockMvc.perform(post("/api/holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", "h-p11", "caseId", "CASE-LATE",
                                "evidenceIds", List.of("EV-P11", "EV-P12")))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVIDENCE_ALREADY_PURGED"));

        // 整笔失败：未创建任何保全单，另一证据不受影响。
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM legal_hold", Integer.class).intValue());
        mockMvc.perform(get("/api/evidence/EV-P12"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }
}
