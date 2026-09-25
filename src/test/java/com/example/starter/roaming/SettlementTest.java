package com.example.starter.roaming;

import com.example.starter.roaming.api.AuthorizeRequest;
import com.example.starter.roaming.api.AuthorizeResponse;
import com.example.starter.roaming.api.EndSessionRequest;
import com.example.starter.roaming.api.MeterSnapshotRequest;
import com.example.starter.roaming.api.SealBatchRequest;
import com.example.starter.roaming.api.SessionResponse;
import com.example.starter.roaming.api.StartSessionRequest;
import com.example.starter.roaming.api.SettlementBatchResponse;
import com.example.starter.roaming.api.WriteOffRequest;
import com.example.starter.roaming.api.WriteOffResponse;
import com.example.starter.roaming.error.ApiException;
import com.example.starter.roaming.repo.SettlementRepository.SuspenseRow;
import com.example.starter.roaming.service.AuthorizationService;
import com.example.starter.roaming.service.ChargingSessionService;
import com.example.starter.roaming.service.SettlementService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨网清分测试：按归属运营商费率计算应收应付、批内合计等于明细之和、
 * 封存终态、封存后迟到快照挂账、挂账唯一核销与留痕。
 */
class SettlementTest extends AbstractRoamingTest {

    @Autowired
    private AuthorizationService authorizationService;
    @Autowired
    private ChargingSessionService sessionService;
    @Autowired
    private SettlementService settlementService;

    private static final LocalDate DAY = LocalDate.parse("2026-09-20");

    private MeterSnapshotRequest meter(String id, int seq, String energy, Instant at) {
        return new MeterSnapshotRequest(id, seq, new BigDecimal(energy), at);
    }

    /**
     * 构造两个跨运营商会话：
     * S1 车属OP-A(应收1.2) 在OP-B桩(应付0.9)，电量 10kWh；
     * S2 车属OP-B(应收1.3) 在OP-A桩(应付0.8)，电量 20kWh。
     */
    private String[] twoEndedCrossOperatorSessions() {
        Instant t = clock.instant();

        AuthorizeResponse auth = authorizationService.authorize(
                new AuthorizeRequest("A1", "FP-CAR-0001", "PILE-B-001", t));
        sessionService.start(new StartSessionRequest("ST1", auth.sessionNo(), null, null, null));
        sessionService.meter(auth.sessionNo(), meter("M11", 0, "10.000", t.plusSeconds(30)));
        SessionResponse e1 = sessionService.end(auth.sessionNo(),
                new EndSessionRequest("E1", t.plus(Duration.ofMinutes(20))));
        assertEquals("ENDED", e1.status());

        sessionService.start(new StartSessionRequest("ST2", null, "FP-CAR-0002", "PILE-A-001",
                t.plusSeconds(5)));
        sessionService.meter(findOnlySessionNo("FP-CAR-0002"),
                meter("M21", 0, "20.000", t.plusSeconds(40)));
        SessionResponse e2 = sessionService.end(findOnlySessionNo("FP-CAR-0002"),
                new EndSessionRequest("E2", t.plus(Duration.ofMinutes(25))));
        assertEquals("ENDED", e2.status());

        return new String[]{auth.sessionNo(), findOnlySessionNo("FP-CAR-0002")};
    }

    private String findOnlySessionNo(String fingerprint) {
        return jdbc.queryForObject("SELECT session_no FROM charging_session WHERE cert_fingerprint = ?",
                String.class, fingerprint);
    }

