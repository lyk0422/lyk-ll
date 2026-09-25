package com.example.starter.torque;

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

/**
 * 多 H 点见证状态合成与归档后工位释放等补充边界。
 */
class MultiHPointAndStationTest extends AbstractIntegrationTest {

    private void prepareTwoHPointCard(String cardNo, String station) {
        service.registerTool(tool("TW-01", LocalDate.parse("2026-09-26")));
        service.dispatch(dispatch("d-" + cardNo, cardNo, station,
                List.of(spec("H1", "100", true), spec("H2", "100", true))));
        service.tighten(cardNo, tighten("t1-" + cardNo, "H1", "100", "TW-01", "op1"));
        service.tighten(cardNo, tighten("t2-" + cardNo, "H2", "100", "TW-01", "op1"));
        service.inspect(cardNo, self("s-" + cardNo, "alice"));
        service.inspect(cardNo, mutual("m-" + cardNo, "bob"));
        assertEquals(CardStatus.WITNESS_PENDING.name(), service.getCard(cardNo).status());
    }

    @Test
    void oneHPointEscalatedKeepsCardFromCoveringUntilAllWitnessed() {
        prepareTwoHPointCard("C-MH", "WS-1");

        // H1 缺席。
        service.witness("C-MH", absent("wa1", "H1"));
        assertEquals(CardStatus.WAITING.name(), service.getCard("C-MH").status());

        // H2 正常见证：因 H1 仍等待，卡不能进入覆盖工序。
        var w2 = service.witness("C-MH", present("wp2", "H2", "carol"));
        assertEquals(CardStatus.WAITING.name(), w2.cardStatus());

        // H1 恰满 2 小时升级。
        advanceSeconds(7200);
        var escalated = service.witness("C-MH", absent("wa2", "H1"));
        assertEquals(CardStatus.ESCALATED.name(), escalated.cardStatus());

        // H2 已见证但 H1 仅升级未见证：仍不能放行。
        assertApiError(() -> service.release("C-MH", release("rel-mh")),
                HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodes.ILLEGAL_STATE);

        // H1 升级后到场补见证：全部 H 点见证完成，方可覆盖与归档。
        var w1 = service.witness("C-MH", present("wp1", "H1", "dave"));
        assertEquals(CardStatus.COVERING.name(), w1.cardStatus());
        var rel = service.release("C-MH", release("rel-mh"));
        assertEquals(CardStatus.RELEASED.name(), rel.status());
    }

    @Test
    void stationIsReleasedAfterReleaseAndQuarantine() {
        prepareTwoHPointCard("C-R1", "WS-2");
        service.witness("C-R1", present("w-h1", "H1", "carol"));
        service.witness("C-R1", present("w-h2", "H2", "dave"));
        service.release("C-R1", release("rel-r1"));

        // 归档后工位释放：同工位可派新卡。
        service.dispatch(dispatch("d-r2", "C-R2", "WS-2", List.of(spec("P1", "100", false))));
        assertEquals(CardStatus.DISPATCHED.name(), service.getCard("C-R2").status());

        // 隔离同样释放工位。
        service.dispatch(dispatch("d-r3", "C-R3", "WS-3", List.of(spec("P1", "100", false))));
        service.tighten("C-R3", tighten("q1", "P1", "90", "TW-01", "op1"));
        service.tighten("C-R3", tighten("q2", "P1", "90", "TW-01", "op1"));
        service.tighten("C-R3", tighten("q3", "P1", "90", "TW-01", "op1"));
        assertEquals(CardStatus.QUARANTINED.name(), service.getCard("C-R3").status());
        service.dispatch(dispatch("d-r4", "C-R4", "WS-3", List.of(spec("P1", "100", false))));
        assertEquals(CardStatus.DISPATCHED.name(), service.getCard("C-R4").status());
    }
}
