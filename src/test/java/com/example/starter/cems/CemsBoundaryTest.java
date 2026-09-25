package com.example.starter.cems;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.cems.service.CemsService;
import com.example.starter.cems.testsupport.TestClockConfiguration;
import com.example.starter.cems.web.dto.ConfirmRequest;
import com.example.starter.cems.web.dto.ConfirmResult;
import com.example.starter.cems.web.dto.OrderView;
import com.example.starter.cems.web.dto.ReadingRequest;
import com.example.starter.cems.web.dto.ReadingResult;
import com.example.starter.cems.web.dto.RegisterRequest;

/**
 * 边界判定：恰等限值达标、恰连续 3 次小时超标限排、恰 2 小时缺失不督办、恰满 2 小时升级。
 */
class CemsBoundaryTest extends AbstractCemsTest {

    @Autowired
    private CemsService service;

    private RegisterRequest register(String code, String reqId, String daily, String hourly) {
        return new RegisterRequest(reqId, code, "测试排口-" + code,
                new BigDecimal(daily), new BigDecimal(hourly));
    }

    private ReadingRequest hourly(String reqId, String code, String slot, String value) {
        return new ReadingRequest(reqId, code, "HOUR", LocalDateTime.parse(slot),
                new BigDecimal(value));
    }

    private ReadingRequest daily(String reqId, String code, String day, String value) {
        return new ReadingRequest(reqId, code, "DAY", LocalDateTime.parse(day),
                new BigDecimal(value));
    }

    @Test
    void 日均值恰等限值判达标_严格大于才生成超标标记() {
        service.register(register("OUT-EQ", "reg-eq", "10", "5"));

        // 恰等限值：达标，无标记，排口进入监测中
        TestClockConfiguration.CLOCK.setLocal("2026-09-21T01:00:00");
        ReadingResult equal = service.reading(
                daily("d-eq", "OUT-EQ", "2026-09-20T00:00:00", "10")).result();
        assertNull(equal.mark());
        assertEquals("MONITORING", service.getOutfall("OUT-EQ").status());

        // 严格大于：生成超标标记并置 MARKED
        TestClockConfiguration.CLOCK.setLocal("2026-09-22T01:00:00");
        ReadingResult over = service.reading(
                daily("d-over", "OUT-EQ", "2026-09-21T00:00:00", "10.01")).result();
        assertNotNull(over.mark());
        assertEquals(0, new BigDecimal("10.01").compareTo(over.mark().dailyAvg()));
        assertEquals("MARKED", service.getOutfall("OUT-EQ").status());
    }

    @Test
    void 小时均值恰连续3次超标自动下达限排令_第3次触发() {
        service.register(register("OUT-3", "reg-3", "10", "5"));

        TestClockConfiguration.CLOCK.setLocal("2026-09-20T09:05:00");
        assertNull(service.reading(
                hourly("h1", "OUT-3", "2026-09-20T09:00:00", "6")).result().order());

        TestClockConfiguration.CLOCK.setLocal("2026-09-20T10:05:00");
        assertNull(service.reading(
                hourly("h2", "OUT-3", "2026-09-20T10:00:00", "7")).result().order());

        // 恰连续第 3 次：自动下达
        TestClockConfiguration.CLOCK.setLocal("2026-09-20T11:05:00");
        ReadingResult third = service.reading(
                hourly("h3", "OUT-3", "2026-09-20T11:00:00", "8")).result();
        OrderView order = third.order();
        assertNotNull(order, "恰连续3次超标必须在第3次触发限排");
        assertEquals("ISSUED", order.status());
        assertEquals(13, order.confirmDeadline().getHour());
        assertEquals(5, order.confirmDeadline().getMinute());
        // 冻结基数=三次小时均值累计
        assertEquals(0, new BigDecimal("21").compareTo(order.baselineTotal()));
        assertEquals("RESTRICTED", service.getOutfall("OUT-3").status());

        // 第 4 次超标不重复下达（同一排口同时刻仅一个执行中限排令）
        TestClockConfiguration.CLOCK.setLocal("2026-09-20T12:05:00");
        assertNull(service.reading(
                hourly("h4", "OUT-3", "2026-09-20T12:00:00", "9")).result().order());
    }

    @Test
    void 两次超标后中断_第三次不构成恰连续3次不下达() {
        service.register(register("OUT-BREAK", "reg-break", "10", "5"));
        TestClockConfiguration.CLOCK.setLocal("2026-09-20T09:05:00");
        service.reading(hourly("b1", "OUT-BREAK", "2026-09-20T09:00:00", "6"));
        TestClockConfiguration.CLOCK.setLocal("2026-09-20T10:05:00");
        service.reading(hourly("b2", "OUT-BREAK", "2026-09-20T10:00:00", "7"));
        TestClockConfiguration.CLOCK.setLocal("2026-09-20T11:05:00");
        assertNull(service.reading(
                hourly("b3", "OUT-BREAK", "2026-09-20T11:00:00", "5")).result().order());

        // 12 点超标：尾部仅连续 1 次，不触发
        TestClockConfiguration.CLOCK.setLocal("2026-09-20T12:05:00");
        assertNull(service.reading(
                hourly("b4", "OUT-BREAK", "2026-09-20T12:00:00", "6")).result().order());
    }

