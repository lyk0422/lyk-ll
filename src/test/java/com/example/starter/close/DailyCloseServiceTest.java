package com.example.starter.close;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.odorant.OdorantService;
import com.example.starter.reading.LateEventRepository;
import com.example.starter.web.IdempotentConflictException;
import com.example.starter.web.dto.DailyCloseRequest;
import com.example.starter.web.dto.DailyCloseResponse;
import com.example.starter.web.dto.OdorantDetectRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 日结测试：勾稽恰3%封存、超3%转人工、封存终态重放、封存后迟到只记事件、同键异参409。
 */
class DailyCloseServiceTest extends AbstractIntegrationTest {

    private static final LocalDate BIZ_DATE = LocalDate.of(2026, 9, 25);

    @Autowired
    private DailyCloseService dailyCloseService;
    @Autowired
    private DailyCloseRepository dailyCloseRepository;
    @Autowired
    private OdorantService odorantService;
    @Autowired
    private LateEventRepository lateEventRepository;

    private void addFlow(String requestId, String flow) {
        odorantService.detect(new OdorantDetectRequest("ST01", requestId,
                BIZ_DATE.atTime(10, 0), new BigDecimal(flow), new BigDecimal("20.000")));
    }

    private DailyCloseRequest closeRequest(String requestId, String reportedMg) {
        return new DailyCloseRequest("ST01", requestId, BIZ_DATE, new BigDecimal(reportedMg));
    }

    @Test
    void deviationExactly3PercentIsSealed() {
        addFlow("DC-F1", "1000");
        // 理论消耗 = 1000 × 20 = 20000mg；恰 +3% = 20600mg
        DailyCloseResponse resp = dailyCloseService.close(closeRequest("DC-1", "20600"));
        assertEquals(0, new BigDecimal("3.0000").compareTo(resp.deviationPct()), "恰3%边界");
        assertEquals("SEALED", resp.status());
        assertTrue(resp.sealed());
    }

    @Test
    void deviationOver3PercentGoesManualThenSealsAfterCorrection() {
        addFlow("DC-F2", "1000");
        DailyCloseResponse manual = dailyCloseService.close(closeRequest("DC-2A", "20601"));
        assertEquals("MANUAL", manual.status(), "超过3%必须转人工");
        assertFalse(manual.sealed());

        // 不同请求重新日结，达标后允许封存
        DailyCloseResponse sealed = dailyCloseService.close(closeRequest("DC-2B", "19400"));
        assertEquals("SEALED", sealed.status(), "|20000-19400|/20000=3% 恰边界可封存");
        assertTrue(sealed.sealed());
    }

    @Test
    void repeatSealReturnsIdempotentSuccess() {
        addFlow("DC-F3", "500");
        DailyCloseResponse first = dailyCloseService.close(closeRequest("DC-3", "10000"));
        assertEquals("SEALED", first.status());
        assertFalse(first.idempotentReplay());

        // 同键同参重复封存：幂等成功
        DailyCloseResponse replay = dailyCloseService.close(closeRequest("DC-3", "10000"));
        assertTrue(replay.idempotentReplay());
        assertEquals("SEALED", replay.status());

        // 新 requestId 重复封存已封存日：返回既有封存结果，状态仍是终态 SEALED
        DailyCloseResponse again = dailyCloseService.close(closeRequest("DC-3B", "9999"));
        assertEquals("SEALED", again.status(), "封存为终态，重复封存不得改为MANUAL");
        assertTrue(again.sealed());
        assertEquals(1, count("SELECT COUNT(*) FROM daily_close WHERE station_id = 1 AND biz_date = ?",
                java.sql.Date.valueOf(BIZ_DATE)));
    }

    @Test
    void sameKeyDifferentParamsReturns409WithoutChange() {
        addFlow("DC-F4", "1000");
        dailyCloseService.close(closeRequest("DC-4", "20000"));
        assertThrows(IdempotentConflictException.class,
                () -> dailyCloseService.close(closeRequest("DC-4", "99999")));
        assertTrue(dailyCloseRepository.find(1L, BIZ_DATE).orElseThrow().sealedAt() != null);
    }

    @Test
    void lateReadingAfterSealOnlyRecordsLateEvent() {
        addFlow("DC-F5", "1000");
        dailyCloseService.close(closeRequest("DC-5", "20000"));

        OdorantDetectRequest late = new OdorantDetectRequest("ST01", "DC-LATE1",
                BIZ_DATE.atTime(23, 30), new BigDecimal("10"), new BigDecimal("20.000"));
        com.example.starter.web.dto.OdorantDetectResponse resp = odorantService.detect(late);
        assertTrue(resp.late(), "封存后迟到检测必须标记late");

        assertEquals(1, lateEventRepository.countByRequest("DC-LATE1"), "迟到数据必须记录迟到事件");
        assertEquals(0, count("SELECT COUNT(*) FROM odorant_reading WHERE request_id = 'DC-LATE1'"),
                "迟到数据不得进入检测读数与日结统计");
        // 日结核对的累计流量仍为封存时的 1000m3
        DailyCloseRow row = dailyCloseRepository.find(1L, BIZ_DATE).orElseThrow();
        assertEquals(0, new BigDecimal("1000.000").compareTo(row.totalFlowM3()));
    }
}
