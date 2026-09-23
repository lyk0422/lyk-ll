package com.example.starter.specimen;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检测申请 API 测试：占用、开始、取消、迟到结果与污染隔离。
 */
class AssayApiTest extends ApiTestSupport {

    private String intakeAndAliquot(String specimenId, String total, String aliquotVolume) throws Exception {
        postJson("/api/specimens", Map.of("specimenId", specimenId,
                "volumeUl", new BigDecimal(total), "custodian", "lab-a"));
        MvcResult aliquot = postJson("/api/specimens/" + specimenId + "/aliquots",
                Map.of("requestId", "req-al-" + specimenId, "expectedVersion", 0,
                        "volumesUl", java.util.List.of(new BigDecimal(aliquotVolume))));
        assertEquals(200, aliquot.getResponse().getStatus());
        return body(aliquot).get("aliquotIds").get(0).asText();
    }

    private MvcResult createAssay(String requestId, String aliquotId, long version, String volume) throws Exception {
        return postJson("/api/assay-requests", Map.of(
                "requestId", requestId,
                "aliquotId", aliquotId,
                "expectedVersion", version,
                "reservedVolumeUl", new BigDecimal(volume),
                "project", "PCR",
                "custodian", "lab-a"));
    }

    @Test
    void reserveTransitionsAliquotAndRejectsSecondReservation() throws Exception {
        String aliquotId = intakeAndAliquot("SP-1", "100", "40");
        MvcResult created = createAssay("req-as-1", aliquotId, 0, "10.000");
        assertEquals(200, created.getResponse().getStatus());
        JsonNode assay = body(created);
        assertEquals("RESERVED", assay.get("status").asText());
        assertEquals(0, new BigDecimal("10.000").compareTo(decimal(assay, "reservedVolumeUl")));

        // 同一分装上的第二个申请被拒绝：并发/重复占用不能超额
        MvcResult second = createAssay("req-as-2", aliquotId, 1, "5");
        assertEquals(409, second.getResponse().getStatus());
        assertEquals("ALIQUOT_NOT_AVAILABLE", body(second).get("code").asText());
        assertEquals(1, count("assay_request"));

        JsonNode ledger = body(getJson("/api/specimens/SP-1/ledger"));
        assertEquals(0, new BigDecimal("40.000").compareTo(decimal(ledger, "reservedVolumeUl")));
        assertTrue(ledger.get("conserved").asBoolean());
    }

    @Test
    void reserveRejectsVolumeExceedingAliquot() throws Exception {
        String aliquotId = intakeAndAliquot("SP-1", "100", "10");
        MvcResult result = createAssay("req-as-1", aliquotId, 0, "10.001");
        assertEquals(409, result.getResponse().getStatus());
        assertEquals("VOLUME_OVERDRAW", body(result).get("code").asText());
        assertEquals(0, count("assay_request"));
    }

    @Test
    void reserveRejectsTooManyDecimals() throws Exception {
        String aliquotId = intakeAndAliquot("SP-1", "100", "10");
        MvcResult result = createAssay("req-as-1", aliquotId, 0, "1.0001");
        assertEquals(400, result.getResponse().getStatus());
        assertEquals("VOLUME_PRECISION", body(result).get("code").asText());
        assertEquals(0, count("assay_request"));
    }

    @Test
    void reserveRejectsStaleVersion() throws Exception {
        String aliquotId = intakeAndAliquot("SP-1", "100", "10");
        MvcResult result = createAssay("req-as-1", aliquotId, 7, "5");
        assertEquals(409, result.getResponse().getStatus());
        assertEquals("VERSION_CONFLICT", body(result).get("code").asText());
    }

    @Test
    void reserveSameKeySameParamsReplaysFirstResult() throws Exception {
        String aliquotId = intakeAndAliquot("SP-1", "100", "10");
        MvcResult first = createAssay("req-as-1", aliquotId, 0, "5");
        MvcResult replay = createAssay("req-as-1", aliquotId, 0, "5");
        assertEquals(200, replay.getResponse().getStatus());
        assertEquals(body(first).get("assayId").asText(), body(replay).get("assayId").asText());
        assertEquals(1, count("assay_request"));
    }

