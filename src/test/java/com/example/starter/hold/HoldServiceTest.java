package com.example.starter.hold;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.domain.LegalHold;
import com.example.starter.service.ApiException;
import com.example.starter.service.HoldService;
import com.example.starter.web.dto.CreateHoldRequest;
import com.example.starter.web.dto.ReleaseHoldRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 法律保全创建/解除：版本、幂等重放、同键异参 409、失败分支。
 */
class HoldServiceTest extends AbstractIntegrationTest {

    @Autowired
    private HoldService holdService;

    private CreateHoldRequest create(String caseId, String requestId, Long expectedVersion, String... ids) {
        return new CreateHoldRequest(caseId, Set.of(ids), requestId,
                expectedVersion == null ? 0L : expectedVersion);
    }

    @Test
    void createHoldIncrementsGlobalVersionAndAppendsHeldEvents() {
        register("E1", "c1", T0.plusSeconds(3600));
        register("E2", "c2", T0.plusSeconds(3600));

        LegalHold hold = holdService.create(create("CASE-1", "REQ-1", null, "E1", "E2"));

        assertEquals("CASE-1", hold.caseId());
        assertEquals("ACTIVE", hold.status().name());
        assertEquals(1L, hold.version());
        assertEquals(Set.of("E1", "E2"), Set.copyOf(hold.evidenceIds()));
        assertEquals(1L, jdbc.queryForObject("SELECT hold_version FROM seq_holder WHERE id=0", Long.class));
        assertEquals("HELD",
                jdbc.queryForObject(
                        "SELECT event_type FROM custody_event WHERE evidence_id='E1' AND event_type='HELD'",
                        String.class));
    }

    @Test
    void createHoldWithWrongExpectedVersionReturns409() {
        register("E1", "c1", T0.plusSeconds(3600));

        ApiException ex = assertThrows(ApiException.class,
                () -> holdService.create(create("CASE-1", "REQ-1", 5L, "E1")));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM legal_hold", Long.class));
    }

    @Test
    void createHoldWithCorrectExpectedVersionSucceeds() {
        register("E1", "c1", T0.plusSeconds(3600));
        LegalHold hold = holdService.create(create("CASE-1", "REQ-1", 0L, "E1"));
        assertEquals(1L, hold.version());
    }

    @Test
    void createHoldForUnknownEvidenceReturns404() {
        ApiException ex = assertThrows(ApiException.class,
                () -> holdService.create(create("CASE-1", "REQ-1", null, "GHOST")));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    void replayCreateReturnsFirstHold() {
        register("E1", "c1", T0.plusSeconds(3600));
        LegalHold first = holdService.create(create("CASE-1", "REQ-1", 0L, "E1"));
        LegalHold replay = holdService.create(create("CASE-1", "REQ-1", 0L, "E1"));

        assertEquals(first.holdId(), replay.holdId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM legal_hold", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM legal_hold_item", Integer.class));
    }

    @Test
    void sameCreateRequestKeyDifferentEvidenceSetReturns409() {
        register("E1", "c1", T0.plusSeconds(3600));
        register("E2", "c2", T0.plusSeconds(3600));
        holdService.create(create("CASE-1", "REQ-1", null, "E1"));

        ApiException ex = assertThrows(ApiException.class,
                () -> holdService.create(create("CASE-1", "REQ-1", null, "E2")));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    void sameCreateRequestKeyDifferentCutoffVersionReturns409() {
        register("E1", "c1", T0.plusSeconds(3600));
        holdService.create(create("CASE-1", "REQ-1", null, "E1"));

        // 同集合同案件，但携带不同预期版本：指纹不同。
        ApiException ex = assertThrows(ApiException.class,
                () -> holdService.create(create("CASE-1", "REQ-1", 1L, "E1")));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    void releaseHoldTransitionsStateAndIncrementsVersion() {
        register("E1", "c1", T0.plusSeconds(3600));
        LegalHold hold = holdService.create(create("CASE-1", "REQ-1", null, "E1"));

        LegalHold released = holdService.release(
                new ReleaseHoldRequest(hold.holdId(), "REL-1", hold.version()));

        assertEquals("RELEASED", released.status().name());
        assertEquals(2L, released.version());
        assertEquals(2L, jdbc.queryForObject("SELECT hold_version FROM seq_holder WHERE id=0", Long.class));
        assertEquals(Instant.class, released.releasedAt().getClass());
    }

    @Test
    void releaseWithStaleVersionReturns409() {
        register("E1", "c1", T0.plusSeconds(3600));
        LegalHold hold = holdService.create(create("CASE-1", "REQ-1", null, "E1"));

        ApiException ex = assertThrows(ApiException.class,
                () -> holdService.release(new ReleaseHoldRequest(hold.holdId(), "REL-1", hold.version() + 1)));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("ACTIVE",
                jdbc.queryForObject("SELECT status FROM legal_hold WHERE id=?", String.class, hold.holdId()));
    }

    @Test
    void doubleReleaseReturns409() {
        register("E1", "c1", T0.plusSeconds(3600));
        LegalHold hold = holdService.create(create("CASE-1", "REQ-1", null, "E1"));
        holdService.release(new ReleaseHoldRequest(hold.holdId(), "REL-1", hold.version()));

        // 不同 requestId 的再次解除必须报 409（同一 requestId 则为幂等重放）。
        ApiException ex = assertThrows(ApiException.class,
                () -> holdService.release(new ReleaseHoldRequest(hold.holdId(), "REL-2", 2L)));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    void releaseReplayReturnsSameReleasedHold() {
        register("E1", "c1", T0.plusSeconds(3600));
        LegalHold hold = holdService.create(create("CASE-1", "REQ-1", null, "E1"));
        ReleaseHoldRequest req = new ReleaseHoldRequest(hold.holdId(), "REL-1", hold.version());
        LegalHold first = holdService.release(req);
        LegalHold replay = holdService.release(req);

        assertEquals(first.holdId(), replay.holdId());
        assertEquals(2L, replay.version());
        assertEquals("RELEASED", replay.status().name());
    }

    @Test
    void releaseUnknownHoldReturns404() {
        ApiException ex = assertThrows(ApiException.class,
                () -> holdService.release(new ReleaseHoldRequest(999L, "REL-X", 1L)));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }
}
