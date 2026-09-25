package com.example.starter.cems;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.cems.service.CemsService;
import com.example.starter.cems.testsupport.TestClockConfiguration;
import com.example.starter.cems.web.ConflictException;
import com.example.starter.cems.web.dto.ArchiveRequest;
import com.example.starter.cems.web.dto.MutationResponse;
import com.example.starter.cems.web.dto.OrderView;
import com.example.starter.cems.web.dto.ReadingRequest;
import com.example.starter.cems.web.dto.ReadingResult;
import com.example.starter.cems.web.dto.RegisterRequest;
import com.example.starter.cems.web.dto.ReleaseRequest;
import com.example.starter.cems.web.dto.ReleaseResult;

/**
 * 复测解除、requestId 幂等（同键重放/同键异参 409）、归档终态与迟到数据。
 */
class CemsWorkflowTest extends AbstractCemsTest {

    @Autowired
    private CemsService service;

    private void issueRestrictedOrder(String code) {
        service.register(new RegisterRequest("reg-" + code, code, "排口-" + code,
                new BigDecimal("10"), new BigDecimal("5")));
        TestClockConfiguration.CLOCK.setLocal("2026-09-20T09:05:00");
        service.reading(new ReadingRequest(code + "-h1", code, "HOUR",
                LocalDateTime.parse("2026-09-20T09:00:00"), new BigDecimal("6")));
        TestClockConfiguration.CLOCK.setLocal("2026-09-20T10:05:00");
        service.reading(new ReadingRequest(code + "-h2", code, "HOUR",
                LocalDateTime.parse("2026-09-20T10:00:00"), new BigDecimal("7")));
        TestClockConfiguration.CLOCK.setLocal("2026-09-20T11:05:00");
        ReadingResult r = service.reading(new ReadingRequest(code + "-h3", code, "HOUR",
                LocalDateTime.parse("2026-09-20T11:00:00"), new BigDecimal("8"))).result();
        assertNotNull(r.order());
    }

    @Test
    void 复测恰等限值合格_解除限排并关闭限排令_同一事务() {
        issueRestrictedOrder("OUT-REL");
        // 先生成一个有效超标标记
        TestClockConfiguration.CLOCK.setLocal("2026-09-21T01:00:00");
        service.reading(new ReadingRequest("day-over", "OUT-REL", "DAY",
                LocalDateTime.parse("2026-09-20T00:00:00"), new BigDecimal("11")));

        // 复测小时均值恰等限值 5：判达标，可解除
        TestClockConfiguration.CLOCK.setLocal("2026-09-20T12:30:00");
        MutationResponse<ReleaseResult> response = service.release(new ReleaseRequest(
                "rel-1", "OUT-REL",
                LocalDateTime.parse("2026-09-20T12:00:00"), new BigDecimal("5")));
        assertFalse(response.replayed());
        ReleaseResult result = response.result();
        assertEquals("CLOSED", result.order().status());
        assertEquals("MONITORING", result.outfall().status());
        assertTrue(result.resolvedMarks() >= 1, "有效超标标记应同步解除");
        assertNull(service.getActiveOrderOrNull("OUT-REL"));
    }

    @Test
    void 复测超标不得解除() {
        issueRestrictedOrder("OUT-REL-FAIL");
        TestClockConfiguration.CLOCK.setLocal("2026-09-20T12:30:00");
        assertThrows(com.example.starter.cems.web.UnprocessableException.class,
                () -> service.release(new ReleaseRequest(
                        "rel-bad", "OUT-REL-FAIL",
                        LocalDateTime.parse("2026-09-20T12:00:00"), new BigDecimal("5.01"))));
        // 限排令仍执行中
        assertNotNull(service.getActiveOrder("OUT-REL-FAIL"));
    }

