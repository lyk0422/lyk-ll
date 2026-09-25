package com.example.starter.query;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.close.DailyCloseService;
import com.example.starter.odorant.OdorantService;
import com.example.starter.pressure.PressureService;
import com.example.starter.web.dto.DailyCloseRequest;
import com.example.starter.web.dto.OdorantDetectRequest;
import com.example.starter.web.dto.PressureDetectRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可复算测试：浓度曲线、压力链、消耗勾稽均由原始数据重新计算并与首次结论一致。
 */
class RecomputeServiceTest extends AbstractIntegrationTest {

    private static final LocalDate BIZ_DATE = LocalDate.of(2026, 9, 25);

    @Autowired
    private RecomputeService recomputeService;
    @Autowired
    private OdorantService odorantService;
    @Autowired
    private PressureService pressureService;
    @Autowired
    private DailyCloseService dailyCloseService;

    private OdorantDetectRequest odorant(String requestId, LocalDateTime time, String conc, String flow) {
        return new OdorantDetectRequest("ST01", requestId, time, new BigDecimal(flow), new BigDecimal(conc));
    }

    @Test
    void concentrationCurveRecomputesClosedBoundaries() {
        odorantService.detect(odorant("RC-1", BIZ_DATE.atTime(8, 0), "16.000", "100"));
        odorantService.detect(odorant("RC-2", BIZ_DATE.atTime(9, 0), "24.001", "100"));

        List<CurvePoint> curve = recomputeService.concentrationCurve("ST01", BIZ_DATE);
        assertEquals(2, curve.size());
        assertTrue(curve.get(0).qualified(), "复算：恰下限16.000合格");
        assertFalse(curve.get(1).qualified(), "复算：24.001越限");
        assertEquals(0, new BigDecimal("16.000").compareTo(curve.get(0).lowerBoundMgM3()));
        assertEquals(0, new BigDecimal("24.000").compareTo(curve.get(0).upperBoundMgM3()));
    }

    @Test
    void pressureChainRecomputesGradesAndIssues() {
        pressureService.detect(new PressureDetectRequest("ST01", "RC-P1", BIZ_DATE.atTime(10, 0),
                new BigDecimal("0.5000"), new BigDecimal("100")));
        pressureService.detect(new PressureDetectRequest("ST01", "RC-P2", BIZ_DATE.atTime(11, 0),
                new BigDecimal("0.2000"), new BigDecimal("100")));

        List<PressureChainPoint> chain = recomputeService.pressureChain("ST01", BIZ_DATE);
        assertEquals(2, chain.size());
        assertEquals("NORMAL", chain.get(0).grade(), "复算：恰上限0.5000合格");
        assertFalse(chain.get(0).pressureAdjusted());
        assertEquals("LOW", chain.get(1).grade(), "复算：0.2000低于0.3000下限");
        assertTrue(chain.get(1).pressureAdjusted(), "联动点须回查到调压指令");
        assertTrue(chain.get(1).injectionAdjusted(), "联动点须回查到加注指令");
        assertEquals("COMPLETED", chain.get(1).taskStatus());
    }

    @Test
    void reconciliationRecomputesFlowAndDeviation() {
        odorantService.detect(odorant("RC-F1", BIZ_DATE.atTime(8, 0), "20.000", "600"));
        odorantService.detect(odorant("RC-F2", BIZ_DATE.atTime(9, 0), "20.000", "400"));
        dailyCloseService.close(new DailyCloseRequest("ST01", "RC-D1", BIZ_DATE,
                new BigDecimal("20600")));

        ReconciliationResult r = recomputeService.reconcile("ST01", BIZ_DATE);
        assertEquals(0, new BigDecimal("1000.000").compareTo(r.totalFlowM3()), "复算累计流量1000m3");
        assertEquals(0, new BigDecimal("20000.000").compareTo(r.expectedOdorantMg()),
                "复算理论消耗20000mg");
        assertEquals(0, new BigDecimal("3.0000").compareTo(r.deviationPct()), "复算偏差恰3%");
        assertTrue(r.sealed());
        assertEquals(2, r.readingCount());
    }
}