    private SettlementBatchResponse.Detail detailOf(SettlementBatchResponse batch, String sessionNo) {
        return batch.details().stream()
                .filter(d -> sessionNo.equals(d.sessionNo()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("清分明细缺少会话：" + sessionNo));
    }

    @Test
    void sealComputesTariffAmountsAndBatchTotalsEqualDetailSums() {
        String[] sessions = twoEndedCrossOperatorSessions();

        SettlementBatchResponse batch = settlementService.seal(new SealBatchRequest("B1", DAY));

        assertEquals("SEALED", batch.status());
        assertEquals(2, batch.sessionCount());
        assertEquals(0, new BigDecimal("30.000").compareTo(batch.totalEnergyKwh()));
        // 应收：10*1.2 + 20*1.3 = 38.00；应付：10*0.9 + 20*0.8 = 25.00。
        assertEquals(0, new BigDecimal("38.00").compareTo(batch.totalReceivable()));
        assertEquals(0, new BigDecimal("25.00").compareTo(batch.totalPayable()));

        // 明细按各会话归属/服务运营商费率分别计算。
        SettlementBatchResponse.Detail d1 = detailOf(batch, sessions[0]);
        assertEquals(0, new BigDecimal("10.000").compareTo(d1.energyKwh()));
        assertEquals(0, new BigDecimal("12.00").compareTo(d1.receivableAmount()));
        assertEquals(0, new BigDecimal("9.00").compareTo(d1.payableAmount()));
        assertEquals(1L, d1.homeOperatorId());
        assertEquals(2L, d1.servingOperatorId());

        SettlementBatchResponse.Detail d2 = detailOf(batch, sessions[1]);
        assertEquals(0, new BigDecimal("26.00").compareTo(d2.receivableAmount()));
        assertEquals(0, new BigDecimal("16.00").compareTo(d2.payableAmount()));

        // 会话唯一归属该批次。
        for (String no : sessions) {
            Long batchId = jdbc.queryForObject("SELECT batch_id FROM charging_session WHERE session_no = ?",
                    Long.class, no);
            assertEquals(batch.batchId(), batchId);
        }
    }

    @Test
    void sealIsTerminalAndRepeatedSealReturnsFirstBatchWithoutRecalculation() {
        twoEndedCrossOperatorSessions();
        SettlementBatchResponse first = settlementService.seal(new SealBatchRequest("B1", DAY));

        // 不同 requestId 重复封存：返回首次清分单。
        SettlementBatchResponse again = settlementService.seal(new SealBatchRequest("B2", DAY));
        assertTrue(again.replayed());
        assertEquals(first.batchId(), again.batchId());
        assertEquals(first.batchNo(), again.batchNo());
        assertEquals(0, first.totalReceivable().compareTo(again.totalReceivable()));

        // 同 requestId 异参 409。
        ApiException ex = assertThrows(ApiException.class,
                () -> settlementService.seal(new SealBatchRequest("B1", DAY.plusDays(1))));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        // 只封存过一个批次。
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM settlement_batch", Integer.class));
    }

    @Test
    void lateSnapshotAfterSealEntersSuspenseAndCanBeWrittenOffOnceWithAudit() {
        String[] sessions = twoEndedCrossOperatorSessions();
        SettlementBatchResponse batch = settlementService.seal(new SealBatchRequest("B1", DAY));

        // 封存后迟到快照：S1 新增序号1，读数 15kWh（封存时 10），增量 5kWh。
        Instant lateAt = Instant.parse("2026-09-20T23:00:00Z");
        var lateResp = sessionService.meter(sessions[0], meter("LATE1", 1, "15.000", lateAt));
        assertTrue(lateResp.suspended());
        String suspenseNo = lateResp.suspenseNo();

        SuspenseRow suspense = jdbc.queryForObject(
                "SELECT * FROM suspense_account WHERE suspense_no = ?",
                (rs, n) -> new SuspenseRow(rs.getLong("id"), rs.getString("suspense_no"),
                        rs.getLong("session_id"), rs.getString("session_no"),
                        rs.getObject("batch_id", Long.class), rs.getInt("reading_seq"),
                        rs.getBigDecimal("energy_delta_kwh"), rs.getBigDecimal("receivable_delta"),
                        rs.getBigDecimal("payable_delta"), rs.getString("reason"),
                        rs.getString("status"), rs.getTimestamp("created_at_utc").toInstant()),
                suspenseNo);
        assertEquals("PENDING", suspense.status());
        assertEquals(0, new BigDecimal("5.000").compareTo(suspense.energyDeltaKwh()));
        // 增量应收 5*1.2=6.00；增量应付 5*0.9=4.50。
        assertEquals(0, new BigDecimal("6.00").compareTo(suspense.receivableDelta()));
        assertEquals(0, new BigDecimal("4.50").compareTo(suspense.payableDelta()));
        assertEquals(batch.batchId(), suspense.batchId());

        // 人工核销并留痕。
        WriteOffResponse wo = settlementService.writeOff(suspenseNo,
                new WriteOffRequest("W1", "operator-li", "补录电量差异核销"));
        assertEquals("WRITTEN_OFF", wo.status());

        // 留痕存在且只有一条。
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM suspense_write_off", Integer.class));
        String operator = jdbc.queryForObject(
                "SELECT operator_name FROM suspense_write_off WHERE suspense_id = ?",
                String.class, suspense.id());
        assertEquals("operator-li", operator);

        // 同一挂账只允许核销一次：不同 requestId 再核销返回 409。
        ApiException ex = assertThrows(ApiException.class,
                () -> settlementService.writeOff(suspenseNo,
                        new WriteOffRequest("W2", "operator-wang", "再次核销")));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());

        // 同 requestId 同参重放：返回首次核销结果，不新增留痕。
        WriteOffRequest sameReq = new WriteOffRequest("W1", "operator-li", "补录电量差异核销");
        WriteOffResponse replay = settlementService.writeOff(suspenseNo, sameReq);
        assertTrue(replay.replayed());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM suspense_write_off", Integer.class));
    }

    @Test
    void repeatedLateSnapshotIsNotRecounted() {
        String[] sessions = twoEndedCrossOperatorSessions();
        settlementService.seal(new SealBatchRequest("B1", DAY));

        Instant lateAt = Instant.parse("2026-09-20T23:00:00Z");
        var first = sessionService.meter(sessions[0], meter("LATE1", 1, "15.000", lateAt));
        assertTrue(first.suspended());

        // 同序号同读数重传：去重，不再挂账。
        var dup = sessionService.meter(sessions[0], meter("LATE1DUP", 1, "15.000", lateAt));
        assertTrue(dup.deduplicated());
        assertFalse(dup.suspended());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM suspense_account", Integer.class));
    }

    @Test
    void sealedBatchExcludesSessionsOfOtherDays() {
        twoEndedCrossOperatorSessions();
        SettlementBatchResponse batch = settlementService.seal(new SealBatchRequest("B1", DAY));
        assertEquals(2, batch.sessionCount());

        // 次日会话不会进入当日批次。
        Instant nextDay = Instant.parse("2026-09-21T08:00:00Z");
        clock.setInstant(nextDay);
        AuthorizeResponse a = authorizationService.authorize(
                new AuthorizeRequest("A9", "FP-CAR-0001", "PILE-B-001", nextDay));
        sessionService.start(new StartSessionRequest("ST9", a.sessionNo(), null, null, null));
        sessionService.meter(a.sessionNo(), meter("M9", 0, "3.000", nextDay.plusSeconds(20)));
        sessionService.end(a.sessionNo(), new EndSessionRequest("E9", nextDay.plus(Duration.ofMinutes(10))));

        SettlementBatchResponse sameBatch = settlementService.seal(new SealBatchRequest("B2", DAY));
        assertEquals(2, sameBatch.sessionCount());
    }
}
