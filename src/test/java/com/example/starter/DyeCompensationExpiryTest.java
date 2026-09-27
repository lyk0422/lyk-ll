package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 批次有效期边界（可控时钟）：now&lt;截止有效，恰在截止失效；审批与投料两个时点都校验。
 */
class DyeCompensationExpiryTest extends BaseIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-28T08:00:00Z");

    @MockitoBean
    private Clock clock;

    private final AtomicReference<Instant> nowRef = new AtomicReference<>(T0);

    @BeforeEach
    void stubClock() {
        nowRef.set(T0);
        Mockito.lenient().when(clock.instant()).thenAnswer(inv -> nowRef.get());
        Mockito.lenient().when(clock.getZone()).thenReturn(ZoneId.of("Asia/Shanghai"));
    }

    /** 以受控时钟为基准登记批次（有效截止为相对 T0 的合成时刻）。 */
    private void registerBatch(String requestId, String lotCode, String dyeCode,
                               String potency, String availableG, Instant validUntil) {
        postOk("/api/dye-lots", Map.of(
                "requestId", requestId,
                "lotCode", lotCode,
                "dyeCode", dyeCode,
                "potency", new BigDecimal(potency),
                "availableG", new BigDecimal(availableG),
                "validUntil", validUntil.toString()));
    }

    @Test
    void approval_exactlyAtDeadline_rejected_oneSecondBefore_allowed() {
        registerBatch("ex-dl-1", "LOT-TR", "D-RED", "2.0", "2000", T0.plusSeconds(3600));
        registerBatch("ex-dl-2", "LOT-TY", "D-YELLOW", "1.5", "2000", T0.plusSeconds(7200));
        registerBatch("ex-dl-3", "LOT-TB", "D-BLUE", "1.0", "2000", T0.plusSeconds(7200));
        schedule("ex-sc", "LT", "VT", "R-BASE", 1, "50");

        // 恰在截止：失效，整单拒绝
        nowRef.set(T0.plusSeconds(3600));
        ResponseEntity<Map> atDeadline = post("/api/compensations", Map.of(
                "requestId", "ex-ap-1", "lotNo", "LT",
                "items", List.of(
                        Map.of("dyeCode", "D-RED", "lotCode", "LOT-TR", "potencyVersion", 1),
                        Map.of("dyeCode", "D-YELLOW", "lotCode", "LOT-TY", "potencyVersion", 1),
                        Map.of("dyeCode", "D-BLUE", "lotCode", "LOT-TB", "potencyVersion", 1))));
        assertEquals(422, atDeadline.getStatusCode().value());
        assertEquals(Long.valueOf(0), jdbc.queryForObject(
                "SELECT COUNT(*) FROM comp_approval WHERE lot_no='LT'", Long.class));

        // 截止前一秒：有效，批准成功（失败不占键，同键可重试）
        nowRef.set(T0.plusSeconds(3599));
        ResponseEntity<Map> beforeDeadline = post("/api/compensations", Map.of(
                "requestId", "ex-ap-1", "lotNo", "LT",
                "items", List.of(
                        Map.of("dyeCode", "D-RED", "lotCode", "LOT-TR", "potencyVersion", 1),
                        Map.of("dyeCode", "D-YELLOW", "lotCode", "LOT-TY", "potencyVersion", 1),
                        Map.of("dyeCode", "D-BLUE", "lotCode", "LOT-TB", "potencyVersion", 1))));
        assertEquals(201, beforeDeadline.getStatusCode().value());
    }

    @Test
    void feed_atOrAfterDeadline_rejected_batchRemainingUntouched() {
        registerBatch("ex2-dl-1", "LOT-FR", "D-RED", "2.0", "2000", T0.plusSeconds(3600));
        registerBatch("ex2-dl-2", "LOT-FY", "D-YELLOW", "1.5", "2000", T0.plusSeconds(7200));
        registerBatch("ex2-dl-3", "LOT-FB", "D-BLUE", "1.0", "2000", T0.plusSeconds(7200));
        schedule("ex2-sc", "LF2", "VF2", "R-BASE", 1, "50");
        approve("ex2-ap", "LF2", List.of(
                List.of("D-RED", "LOT-FR", 1),
                List.of("D-YELLOW", "LOT-FY", 1),
                List.of("D-BLUE", "LOT-FB", 1)));

        // 审批后到投料前到达截止时刻：投料拒绝，批次余量不动
        nowRef.set(T0.plusSeconds(3600));
        ResponseEntity<Map> feed = post("/api/feedings", Map.of(
                "requestId", "ex2-feed", "lotNo", "LF2",
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("500.000")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("500.000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("250.000")))));
        assertEquals(422, feed.getStatusCode().value());
        assertEquals(0, new BigDecimal("2000.000").compareTo(batchAvailable("LOT-FR")));
        assertEquals("CREATED", jdbc.queryForObject(
                "SELECT status FROM lot WHERE lot_no='LF2'", String.class));
    }
}
