package com.example.starter.torque;

import com.example.starter.torque.dto.CardView;
import com.example.starter.torque.dto.TighteningResult;
import com.example.starter.torque.dto.ToolView;
import com.example.starter.torque.dto.WitnessResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.List;

import static com.example.starter.torque.TestRequests.absent;
import static com.example.starter.torque.TestRequests.dispatch;
import static com.example.starter.torque.TestRequests.mutual;
import static com.example.starter.torque.TestRequests.present;
import static com.example.starter.torque.TestRequests.self;
import static com.example.starter.torque.TestRequests.spec;
import static com.example.starter.torque.TestRequests.tighten;
import static com.example.starter.torque.TestRequests.tool;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 力矩紧固业务主流程与失败分支（真实 H2 事务，无 mock）。
 */
class TorqueServiceIntegrationTest extends AbstractIntegrationTest {

    private void registerValidTool() {
        ToolView view = service.registerTool(tool("TW-01", LocalDate.parse("2026-09-26")));
        assertTrue(view.validToday());
    }

    /** 恰 ±5% 边界合格：95.000 与 105.000 均判定 within，且完成后进入自检。 */
    @Test
    void torqueExactlyAtFivePercentBoundariesPasses() {
        registerValidTool();
        service.dispatch(dispatch("d-low", "C-LOW", "S1", List.of(spec("P1", "100", false))));
        TighteningResult low = service.tighten("C-LOW", tighten("t-low", "P1", "95.000", "TW-01", "op1"));
        assertTrue(low.withinTolerance());
        assertFalse(low.reworkRequired());
        assertEquals(CardStatus.SELF_CHECK.name(), low.cardStatus());
        assertEquals(0, low.lowerBound().compareTo(new java.math.BigDecimal("95.000")));

        service.dispatch(dispatch("d-high", "C-HIGH", "S2", List.of(spec("P1", "100", false))));
        TighteningResult high = service.tighten("C-HIGH", tighten("t-high", "P1", "105.000", "TW-01", "op1"));
        assertTrue(high.withinTolerance());
        assertEquals(CardStatus.SELF_CHECK.name(), high.cardStatus());
    }

    /** 超差返工，恰 2 次返工合法：第 3 次测量合格可继续流转。 */
    @Test
    void exactlyTwoReworksAreAllowedAndThirdAttemptCanPass() {
        registerValidTool();
        service.dispatch(dispatch("d1", "C-RW", "S3", List.of(spec("P1", "100", false))));

        TighteningResult a1 = service.tighten("C-RW", tighten("r1", "P1", "90", "TW-01", "op1"));
        assertFalse(a1.withinTolerance());
        assertTrue(a1.reworkRequired());
        assertEquals(1, a1.attemptNo());

        TighteningResult a2 = service.tighten("C-RW", tighten("r2", "P1", "90", "TW-01", "op1"));
        assertTrue(a2.reworkRequired());
        assertFalse(a2.quarantined());
        assertEquals(2, a2.attemptNo());
        assertEquals(2, service.getReworkLedger("C-RW").size(), "恰 2 次返工合法，工序仍可继续");

        TighteningResult a3 = service.tighten("C-RW", tighten("r3", "P1", "100", "TW-01", "op1"));
        assertTrue(a3.withinTolerance());
        assertEquals(3, a3.attemptNo());
        assertEquals(CardStatus.SELF_CHECK.name(), a3.cardStatus());

        // 原始记录不被覆盖：链上 3 条测量 + 2 条返工台账。
        assertEquals(3, service.getTighteningChain("C-RW").size());
        assertEquals(2, service.getReworkLedger("C-RW").size());
        assertEquals(2, service.getCard("C-RW").currentRework());
    }

    /** 返工 2 次后仍超差：第 3 次超差测量触发整批隔离评审终态。 */
    @Test
    void thirdOutOfToleranceAfterTwoReworksQuarantinesBatch() {
        registerValidTool();
        service.dispatch(dispatch("d2", "C-Q", "S4", List.of(spec("P1", "100", false))));

        service.tighten("C-Q", tighten("q1", "P1", "90", "TW-01", "op1"));
        service.tighten("C-Q", tighten("q2", "P1", "90", "TW-01", "op1"));
        TighteningResult q3 = service.tighten("C-Q", tighten("q3", "P1", "90", "TW-01", "op1"));

        assertTrue(q3.quarantined());
        assertEquals(CardStatus.QUARANTINED.name(), q3.cardStatus());
        CardView card = service.getCard("C-Q");
        assertEquals(CardStatus.QUARANTINED.name(), card.status());
        assertEquals(2, service.getReworkLedger("C-Q").size(), "返工台账恰 2 条");
        assertEquals(3, service.getTighteningChain("C-Q").size(), "原始测量链 3 条只追加");

        // 隔离为终态：继续紧固被拒；工位已释放，可派新卡。
        ApiException ex = assertApiError(
                () -> service.tighten("C-Q", tighten("q4", "P1", "100", "TW-01", "op1")),
                HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodes.ILLEGAL_STATE);
        assertTrue(ex.getMessage().contains("隔离"));
        service.dispatch(dispatch("d2b", "C-Q2", "S4", List.of(spec("P1", "100", false))));
        assertEquals(CardStatus.DISPATCHED.name(), service.getCard("C-Q2").status());
    }

