package com.example.starter.roaming;

import com.example.starter.roaming.api.AuthorizeRequest;
import com.example.starter.roaming.api.AuthorizeResponse;
import com.example.starter.roaming.error.ApiException;
import com.example.starter.roaming.service.AuthorizationService;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 即插即充授权测试：5 分钟有效期边界、同车同刻唯一会话、requestId 幂等与冲突、失败分支。
 */
class AuthorizationTest extends AbstractRoamingTest {

    @Autowired
    private AuthorizationService authorizationService;

    private AuthorizeRequest req(String requestId, String fp, String pile, Instant t) {
        return new AuthorizeRequest(requestId, fp, pile, t);
    }

    @Test
    void firstAuthorizationCreatesSessionValidForFiveMinutes() {
        Instant t = clock.instant();
        AuthorizeResponse resp = authorizationService.authorize(req("A1", "FP-CAR-0001", "PILE-B-001", t));

        assertEquals("AUTHORIZED", resp.status());
        assertFalse(resp.replayed());
        assertEquals(t, resp.startTime());
        assertEquals(t.plus(Duration.ofMinutes(5)), resp.expiresAt());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM charging_session", Integer.class));
    }

    @Test
    void repeatedAuthorizationWithinFiveMinutesReturnsFirstResult() {
        Instant t = clock.instant();
        AuthorizeResponse first = authorizationService.authorize(req("A1", "FP-CAR-0001", "PILE-B-001", t));

        clock.advance(Duration.ofMinutes(4).plusSeconds(59));
        AuthorizeResponse again = authorizationService.authorize(req("A2", "FP-CAR-0001", "PILE-B-001", t));

        assertTrue(again.replayed());
        assertEquals(first.sessionNo(), again.sessionNo());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM charging_session", Integer.class));
    }

    @Test
    void authorizationExactlyAtFiveMinuteBoundaryStillReturnsFirstResult() {
        Instant t = clock.instant();
        AuthorizeResponse first = authorizationService.authorize(req("A1", "FP-CAR-0001", "PILE-B-001", t));

        clock.advance(Duration.ofMinutes(5));
        // 边界：当前时刻不晚于 授权时刻+5min，仍属有效期（isAfter 判断）。
        AuthorizeResponse boundary = authorizationService.authorize(req("A2", "FP-CAR-0001", "PILE-B-001", t));
        assertEquals(first.sessionNo(), boundary.sessionNo());
        assertTrue(boundary.replayed());
    }

    @Test
    void authorizationAfterFiveMinutesMustReauthorizeAtNewTime() {
        Instant t = clock.instant();
        authorizationService.authorize(req("A1", "FP-CAR-0001", "PILE-B-001", t));

        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        ApiException ex = assertThrows(ApiException.class,
                () -> authorizationService.authorize(req("A2", "FP-CAR-0001", "PILE-B-001", t)));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());

        // 以新的请求时刻重新授权成功，产生第二个会话。
        Instant t2 = clock.instant();
        AuthorizeResponse reauth = authorizationService.authorize(req("A3", "FP-CAR-0001", "PILE-B-001", t2));
        assertFalse(reauth.replayed());
        assertNotEquals(t, reauth.startTime());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM charging_session", Integer.class));
    }

    @Test
    void sameRequestIdSameParamsReturnsFirstResult() {
        Instant t = clock.instant();
        AuthorizeRequest r = req("SAME-ID", "FP-CAR-0001", "PILE-B-001", t);
        AuthorizeResponse first = authorizationService.authorize(r);
        AuthorizeResponse second = authorizationService.authorize(r);

        assertTrue(second.replayed());
        assertEquals(first.sessionNo(), second.sessionNo());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM charging_session", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM idempotent_request", Integer.class));
    }

    @Test
    void sameRequestIdDifferentParamsReturns409AndCreatesNothing() {
        Instant t = clock.instant();
        authorizationService.authorize(req("DUP-ID", "FP-CAR-0001", "PILE-B-001", t));

        ApiException ex = assertThrows(ApiException.class,
                () -> authorizationService.authorize(req("DUP-ID", "FP-CAR-0002", "PILE-B-001", t)));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        // 同键异参不产生新会话。
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM charging_session", Integer.class));
    }

    @Test
    void disabledVehicleIsRejected() {
        Instant t = clock.instant();
        ApiException ex = assertThrows(ApiException.class,
                () -> authorizationService.authorize(req("X1", "FP-CAR-DISABLED", "PILE-A-001", t)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM charging_session", Integer.class));
        // 失败不占用 requestId（事务回滚），同键随后可成功。
        AuthorizeResponse ok = authorizationService.authorize(req("X1", "FP-CAR-0001", "PILE-A-001", t));
        assertFalse(ok.replayed());
    }

    @Test
    void unknownVehicleAndPileAre404() {
        Instant t = clock.instant();
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class,
                () -> authorizationService.authorize(req("N1", "NO-CAR", "PILE-A-001", t))).getStatus());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class,
                () -> authorizationService.authorize(req("N2", "FP-CAR-0001", "NO-PILE", t))).getStatus());
    }
}
