package com.example.starter.torque;

import com.example.starter.torque.dto.ReleaseResult;
import com.example.starter.torque.dto.TighteningResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.List;

import static com.example.starter.torque.TorqueServiceIntegrationTest.assertApiError;
import static com.example.starter.torque.TestRequests.absent;
import static com.example.starter.torque.TestRequests.dispatch;
import static com.example.starter.torque.TestRequests.mutual;
import static com.example.starter.torque.TestRequests.present;
import static com.example.starter.torque.TestRequests.release;
import static com.example.starter.torque.TestRequests.self;
import static com.example.starter.torque.TestRequests.spec;
import static com.example.starter.torque.TestRequests.tighten;
import static com.example.starter.torque.TestRequests.tool;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * requestId 幂等：同键同参返回首次结果；同键异参 409 且不改变工序状态。
 * 放行归档终态：重复归档幂等成功；归档后迟到测量只记迟到事件。
 */
class IdempotencyAndReleaseTest extends AbstractIntegrationTest {

    private String prepareCoverableCard(String cardNo, String station) {
        service.registerTool(tool("TW-01", LocalDate.parse("2026-09-26")));
        service.dispatch(dispatch("d-" + cardNo, cardNo, station, List.of(spec("P1", "100", true))));
        service.tighten(cardNo, tighten("t-" + cardNo, "P1", "100", "TW-01", "op1"));
        service.inspect(cardNo, self("s-" + cardNo, "alice"));
        service.inspect(cardNo, mutual("m-" + cardNo, "bob"));
        service.witness(cardNo, present("w-" + cardNo, "P1", "inspector-x"));
        assertEquals(CardStatus.COVERING.name(), service.getCard(cardNo).status());
        return cardNo;
    }

    @Test
    void sameKeySameParamsReturnsFirstResult() {
        service.registerTool(tool("TW-01", LocalDate.parse("2026-09-26")));
        service.dispatch(dispatch("d1", "C-IDEM", "S1", List.of(spec("P1", "100", false))));

        TighteningResult first = service.tighten("C-IDEM", tighten("same-key", "P1", "100", "TW-01", "op1"));
        TighteningResult replay = service.tighten("C-IDEM", tighten("same-key", "P1", "100", "TW-01", "op1"));
        assertEquals(first.recordedAt(), replay.recordedAt());
        assertEquals(first.attemptNo(), replay.attemptNo());
        assertEquals(1, service.getTighteningChain("C-IDEM").size(), "重放不得追加记录");
    }

    @Test
    void sameKeyDifferentParamsConflictsWithoutStateChange() {
        service.registerTool(tool("TW-01", LocalDate.parse("2026-09-26")));
        service.dispatch(dispatch("d2", "C-CONF", "S2", List.of(spec("P1", "100", false))));
        service.tighten("C-CONF", tighten("k1", "P1", "100", "TW-01", "op1"));
        assertEquals(CardStatus.SELF_CHECK.name(), service.getCard("C-CONF").status());

        // 相同 requestId，不同业务参数（实测力矩）→ 409，且不产生第二条测量、状态不变。
        assertApiError(() -> service.tighten("C-CONF", tighten("k1", "P1", "90", "TW-01", "op1")),
                HttpStatus.CONFLICT, ErrorCodes.IDEMPOTENCY_CONFLICT);
        assertEquals(1, service.getTighteningChain("C-CONF").size());
        assertEquals(CardStatus.SELF_CHECK.name(), service.getCard("C-CONF").status());
        assertEquals(0, service.getReworkLedger("C-CONF").size());
    }

    @Test
    void sameKeyDifferentCardConflicts() {
        service.registerTool(tool("TW-01", LocalDate.parse("2026-09-26")));
        service.dispatch(dispatch("d3a", "C-A", "S3", List.of(spec("P1", "100", false))));
        service.dispatch(dispatch("d3b", "C-B", "S4", List.of(spec("P1", "100", false))));
        service.tighten("C-A", tighten("shared", "P1", "100", "TW-01", "op1"));
        assertApiError(() -> service.tighten("C-B", tighten("shared", "P1", "100", "TW-01", "op1")),
                HttpStatus.CONFLICT, ErrorCodes.IDEMPOTENCY_CONFLICT);
        assertEquals(0, service.getTighteningChain("C-B").size(), "异卡同键不得在 C-B 落记录");
    }

    @Test
    void releaseIsTerminalAndRepeatedReleaseIsIdempotent() {
        prepareCoverableCard("C-REL", "S5");
        ReleaseResult first = service.release("C-REL", release("rel-1"));
        assertEquals(CardStatus.RELEASED.name(), first.status());
        assertFalse(first.idempotentReplay());
        assertNotNull(first.releasedAt());

        ReleaseResult again = service.release("C-REL", release("rel-1"));
        assertTrue(again.idempotentReplay());
        assertEquals(first.releasedAt(), again.releasedAt(), "重复归档不得改变归档时间");
        assertEquals(CardStatus.RELEASED.name(), service.getCard("C-REL").status());
    }

    @Test
    void lateMeasurementAfterReleaseOnlyRecordsLateEvent() {
        prepareCoverableCard("C-LATE", "S6");
        service.release("C-LATE", release("rel-late"));
        advanceSeconds(3600);

        TighteningResult late = service.tighten("C-LATE",
                tighten("late-1", "P1", "90", "TW-01", "op1"));
        assertTrue(late.late());
        assertEquals(CardStatus.RELEASED.name(), late.cardStatus(), "迟到测量不得改变终态");
        assertEquals(CardStatus.RELEASED.name(), service.getCard("C-LATE").status());
        assertEquals(1, service.getLateMeasurements("C-LATE").size());

        // 正式紧固链不含迟到测量；复算视图将其追加在链尾并标 late。
        long formalCount = service.getTighteningChain("C-LATE").stream()
                .filter(r -> !r.late()).count();
        assertEquals(1, formalCount);
        assertTrue(service.getTighteningChain("C-LATE").stream().anyMatch(r -> r.late()));

        // 迟到测量幂等：同键重放不重复记迟到事件。
        service.tighten("C-LATE", tighten("late-1", "P1", "90", "TW-01", "op1"));
        assertEquals(1, service.getLateMeasurements("C-LATE").size());
    }

    @Test
    void witnessIdempotencyReplaysFirstOutcome() {
        service.registerTool(tool("TW-01", LocalDate.parse("2026-09-26")));
        // 准备一张处于见证待办的卡。
        service.dispatch(dispatch("d-w", "C-W2", "S8", List.of(spec("H1", "100", true))));
        service.tighten("C-W2", tighten("t-w", "H1", "100", "TW-01", "op1"));
        service.inspect("C-W2", self("s-w", "alice"));
        service.inspect("C-W2", mutual("m-w", "bob"));

        service.witness("C-W2", absent("wa", "H1"));
        assertEquals(CardStatus.WAITING.name(), service.getCard("C-W2").status());
        // 同键同参重放缺席：返回首次 ABSENT，不重复插入事件。
        service.witness("C-W2", absent("wa", "H1"));
        long absentEvents = service.getWitnessChain("C-W2").stream()
                .filter(e -> "ABSENT".equals(e.eventType()) && "H1".equals(e.pointCode())).count();
        assertEquals(1, absentEvents);
    }
}
