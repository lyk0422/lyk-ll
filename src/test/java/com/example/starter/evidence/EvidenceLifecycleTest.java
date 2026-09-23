package com.example.starter.evidence;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.domain.CustodyEvent;
import com.example.starter.domain.CustodyEventType;
import com.example.starter.domain.Evidence;
import com.example.starter.domain.EvidenceStatus;
import com.example.starter.service.ApiException;
import com.example.starter.web.dto.RegisterEvidenceRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 证据登记主流程、重放幂等、异摘要 409 与保管事件链。
 */
class EvidenceLifecycleTest extends AbstractIntegrationTest {

    private RegisterEvidenceRequest request(String id, String content, Instant expiresAt) {
        return new RegisterEvidenceRequest(id,
                Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)),
                T0, expiresAt);
    }

    @Test
    void registerPersistsSha256AndRegisteredCustodyEvent() {
        Evidence evidence = evidenceService.register(request("E1", "synthetic-1", T0.plusSeconds(3600)));

        assertEquals("E1", evidence.evidenceId());
        assertEquals(EvidenceStatus.RETAINED, evidence.status());
        assertEquals(64, evidence.sha256().length());
        assertEquals(T0, evidence.collectedAt());

        List<CustodyEvent> events = evidenceService.custodyTrail("E1");
        assertEquals(1, events.size());
        assertEquals(CustodyEventType.REGISTERED, events.get(0).eventType());
    }

    @Test
    void replayWithSameIdAndDigestIsIdempotent() {
        var first = evidenceService.register(request("E1", "synthetic-1", T0.plusSeconds(3600)));
        var second = evidenceService.register(request("E1", "synthetic-1", T0.plusSeconds(7200)));

        assertEquals(first.sha256(), second.sha256());
        assertEquals(first.expiresAt(), second.expiresAt(), "重放返回首次登记结果，到期时间不被覆盖");
        assertEquals(1, evidenceService.custodyTrail("E1").size(), "重放不追加保管事件");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM evidence", Integer.class));
    }

    @Test
    void sameIdDifferentDigestReturns409() {
        evidenceService.register(request("E1", "synthetic-1", T0.plusSeconds(3600)));

        ApiException ex = assertThrows(ApiException.class,
                () -> evidenceService.register(request("E1", "different-content", T0.plusSeconds(3600))));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals(EvidenceStatus.RETAINED, evidenceService.get("E1").status());
    }

    @Test
    void getUnknownEvidenceReturns404() {
        ApiException ex = assertThrows(ApiException.class, () -> evidenceService.get("MISSING"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    void invalidBase64Returns400() {
        RegisterEvidenceRequest bad = new RegisterEvidenceRequest("E9", "@@@not-base64@@@", T0, T0.plusSeconds(60));
        ApiException ex = assertThrows(ApiException.class, () -> evidenceService.register(bad));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void emptyContentRejected() {
        RegisterEvidenceRequest empty = new RegisterEvidenceRequest("E9", "", T0, T0.plusSeconds(60));
        ApiException ex = assertThrows(ApiException.class, () -> evidenceService.register(empty));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM evidence", Integer.class) == 0);
    }
}
