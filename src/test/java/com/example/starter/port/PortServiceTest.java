package com.example.starter.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.example.starter.TestTimeConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 携转流程主流程、资格边界、授权码失效、生效窗口与幂等规则的 H2 实测。
 */
@SpringBootTest
@Import(TestTimeConfig.class)
class PortServiceTest extends PortTestSupport {

    @Test
    void applyHappyPathCreatesAppliedFlowWithEvent() {
        String msisdn = nextMsisdn();
        insertEligibleSubscriber(msisdn);

        PortFlow flow = service.apply(nextRequestId(), msisdn, "OP_A", "OP_B");

        assertEquals(PortStatus.APPLIED, flow.status());
        assertEquals(1, countInflight(msisdn));
        List<PortEvent> events = service.listEvents(flow.id());
        assertEquals(List.of("APPLIED"), events.stream().map(PortEvent::eventType).toList());
    }

    @Test
    void applyRejectsArrears() {
        String msisdn = nextMsisdn();
        insertSubscriber(msisdn, "OP_A", new BigDecimal("0.01"), null, null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.apply(nextRequestId(), msisdn, "OP_A", "OP_B"));
        assertEquals("ELIGIBILITY_ARREARS", e.code());
        assertEquals(0, countFlows(msisdn));
    }

    @Test
    void applyRejectsActiveContract() {
        String msisdn = nextMsisdn();
        insertSubscriber(msisdn, "OP_A", BigDecimal.ZERO, LocalDate.of(2026, 9, 25), null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.apply(nextRequestId(), msisdn, "OP_A", "OP_B"));
        assertEquals("ELIGIBILITY_CONTRACT", e.code());
    }

