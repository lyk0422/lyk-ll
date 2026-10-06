package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.starter.dto.DimRequest;
import com.example.starter.dto.SettlementView;
import com.example.starter.service.DimmingService;
import com.example.starter.service.SettlementService;

/**
 * 日结测试：输出各回路调光次数与能耗，能耗由调光留痕复算，重复日结结果一致。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:tl_test_settle;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class SettlementTest extends BaseIntegrationTest {

    private static final LocalDate SETTLE_DATE = LocalDate.of(2026, 10, 5);

    @Autowired
    private DimmingService dimmingService;

    @Autowired
    private SettlementService settlementService;

    @Test
    void 日结输出调光次数与能耗且可复算() {
        // 基准时间 2026-10-05T08:00:00Z，回路1 初始 3 档(240W)
        dimmingService.dimManually(1L, new DimRequest("r-se-1", 4));
        clock.advance(Duration.ofMinutes(10));
        dimmingService.dimManually(1L, new DimRequest("r-se-2", 2));
        clock.advance(Duration.ofMinutes(10));
        dimmingService.dimManually(1L, new DimRequest("r-se-3", 5));
        // 当前时刻 08:20，日结按当前时刻截断

        List<SettlementView> views = settlementService.settle(SETTLE_DATE);
        assertEquals(3, views.size());

        SettlementView c1 = findByCircuit(views, 1L);
        assertEquals(3, c1.dimCount());
        // 240W*28800s(00:00-08:00,3档) + 320W*600s(08:00-08:10,4档)
        // + 160W*600s(08:10-08:20,2档) + 400W*0s = 7,200,000 Ws = 2,000,000 mWh
        assertEquals(2_000_000L, c1.energyMwh());
        assertEquals(2000.0d, c1.energyWh(), 0.0001d);

        SettlementView c2 = findByCircuit(views, 2L);
        assertEquals(0, c2.dimCount());
        // 回路2 全天维持初始 2 档(160W)，00:00-08:20 共 30000s → 4,800,000 Ws = 1,333,333 mWh
        assertEquals(1_333_333L, c2.energyMwh());

        // 可复算：重复日结结果一致，快照每回路一行
        List<SettlementView> again = settlementService.settle(SETTLE_DATE);
        assertEquals(views, again);
        assertEquals(3, settlementService.findSnapshot(SETTLE_DATE).size());
    }

    @Test
    void 无调光记录时日结按初始档位计能耗() {
        List<SettlementView> views = settlementService.settle(SETTLE_DATE);
        SettlementView c1 = findByCircuit(views, 1L);
        assertEquals(0, c1.dimCount());
        // 回路1 初始 3 档(240W)，00:00-08:00 共 28800s → 6,912,000 Ws = 1,920,000 mWh
        assertEquals(1_920_000L, c1.energyMwh());
    }

    private SettlementView findByCircuit(List<SettlementView> views, long circuitId) {
        return views.stream().filter(v -> v.circuitId() == circuitId).findFirst().orElseThrow();
    }
}
