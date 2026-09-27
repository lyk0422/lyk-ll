package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 批次有效期边界（可控固定时钟）：now&lt;截止 有效，恰在截止即失效。
 * 覆盖批准与受控投料两个校验点。
 * 因 @MockitoBean 产生独立 ApplicationContext，使用独立命名库避免种子重复初始化。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url="
                + "jdbc:h2:mem:test_dye_expiry;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class DyeBatchExpiryTest extends BaseIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-09-27T10:00:00Z");

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void stubClock() {
        when(clock.instant()).thenReturn(T0);
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Shanghai"));
    }

    @Test
    void approve_exactlyAtValidUntil_rejected_oneSecondBefore_accepted() {
        // 恰在截止：失效
        postOk("/api/dye-batches", Map.of(
                "requestId", "ex-rb-1", "batchNo", "B-EDGE", "dyeCode", "D-RED",
                "potency", new BigDecimal("1.25"), "availableG", new BigDecimal("4000"),
                "validUntil", T0.toString()));
        // 截止晚 1 秒：有效
        postOk("/api/dye-batches", Map.of(
                "requestId", "ex-rb-2", "batchNo", "B-FUTURE", "dyeCode", "D-RED",
                "potency", new BigDecimal("1.25"), "availableG", new BigDecimal("4000"),
                "validUntil", T0.plusSeconds(1).toString()));

        schedule("ex-sched-1", "LX-1", "VX-1", "R-BASE", 1, "50");
        ResponseEntity<Map> expired = post("/api/compensations", Map.of(
                "requestId", "ex-ap-1", "approvalNo", "AP-X1", "lotNo", "LX-1",
                "items", List.of(
                        Map.of("dyeCode", "D-RED", "batchNo", "B-EDGE", "potencyVersion", 1),
                        Map.of("dyeCode", "D-YELLOW", "batchNo", "B-YELLOW-01", "potencyVersion", 1),
                        Map.of("dyeCode", "D-BLUE", "batchNo", "B-BLUE-01", "potencyVersion", 1))));
        assertEquals(422, expired.getStatusCode().value(), "恰在截止时刻批次失效，整单拒绝");

        schedule("ex-sched-2", "LX-2", "VX-2", "R-BASE", 1, "50");
        Map<String, Object> ok = postOk("/api/compensations", Map.of(
                "requestId", "ex-ap-2", "approvalNo", "AP-X2", "lotNo", "LX-2",
                "items", List.of(
                        Map.of("dyeCode", "D-RED", "batchNo", "B-FUTURE", "potencyVersion", 1),
                        Map.of("dyeCode", "D-YELLOW", "batchNo", "B-YELLOW-01", "potencyVersion", 1),
                        Map.of("dyeCode", "D-BLUE", "batchNo", "B-BLUE-01", "potencyVersion", 1))));
        assertEquals("ACTIVE", ok.get("status"));
    }

    @Test
    void controlledFeed_batchExpiresBetweenApproveAndFeed_rejected() {
        // 批准时批次有效（截止 T0+10s）
        postOk("/api/dye-batches", Map.of(
                "requestId", "ex-rb-3", "batchNo", "B-SHORT", "dyeCode", "D-RED",
                "potency", new BigDecimal("1.25"), "availableG", new BigDecimal("4000"),
                "validUntil", T0.plusSeconds(10).toString()));
        schedule("ex-sched-3", "LX-3", "VX-3", "R-BASE", 1, "50");
        postOk("/api/compensations", Map.of(
                "requestId", "ex-ap-3", "approvalNo", "AP-X3", "lotNo", "LX-3",
                "items", List.of(
                        Map.of("dyeCode", "D-RED", "batchNo", "B-SHORT", "potencyVersion", 1),
                        Map.of("dyeCode", "D-YELLOW", "batchNo", "B-YELLOW-01", "potencyVersion", 1),
                        Map.of("dyeCode", "D-BLUE", "batchNo", "B-BLUE-01", "potencyVersion", 1))));

        // 时钟推进到截止之后：投料时有效期校验拒绝，不扣批次
        when(clock.instant()).thenReturn(T0.plusSeconds(11));
        ResponseEntity<Map> rejected = post("/api/controlled-feedings", Map.of(
                "requestId", "ex-feed-3", "lotNo", "LX-3", "approvalNo", "AP-X3",
                "weighings", List.of(
                        Map.of("dyeCode", "D-RED", "actualG", new BigDecimal("800.0000")),
                        Map.of("dyeCode", "D-YELLOW", "actualG", new BigDecimal("750.0000")),
                        Map.of("dyeCode", "D-BLUE", "actualG", new BigDecimal("312.5000")))));
        assertEquals(422, rejected.getStatusCode().value());
        assertEquals(0, new BigDecimal("4000.000").compareTo(batchAvailable("B-SHORT")));
        assertEquals("CREATED", jdbc.queryForObject(
                "SELECT status FROM lot WHERE lot_no='LX-3'", String.class));
    }
}
