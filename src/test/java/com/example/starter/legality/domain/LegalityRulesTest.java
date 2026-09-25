package com.example.starter.legality.domain;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 报到时段分档规则单元测试：覆盖三档边界与跨时区换算。
 */
class LegalityRulesTest {

    private final LegalityRules rules = new LegalityRules(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

    @Test
    void tierBoundaries_06To12Is13Hours() {
        ZoneId utc = ZoneOffset.UTC;
        // 05:59 仍属 22:00-05:59 的 10 小时档；06:00 整点切入 13 小时档。
        assertEquals(600, rules.tierLimitMinutes(Instant.parse("2026-09-20T05:59:00Z"), utc));
        assertEquals(780, rules.tierLimitMinutes(Instant.parse("2026-09-20T06:00:00Z"), utc));
        assertEquals(780, rules.tierLimitMinutes(Instant.parse("2026-09-20T12:59:00Z"), utc));
    }

    @Test
    void tierBoundaries_13To21Is12Hours() {
        ZoneId utc = ZoneOffset.UTC;
        assertEquals(720, rules.tierLimitMinutes(Instant.parse("2026-09-20T13:00:00Z"), utc));
        assertEquals(720, rules.tierLimitMinutes(Instant.parse("2026-09-20T21:59:00Z"), utc));
    }

    @Test
    void tierBoundaries_22To05Is10Hours() {
        ZoneId utc = ZoneOffset.UTC;
        assertEquals(600, rules.tierLimitMinutes(Instant.parse("2026-09-20T22:00:00Z"), utc));
        assertEquals(600, rules.tierLimitMinutes(Instant.parse("2026-09-20T05:59:00Z"), utc));
    }

    @Test
    void tierUsesDepartureAirportLocalTime() {
        // UTC 22:00 在上海（UTC+8）为次日 06:00，应落入 13 小时档而非 10 小时档。
        ZoneId shanghai = ZoneId.of("Asia/Shanghai");
        assertEquals(780, rules.tierLimitMinutes(Instant.parse("2026-09-20T22:00:00Z"), shanghai));
        // UTC 05:00 在上海为 13:00，应落入 12 小时档。
        assertEquals(720, rules.tierLimitMinutes(Instant.parse("2026-09-20T05:00:00Z"), shanghai));
    }
}