    @Test
    void 恰2小时缺失不督办() {
        // 登记于 08:00；11:00 上报 08:00 读数，完整缺失小时为 09、10，恰 2 小时：不督办
        service.register(register("OUT-MISS2", "reg-miss2", "10", "5"));
        TestClockConfiguration.CLOCK.setLocal("2026-09-20T11:00:00");
        ReadingResult twoHours = service.reading(
                hourly("m2", "OUT-MISS2", "2026-09-20T08:00:00", "4")).result();
        assertTrue(twoHours.notices().isEmpty(), "恰2小时缺失不得督办");
    }

    @Test
    void 连续缺失3小时严格超过2小时才督办() {
        // 另一排口：12:00 上报 08:00 读数，完整缺失小时为 09、10、11，共 3 小时：督办
        service.register(register("OUT-MISS3", "reg-miss3", "10", "5"));
        TestClockConfiguration.CLOCK.setLocal("2026-09-20T12:00:00");
        ReadingResult threeHours = service.reading(
                hourly("m3", "OUT-MISS3", "2026-09-20T08:00:00", "4")).result();
        assertEquals(1, threeHours.notices().size());
        assertEquals(3, threeHours.notices().get(0).missingHours());
        assertEquals(LocalDateTime.parse("2026-09-20T09:00:00"),
                threeHours.notices().get(0).gapStart());
        assertEquals(LocalDateTime.parse("2026-09-20T11:00:00"),
                threeHours.notices().get(0).gapEnd());
    }

    @Test
    void 限排令2小时内确认不升级() {
        issueOrderAt("OUT-OK", "ok");
        // 下达于 11:05，截止 13:05；13:00 确认仍在 2 小时内
        TestClockConfiguration.CLOCK.setLocal("2026-09-20T13:00:00");
        ConfirmResult inTime = service.confirm(
                new ConfirmRequest("c-in", "OUT-OK")).result();
        assertTrue(inTime.order().confirmed());
        assertEquals(0, inTime.escalated() ? 1 : 0);
    }

    @Test
    void 限排令恰满截止时刻确认即升级() {
        issueOrderAt("OUT-EDGE", "edge");
        // 恰满 2 小时（13:05）未确认：升级并联动限产
        TestClockConfiguration.CLOCK.setLocal("2026-09-20T13:05:00");
        ConfirmResult result = service.confirm(
                new ConfirmRequest("c-edge", "OUT-EDGE")).result();
        assertTrue(result.escalated(), "恰满2小时未确认必须升级");
        assertTrue(result.order().productionLimited(), "升级须联动限产");
        assertEquals("ESCALATED", result.order().status());
    }

    @Test
    void 超时扫描在恰满2小时时升级且可重复扫描不重复升级() {
        issueOrderAt("OUT-SCAN", "scan");
        TestClockConfiguration.CLOCK.setLocal("2026-09-20T13:04:59");
        assertTrue(service.scanEscalations().isEmpty(), "未满2小时不升级");
        TestClockConfiguration.CLOCK.setLocal("2026-09-20T13:05:00");
        var escalated = service.scanEscalations();
        assertEquals(1, escalated.size());
        assertTrue(escalated.get(0).productionLimited());
        // 幂等：再次扫描不重复升级
        assertTrue(service.scanEscalations().isEmpty());
    }

    @Test
    void 缺失补齐后复算清除督办() {
        service.register(register("OUT-RECALC", "reg-recalc", "10", "5"));
        // 12:00 上报 08:00：09-11 缺失 3 小时，督办
        TestClockConfiguration.CLOCK.setLocal("2026-09-20T12:00:00");
        assertEquals(1, service.reading(
                hourly("rc-08", "OUT-RECALC", "2026-09-20T08:00:00", "4"))
                .result().notices().size());
        // 补齐 09、10、11
        TestClockConfiguration.CLOCK.setLocal("2026-09-20T12:10:00");
        service.reading(hourly("rc-09", "OUT-RECALC", "2026-09-20T09:00:00", "4"));
        service.reading(hourly("rc-10", "OUT-RECALC", "2026-09-20T10:00:00", "4"));
        service.reading(hourly("rc-11", "OUT-RECALC", "2026-09-20T11:00:00", "4"));
        // 12:30 上报 12 点读数触发复算：完整小时截至 11，已无缺失
        TestClockConfiguration.CLOCK.setLocal("2026-09-20T12:30:00");
        ReadingResult recomputed = service.reading(
                hourly("rc-12", "OUT-RECALC", "2026-09-20T12:00:00", "4")).result();
        assertTrue(recomputed.notices().isEmpty(), "缺失补齐后复算应清除督办");
        assertTrue(service.getNotices("OUT-RECALC").isEmpty());
    }

    /** 辅助：按 09/10/11 三次超标在 11:05 下达限排令。 */
    private void issueOrderAt(String code, String prefix) {
        service.register(register(code, "reg-" + prefix, "10", "5"));
        TestClockConfiguration.CLOCK.setLocal("2026-09-20T09:05:00");
        service.reading(hourly(prefix + "-h1", code, "2026-09-20T09:00:00", "6"));
        TestClockConfiguration.CLOCK.setLocal("2026-09-20T10:05:00");
        service.reading(hourly(prefix + "-h2", code, "2026-09-20T10:00:00", "7"));
        TestClockConfiguration.CLOCK.setLocal("2026-09-20T11:05:00");
        ReadingResult r = service.reading(
                hourly(prefix + "-h3", code, "2026-09-20T11:00:00", "8")).result();
        assertNotNull(r.order());
    }
}