    @Test
    void reserveSameKeyDifferentParamsReturns409() throws Exception {
        String aliquotId = intakeAndAliquot("SP-1", "100", "10");
        assertEquals(200, createAssay("req-as-1", aliquotId, 0, "5").getResponse().getStatus());
        // 占用量变化
        MvcResult differentVolume = createAssay("req-as-1", aliquotId, 0, "6");
        assertEquals(409, differentVolume.getResponse().getStatus());
        assertEquals("IDEMPOTENCY_CONFLICT", body(differentVolume).get("code").asText());
        // 版本变化
        MvcResult differentVersion = createAssay("req-as-1", aliquotId, 1, "5");
        assertEquals(409, differentVersion.getResponse().getStatus());
        assertEquals("IDEMPOTENCY_CONFLICT", body(differentVersion).get("code").asText());
        assertEquals(1, count("assay_request"));
    }

    @Test
    void startConsumesReservationAndCancelAfterStartRejected() throws Exception {
        String aliquotId = intakeAndAliquot("SP-1", "100", "10");
        String assayId = body(createAssay("req-as-1", aliquotId, 0, "5")).get("assayId").asText();

        MvcResult started = postJson("/api/assay-requests/" + assayId + "/start",
                Map.of("expectedVersion", 0));
        assertEquals(200, started.getResponse().getStatus());
        assertEquals("CONSUMED", body(started).get("status").asText());

        MvcResult cancel = postJson("/api/assay-requests/" + assayId + "/cancel",
                Map.of("expectedVersion", 1));
        assertEquals(409, cancel.getResponse().getStatus());
        assertEquals("ASSAY_ALREADY_STARTED", body(cancel).get("code").asText());

        JsonNode ledger = body(getJson("/api/specimens/SP-1/ledger"));
        assertEquals(0, new BigDecimal("10.000").compareTo(decimal(ledger, "consumedVolumeUl")));
        assertTrue(ledger.get("conserved").asBoolean());
    }

    @Test
    void cancelReleasesReservationAndLateResultReturns409() throws Exception {
        String aliquotId = intakeAndAliquot("SP-1", "100", "10");
        String assayId = body(createAssay("req-as-1", aliquotId, 0, "5")).get("assayId").asText();

        MvcResult cancelled = postJson("/api/assay-requests/" + assayId + "/cancel",
                Map.of("expectedVersion", 0));
        assertEquals(200, cancelled.getResponse().getStatus());
        assertEquals("CANCELLED", body(cancelled).get("status").asText());

        // 取消后到达的结果：409，不写结果、不恢复占用
        MvcResult late = postJson("/api/assay-requests/" + assayId + "/result",
                Map.of("expectedVersion", 1, "result", "POSITIVE"));
        assertEquals(409, late.getResponse().getStatus());
        assertEquals("ASSAY_CANCELLED", body(late).get("code").asText());

        JsonNode assay = body(getJson("/api/assay-requests/" + assayId));
        assertEquals("CANCELLED", assay.get("status").asText());
        assertTrue(assay.get("result").isNull(), "取消后不得写入结果");

        // 分装已释放回 AVAILABLE，可被新申请重新占用
        MvcResult reReserved = createAssay("req-as-2", aliquotId, 2, "5");
        assertEquals(200, reReserved.getResponse().getStatus());
    }

    @Test
    void resultOnlyAcceptedAfterStart() throws Exception {
        String aliquotId = intakeAndAliquot("SP-1", "100", "10");
        String assayId = body(createAssay("req-as-1", aliquotId, 0, "5")).get("assayId").asText();

        MvcResult tooEarly = postJson("/api/assay-requests/" + assayId + "/result",
                Map.of("expectedVersion", 0, "result", "NEGATIVE"));
        assertEquals(409, tooEarly.getResponse().getStatus());
        assertEquals("ASSAY_NOT_STARTED", body(tooEarly).get("code").asText());

        postJson("/api/assay-requests/" + assayId + "/start", Map.of("expectedVersion", 0));
        MvcResult recorded = postJson("/api/assay-requests/" + assayId + "/result",
                Map.of("expectedVersion", 1, "result", "NEGATIVE"));
        assertEquals(200, recorded.getResponse().getStatus());
        assertEquals("NEGATIVE", body(recorded).get("result").asText());
    }