    /** 工具日校验当日（UTC 日界）有效；逾期或未登记直接拒绝，且不写入测量记录。 */
    @Test
    void expiredOrUnknownToolIsRejected() {
        service.registerTool(tool("TW-OLD", LocalDate.parse("2026-09-25")));
        service.dispatch(dispatch("d3", "C-T", "S5", List.of(spec("P1", "100", false))));

        assertApiError(() -> service.tighten("C-T", tighten("t-old", "P1", "100", "TW-OLD", "op1")),
                HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodes.TOOL_EXPIRED);
        assertApiError(() -> service.tighten("C-T", tighten("t-x", "P1", "100", "TW-UNKNOWN", "op1")),
                HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodes.TOOL_EXPIRED);
        assertEquals(0, service.getTighteningChain("C-T").size(), "被拒测量不得落链");

        // 当日补校验后可用。
        service.registerTool(tool("TW-OLD", LocalDate.parse("2026-09-26")));
        TighteningResult ok = service.tighten("C-T", tighten("t-ok", "P1", "100", "TW-OLD", "op1"));
        assertTrue(ok.withinTolerance());
    }

    /** 自检与互检须不同人员：同一人双签拒绝。 */
    @Test
    void selfAndMutualInspectorMustDiffer() {
        registerValidTool();
        service.dispatch(dispatch("d4", "C-I", "S6", List.of(spec("P1", "100", false))));
        service.tighten("C-I", tighten("ti", "P1", "100", "TW-01", "op1"));

        service.inspect("C-I", self("s1", "alice"));
        assertApiError(() -> service.inspect("C-I", mutual("m1", "alice")),
                HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodes.SAME_INSPECTOR);
        assertEquals(CardStatus.MUTUAL_CHECK.name(), service.getCard("C-I").status(),
                "同一人双签拒绝后状态不变");

        var mutualResult = service.inspect("C-I", mutual("m2", "bob"));
        assertEquals(CardStatus.COVERING.name(), mutualResult.cardStatus(), "无 H 点互检后可进入覆盖工序");
    }

    /** H 点缺席转等待，恰满 2 小时升级；升级后见证到场方可覆盖。 */
    @Test
    void witnessAbsenceWaitsAndEscalatesAfterExactlyTwoHours() {
        prepareCardAtWitnessPending("C-W", "S7");

        WitnessResult absent1 = service.witness("C-W", absent("w1", "P1"));
        assertEquals("ABSENT", absent1.eventType());
        assertEquals(CardStatus.WAITING.name(), absent1.cardStatus());
        assertEquals(Instant_parse("2026-09-26T10:00:00Z"), absent1.waitUntil());

        // 未满 2 小时不升级。
        advanceSeconds(7199);
        WitnessResult stillWaiting = service.witness("C-W", absent("w2", "P1"));
        assertEquals("ABSENT", stillWaiting.eventType());
        assertFalse(stillWaiting.escalated());
        assertEquals(CardStatus.WAITING.name(), service.getCard("C-W").status());

        // 恰满 2 小时判定升级。
        advanceSeconds(1);
        WitnessResult escalated = service.witness("C-W", absent("w3", "P1"));
        assertTrue(escalated.escalated());
        assertEquals("ESCALATED", escalated.eventType());
        assertEquals(CardStatus.ESCALATED.name(), service.getCard("C-W").status());

        // 升级事件不重复：再次查询仍只一条 ESCALATED。
        service.witness("C-W", absent("w4", "P1"));
        long escalatedEvents = service.getWitnessChain("C-W").stream()
                .filter(e -> "ESCALATED".equals(e.eventType())).count();
        assertEquals(1, escalatedEvents);

        // 升级后见证人到场：H 点见证完成才进入覆盖工序。
        WitnessResult witnessed = service.witness("C-W", present("w5", "P1", "inspector-w"));
        assertEquals("WITNESSED", witnessed.eventType());
        assertEquals(CardStatus.COVERING.name(), witnessed.cardStatus());
    }

    /** H 点未见证不得进入覆盖工序。 */
    @Test
    void releaseRejectedBeforeWitnessCompleted() {
        prepareCardAtWitnessPending("C-H", "S8");
        assertApiError(() -> service.release("C-H", TestRequests.release("rel-x")),
                HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodes.ILLEGAL_STATE);
    }

    private void prepareCardAtWitnessPending(String cardNo, String station) {
        registerValidTool();
        service.dispatch(dispatch("d-" + cardNo, cardNo, station, List.of(spec("P1", "100", true))));
        service.tighten(cardNo, tighten("t-" + cardNo, "P1", "100", "TW-01", "op1"));
        service.inspect(cardNo, self("s-" + cardNo, "alice"));
        service.inspect(cardNo, mutual("m-" + cardNo, "bob"));
        assertEquals(CardStatus.WITNESS_PENDING.name(), service.getCard(cardNo).status());
    }

    private static java.time.Instant Instant_parse(String text) {
        return java.time.Instant.parse(text);
    }

    static ApiException assertApiError(ThrowingAction action, HttpStatus status, String code) {
        try {
            action.run();
        } catch (ApiException e) {
            assertEquals(status, e.getStatus());
            assertEquals(code, e.getCode());
            return e;
        }
        throw new AssertionError("期望 ApiException: " + status + " " + code);
    }

    @FunctionalInterface
    interface ThrowingAction {
        void run();
    }
}