    @Test
    void applyRejectsPortIntervalOf119Days() {
        String msisdn = nextMsisdn();
        Instant lastPort = TestTimeConfig.BASE_TIME.minus(Duration.ofDays(120)).plusSeconds(1);
        insertSubscriber(msisdn, "OP_A", BigDecimal.ZERO, null, lastPort);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.apply(nextRequestId(), msisdn, "OP_A", "OP_B"));
        assertEquals("ELIGIBILITY_INTERVAL", e.code());
    }

    @Test
    void applyAcceptsPortIntervalOfExactly120Days() {
        String msisdn = nextMsisdn();
        Instant lastPort = TestTimeConfig.BASE_TIME.minus(Duration.ofDays(120));
        insertSubscriber(msisdn, "OP_A", BigDecimal.ZERO, null, lastPort);

        PortFlow flow = service.apply(nextRequestId(), msisdn, "OP_A", "OP_B");

        assertEquals(PortStatus.APPLIED, flow.status());
    }

    @Test
    void applyRejectsDonorMismatchAndUnknownSubscriber() {
        String msisdn = nextMsisdn();
        insertEligibleSubscriber(msisdn);

        BusinessException mismatch = assertThrows(BusinessException.class,
                () -> service.apply(nextRequestId(), msisdn, "OP_X", "OP_B"));
        assertEquals("DONOR_MISMATCH", mismatch.code());

        BusinessException notFound = assertThrows(BusinessException.class,
                () -> service.apply(nextRequestId(), nextMsisdn(), "OP_A", "OP_B"));
        assertEquals("SUBSCRIBER_NOT_FOUND", notFound.code());
    }

    @Test
    void applyRejectsSecondInFlightFlowForSameNumber() {
        String msisdn = nextMsisdn();
        insertEligibleSubscriber(msisdn);
        service.apply(nextRequestId(), msisdn, "OP_A", "OP_B");

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.apply(nextRequestId(), msisdn, "OP_A", "OP_B"));
        assertEquals("IN_FLIGHT", e.code());
        assertEquals(1, countFlows(msisdn));
    }

    @Test
    void applyAllowsNewFlowAfterTerminalState() {
        String msisdn = nextMsisdn();
        insertEligibleSubscriber(msisdn);
        PortFlow first = service.apply(nextRequestId(), msisdn, "OP_A", "OP_B");
        service.cancel(first.id(), "用户反悔");

        PortFlow second = service.apply(nextRequestId(), msisdn, "OP_A", "OP_B");

        assertEquals(PortStatus.APPLIED, second.status());
        assertEquals(2, countFlows(msisdn));
        assertEquals(1, countInflight(msisdn));
    }

    @Test
    void applyIdempotentReplayReturnsFirstResult() {
        String msisdn = nextMsisdn();
        insertEligibleSubscriber(msisdn);
        String requestId = nextRequestId();

        PortFlow first = service.apply(requestId, msisdn, "OP_A", "OP_B");
        PortFlow replay = service.apply(requestId, msisdn, "OP_A", "OP_B");

        assertEquals(first.id(), replay.id());
        assertEquals(1, countFlows(msisdn));
    }

    @Test
    void applySameRequestIdWithDifferentParamsConflicts() {
        String msisdn = nextMsisdn();
        insertEligibleSubscriber(msisdn);
        String requestId = nextRequestId();
        PortFlow first = service.apply(requestId, msisdn, "OP_A", "OP_B");

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.apply(requestId, msisdn, "OP_A", "OP_C"));
        assertEquals("IDEMPOTENCY_CONFLICT", e.code());
        assertEquals("OP_B", service.getFlow(first.id()).recipientOperator());
        assertEquals(1, countFlows(msisdn));
    }

    @Test
    void authCodeIssuedWith24HourTtlAndReissueReturnsSameCode() {
        String msisdn = nextMsisdn();
        insertEligibleSubscriber(msisdn);
        PortFlow flow = service.apply(nextRequestId(), msisdn, "OP_A", "OP_B");

        PortFlow issued = service.issueAuthCode(flow.id());

        assertEquals(PortStatus.CODE_ISSUED, issued.status());
        assertNotNull(issued.authCode());
        assertEquals(TestTimeConfig.BASE_TIME, issued.authCodeIssuedAt());
        assertEquals(TestTimeConfig.BASE_TIME.plus(Duration.ofHours(24)), issued.authCodeExpiresAt());

        PortFlow reissued = service.issueAuthCode(flow.id());
        assertEquals(issued.authCode(), reissued.authCode());
    }

    @Test
    void confirmRejectsMismatchedAuthCode() {
        String msisdn = nextMsisdn();
        insertEligibleSubscriber(msisdn);
        PortFlow flow = service.apply(nextRequestId(), msisdn, "OP_A", "OP_B");
        service.issueAuthCode(flow.id());

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.confirm(flow.id(), "00000000"));
        assertEquals("AUTH_CODE_MISMATCH", e.code());
    }

    @Test
    void confirmSucceedsOneSecondBeforeExpiry() {
        String msisdn = nextMsisdn();
        insertEligibleSubscriber(msisdn);
        PortFlow flow = service.apply(nextRequestId(), msisdn, "OP_A", "OP_B");
        PortFlow issued = service.issueAuthCode(flow.id());

        clock.plus(Duration.ofHours(24).minusSeconds(1));
        PortFlow confirmed = service.confirm(flow.id(), issued.authCode());

        assertEquals(PortStatus.CONFIRMED, confirmed.status());
    }

    @Test
    void confirmFailsAtExactExpiryInstant() {
        String msisdn = nextMsisdn();
        insertEligibleSubscriber(msisdn);
        PortFlow flow = service.apply(nextRequestId(), msisdn, "OP_A", "OP_B");
        PortFlow issued = service.issueAuthCode(flow.id());

        clock.plus(Duration.ofHours(24));
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.confirm(flow.id(), issued.authCode()));
        assertEquals("AUTH_CODE_EXPIRED", e.code());
        assertEquals(PortStatus.CODE_ISSUED, service.getFlow(flow.id()).status());
    }

    @Test
    void broadcastOutsideWindowKeepsPendingState() {
        String msisdn = nextMsisdn();
        PortFlow flow = newConfirmedFlow(msisdn);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.broadcast(flow.id(), nextRequestId(), "CH1"));
        assertEquals("OUTSIDE_WINDOW", e.code());
        assertEquals(PortStatus.CONFIRMED, service.getFlow(flow.id()).status());
        assertEquals(0, countEffectiveness(flow.id()));
    }

    @Test
    void broadcastAtExactly0400IsOutsideWindow() {
        String msisdn = nextMsisdn();
        PortFlow flow = newConfirmedFlow(msisdn);
        clock.setInstant(Instant.parse("2026-09-26T04:00:00Z"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.broadcast(flow.id(), nextRequestId(), "CH1"));
        assertEquals("OUTSIDE_WINDOW", e.code());
        assertEquals(PortStatus.CONFIRMED, service.getFlow(flow.id()).status());
    }

    @Test
    void broadcastAt015959IsOutsideWindow() {
        String msisdn = nextMsisdn();
        PortFlow flow = newConfirmedFlow(msisdn);
        clock.setInstant(Instant.parse("2026-09-26T01:59:59Z"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.broadcast(flow.id(), nextRequestId(), "CH1"));
        assertEquals("OUTSIDE_WINDOW", e.code());
    }

    @Test
    void broadcastAt0200SucceedsAndVoidsAuthCode() {
        String msisdn = nextMsisdn();
        PortFlow flow = newConfirmedFlow(msisdn);
        clock.setInstant(Instant.parse("2026-09-26T02:00:00Z"));

        EffectivenessRecord record = service.broadcast(flow.id(), nextRequestId(), "CH1");

        assertEquals(flow.id(), record.portRequestId());
        assertEquals(Instant.parse("2026-09-26T02:00:00Z"), record.effectiveAt());
        PortFlow effective = service.getFlow(flow.id());
        assertEquals(PortStatus.EFFECTIVE, effective.status());
        assertNull(effective.authCode());
        assertEquals(0, countInflight(msisdn));
        assertEquals(
                List.of("APPLIED", "AUTH_CODE_ISSUED", "CONFIRMED", "EFFECTIVE"),
                service.listEvents(flow.id()).stream().map(PortEvent::eventType).toList());
    }

    @Test
    void broadcastIdempotentReplayAndFlowLevelUniqueness() {
        String msisdn = nextMsisdn();
        PortFlow flow = newConfirmedFlow(msisdn);
        clock.setInstant(Instant.parse("2026-09-26T03:00:00Z"));
        String requestId = nextRequestId();

        EffectivenessRecord first = service.broadcast(flow.id(), requestId, "CH1");
        // 同键同参重放：返回首次结果
        EffectivenessRecord replay = service.broadcast(flow.id(), requestId, "CH1");
        // 同流程不同 requestId 重复广播：仍只产生一条生效记录
        EffectivenessRecord otherKey = service.broadcast(flow.id(), nextRequestId(), "CH1");

        assertEquals(first.id(), replay.id());
        assertEquals(first.id(), otherKey.id());
        assertEquals(1, countEffectiveness(flow.id()));
    }

    @Test
    void broadcastSameRequestIdWithDifferentChannelConflicts() {
        String msisdn = nextMsisdn();
        PortFlow flow = newConfirmedFlow(msisdn);
        clock.setInstant(Instant.parse("2026-09-26T03:00:00Z"));
        String requestId = nextRequestId();
        service.broadcast(flow.id(), requestId, "CH1");

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.broadcast(flow.id(), requestId, "CH2"));
        assertEquals("IDEMPOTENCY_CONFLICT", e.code());
        assertEquals(1, countEffectiveness(flow.id()));
    }

    @Test
    void lateConfirmAfterEffectiveIsRejected() {
        String msisdn = nextMsisdn();
        PortFlow flow = newConfirmedFlow(msisdn);
        String authCode = jdbc.queryForObject(
                "SELECT auth_code FROM port_request WHERE id = ?", String.class, flow.id());
        clock.setInstant(Instant.parse("2026-09-26T03:00:00Z"));
        service.broadcast(flow.id(), nextRequestId(), "CH1");

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.confirm(flow.id(), authCode));
        assertEquals("CONFIRM_TOO_LATE", e.code());
        assertEquals(PortStatus.EFFECTIVE, service.getFlow(flow.id()).status());
    }

    @Test
    void cancelBeforeEffectiveTerminatesFlow() {
        String msisdn = nextMsisdn();
        PortFlow flow = newConfirmedFlow(msisdn);

        PortFlow cancelled = service.cancel(flow.id(), "用户反悔");

        assertEquals(PortStatus.CANCELLED, cancelled.status());
        assertNotNull(cancelled.cancelledAt());
        assertEquals(0, countInflight(msisdn));
        // 撤销后广播不再生效
        clock.setInstant(Instant.parse("2026-09-26T03:00:00Z"));
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.broadcast(flow.id(), nextRequestId(), "CH1"));
        assertEquals("INVALID_STATE", e.code());
        assertEquals(PortStatus.CANCELLED, service.getFlow(flow.id()).status());
        assertEquals(0, countEffectiveness(flow.id()));
    }

    @Test
    void cancelAfterEffectiveIsRejected() {
        String msisdn = nextMsisdn();
        PortFlow flow = newConfirmedFlow(msisdn);
        clock.setInstant(Instant.parse("2026-09-26T03:00:00Z"));
        service.broadcast(flow.id(), nextRequestId(), "CH1");

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.cancel(flow.id(), "太迟了"));
        assertEquals("ALREADY_EFFECTIVE", e.code());
        assertEquals(PortStatus.EFFECTIVE, service.getFlow(flow.id()).status());
    }

    @Test
    void windowBoundaryCheck() {
        assertTrue(service.inWindow(Instant.parse("2026-09-25T02:00:00Z")));
        assertTrue(service.inWindow(Instant.parse("2026-09-25T03:59:59Z")));
        org.junit.jupiter.api.Assertions.assertFalse(
                service.inWindow(Instant.parse("2026-09-25T04:00:00Z")));
        org.junit.jupiter.api.Assertions.assertFalse(
                service.inWindow(Instant.parse("2026-09-25T01:59:59Z")));
    }
}