    @Test
    void quarantineCancelsPendingAssayAndKeepsLedgerConserved() throws Exception {
        String aliquotId = intakeAndAliquot("SP-1", "100", "10");
        String assayId = body(createAssay("req-as-1", aliquotId, 0, "5")).get("assayId").asText();

        MvcResult quarantined = postJson("/api/aliquots/" + aliquotId + "/quarantine",
                Map.of("expectedVersion", 1, "reason", "污染报警"));
        assertEquals(200, quarantined.getResponse().getStatus());
        JsonNode body = body(quarantined);
        assertEquals("QUARANTINED", body.get("status").asText());
        assertEquals(1, body.get("cancelledAssayCount").asInt());

        JsonNode assay = body(getJson("/api/assay-requests/" + assayId));
        assertEquals("CANCELLED", assay.get("status").asText());

        JsonNode ledger = body(getJson("/api/specimens/SP-1/ledger"));
        assertEquals(0, new BigDecimal("10.000").compareTo(decimal(ledger, "quarantinedVolumeUl")));
        assertTrue(ledger.get("conserved").asBoolean());

        // 已隔离分装不能再次被占用
        MvcResult again = createAssay("req-as-2", aliquotId, 2, "5");
        assertEquals(409, again.getResponse().getStatus());
    }

    @Test
    void quarantinePreservesConsumedAssayAndCustodyHistory() throws Exception {
        // 同一样本分装两次：aliquot-1 将被消耗，aliquot-2 将被污染隔离
        postJson("/api/specimens", Map.of("specimenId", "SP-1",
                "volumeUl", new BigDecimal("100"), "custodian", "lab-a"));
        String aliquot1 = body(postJson("/api/specimens/SP-1/aliquots",
                Map.of("requestId", "req-al-1", "expectedVersion", 0,
                        "volumesUl", java.util.List.of(new BigDecimal("10")))))
                .get("aliquotIds").get(0).asText();
        String aliquot2 = body(postJson("/api/specimens/SP-1/aliquots",
                Map.of("requestId", "req-al-2", "expectedVersion", 1,
                        "volumesUl", java.util.List.of(new BigDecimal("20")))))
                .get("aliquotIds").get(0).asText();

        // aliquot-1：占用 -> 开始 -> 结果，并完成一次交接
        String consumedAssay = body(createAssay("req-as-1", aliquot1, 0, "5")).get("assayId").asText();
        postJson("/api/assay-requests/" + consumedAssay + "/start", Map.of("expectedVersion", 0));
        postJson("/api/assay-requests/" + consumedAssay + "/result",
                Map.of("expectedVersion", 1, "result", "POSITIVE"));
        MvcResult transfer = postJson("/api/custody/transfers", Map.of(
                "requestId", "req-tr-1", "entityType", "ALIQUOT", "entityId", aliquot1,
                "fromCustodian", "lab-a", "toCustodian", "lab-b",
                "expectedVersion", 2, "eventAt", "2026-09-23T10:00:00Z"));
        assertEquals(200, transfer.getResponse().getStatus());

        // aliquot-2：预留后被污染隔离
        String pendingAssay = body(createAssay("req-as-2", aliquot2, 0, "5")).get("assayId").asText();
        MvcResult quarantined = postJson("/api/aliquots/" + aliquot2 + "/quarantine",
                Map.of("expectedVersion", 1, "reason", "污染报警"));
        assertEquals(200, quarantined.getResponse().getStatus());
        assertEquals(1, body(quarantined).get("cancelledAssayCount").asInt());

        // 已消耗申请与结果保留，未开始申请被取消
        JsonNode consumed = body(getJson("/api/assay-requests/" + consumedAssay));
        assertEquals("CONSUMED", consumed.get("status").asText());
        assertEquals("POSITIVE", consumed.get("result").asText());
        JsonNode pending = body(getJson("/api/assay-requests/" + pendingAssay));
        assertEquals("CANCELLED", pending.get("status").asText());

        // 历史保管事件保留
        JsonNode events = body(getJson("/api/custody/events?entityType=ALIQUOT&entityId=" + aliquot1));
        assertEquals(1, events.size());
        assertEquals("lab-b", events.get(0).get("toCustodian").asText());

        // 账本仍然守恒：消耗 10 + 隔离 20 + 剩余 70 = 100
        JsonNode ledger = body(getJson("/api/specimens/SP-1/ledger"));
        assertEquals(0, new BigDecimal("10.000").compareTo(decimal(ledger, "consumedVolumeUl")));
        assertEquals(0, new BigDecimal("20.000").compareTo(decimal(ledger, "quarantinedVolumeUl")));
        assertEquals(0, new BigDecimal("70.000").compareTo(decimal(ledger, "remainingVolumeUl")));
        assertTrue(ledger.get("conserved").asBoolean());
    }
}
