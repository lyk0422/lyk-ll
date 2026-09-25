package com.example.rebate.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.rebate.testsupport.BaseApiTest;
import com.example.rebate.testsupport.TestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * 退税率按申报日（UTC 日界）取有效文库版本。
 */
class LibraryVersionByDayTest extends BaseApiTest {

    private JsonNode resolve(String date) throws Exception {
        return getJson("/api/recalc/library?productCode=" + TestFixtures.P1
                + "&declareDate=" + date, 200);
    }

    @Test
    void picksEffectiveVersionAtUtcDayBoundary() throws Exception {
        seedLibrary();

        // 2025-12-31 仍命中 V2025(10%)；2026-01-01 当天起命中 V2026(13%)
        JsonNode dayBefore = resolve("2025-12-31");
        JsonNode effectiveDay = resolve("2026-01-01");
        JsonNode later = resolve("2026-06-30");

        assertEquals("V2025", dayBefore.get("effectiveVersion").asText());
        assertEquals(0, new java.math.BigDecimal("0.10")
                .compareTo(new java.math.BigDecimal(dayBefore.get("rebateRate").asText())));
        assertEquals("V2026", effectiveDay.get("effectiveVersion").asText());
        assertEquals("V2026", later.get("effectiveVersion").asText());
    }

    @Test
    void missingVersionProducesRateNotFoundDoubt() throws Exception {
        seedLibrary();
        // 仅登记 V2025；申报日早于最早生效日 -> RATE_NOT_FOUND
        postJson("/api/collect/customs",
                TestFixtures.customs("c1", "CD-NORATE", TestFixtures.P1, "500", "100"), 200);
        postJson("/api/collect/invoices",
                TestFixtures.invoice("i1", "INV-NORATE", "CD-NORATE", TestFixtures.P1, "450"), 200);

        JsonNode resp = postJson("/api/batches/check",
                TestFixtures.check("chk-norate", "B-NORATE", "2024-01-01", "CD-NORATE"), 200);

        assertEquals("DOUBTFUL", resp.get("status").asText());
        assertEquals("RATE_NOT_FOUND", resp.get("details").get(0).get("doubtType").asText());
        assertEquals("OPEN", resp.get("doubts").get(0).get("status").asText());
    }
}