    @Test
    void 同键同参重放返回首次结果_同键异参返回409且不改变状态() {
        // 首次登记
        MutationResponse<com.example.starter.cems.web.dto.OutfallView> first =
                service.register(new RegisterRequest("idem-1", "OUT-IDEM", "排口",
                        new BigDecimal("10"), new BigDecimal("5")));
        assertFalse(first.replayed());

        // 同键同参重放
        MutationResponse<com.example.starter.cems.web.dto.OutfallView> replay =
                service.register(new RegisterRequest("idem-1", "OUT-IDEM", "排口",
                        new BigDecimal("10"), new BigDecimal("5")));
        assertTrue(replay.replayed());
        assertEquals(first.result().registeredAt(), replay.result().registeredAt());

        // 同键异参：409
        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.register(new RegisterRequest("idem-1", "OUT-IDEM", "排口",
                        new BigDecimal("20"), new BigDecimal("5"))));
        assertTrue(ex.getMessage().contains("不同业务内容"));
        // 排口限值未被改变
        assertEquals(0, new BigDecimal("10").compareTo(
                service.getOutfall("OUT-IDEM").dailyLimit()));
    }

    @Test
    void 读数按排口加时段去重_重复时段冲突() {
        service.register(new RegisterRequest("reg-dup", "OUT-DUP", "排口",
                new BigDecimal("10"), new BigDecimal("5")));
        TestClockConfiguration.CLOCK.setLocal("2026-09-20T09:05:00");
        service.reading(new ReadingRequest("dup-h1", "OUT-DUP", "HOUR",
                LocalDateTime.parse("2026-09-20T09:00:00"), new BigDecimal("4")));
        // 换 requestId、同时段：409
        assertThrows(ConflictException.class,
                () -> service.reading(new ReadingRequest("dup-h1-again", "OUT-DUP", "HOUR",
                        LocalDateTime.parse("2026-09-20T09:00:00"), new BigDecimal("9"))));
    }

    @Test
    void 归档为终态_重复归档幂等成功_归档后迟到数据只记迟到事件() {
        issueRestrictedOrder("OUT-ARC");
        TestClockConfiguration.CLOCK.setLocal("2026-09-30T10:00:00");
        MutationResponse<com.example.starter.cems.web.dto.OutfallView> arc1 =
                service.archive(new ArchiveRequest("arc-1", "OUT-ARC"));
        assertEquals("ARCHIVED", arc1.result().status());
        assertFalse(arc1.replayed());

        // 重复归档（不同 requestId 但排口已归档）——返回归档视图，幂等成功
        TestClockConfiguration.CLOCK.setLocal("2026-09-30T11:00:00");
        MutationResponse<com.example.starter.cems.web.dto.OutfallView> arc2 =
                service.archive(new ArchiveRequest("arc-2", "OUT-ARC"));
        assertEquals("ARCHIVED", arc2.result().status());

        // 同 requestId 重放
        MutationResponse<com.example.starter.cems.web.dto.OutfallView> arc1Replay =
                service.archive(new ArchiveRequest("arc-1", "OUT-ARC"));
        assertTrue(arc1Replay.replayed());

        // 归档后迟到监测数据：只记迟到事件，不入读数表
        int readingsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitor_reading WHERE outfall_code = 'OUT-ARC'",
                Integer.class);
        TestClockConfiguration.CLOCK.setLocal("2026-10-01T09:00:00");
        ReadingResult late = service.reading(new ReadingRequest("late-1", "OUT-ARC", "HOUR",
                LocalDateTime.parse("2026-10-01T09:00:00"), new BigDecimal("99"))).result();
        assertTrue(late.late());
        assertNull(late.order());
        int readingsAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitor_reading WHERE outfall_code = 'OUT-ARC'",
                Integer.class);
        assertEquals(readingsBefore, readingsAfter, "迟到数据不得写入读数表");
        Integer lateEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outfall_event WHERE outfall_code = 'OUT-ARC' "
                        + "AND event_type = 'LATE'", Integer.class);
        assertEquals(1, lateEvents);
    }

    @Test
    void 解除请求重放幂等_不重复关闭() {
        issueRestrictedOrder("OUT-REL2");
        TestClockConfiguration.CLOCK.setLocal("2026-09-20T12:30:00");
        ReleaseRequest req = new ReleaseRequest("rel-replay", "OUT-REL2",
                LocalDateTime.parse("2026-09-20T12:00:00"), new BigDecimal("4"));
        ReleaseResult first = service.release(req).result();
        assertEquals("CLOSED", first.order().status());

        MutationResponse<ReleaseResult> replay = service.release(req);
        assertTrue(replay.replayed());
        assertEquals("CLOSED", replay.result().order().status());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM restriction_order WHERE outfall_code = 'OUT-REL2'",
                Integer.class));
    }
}
