package com.example.starter.greenhouse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.example.starter.greenhouse.dto.BudgetAllocateRequest;
import com.example.starter.greenhouse.dto.CommandIssueRequest;
import com.example.starter.greenhouse.dto.EvaluationResponse;
import com.example.starter.greenhouse.dto.ReadingIngestRequest;
import com.example.starter.greenhouse.service.EnergyService;
import com.example.starter.greenhouse.service.EvaluationService;
import com.example.starter.greenhouse.service.ReadingService;
import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 日评估：达成率与能耗明细可复算、封存终态幂等、迟到读数只记事件、并发封存唯一。
 */
class DailyEvaluationTest extends BaseGreenhouseTest {

    private static final String DAY = "2026-09-25";

    @Autowired
    private EvaluationService evaluationService;
    @Autowired
    private ReadingService readingService;
    @Autowired
    private EnergyService energyService;

    private void setupDay(String crop, String zone, String strategy) {
        createCrop(crop);
        createZone(zone);
        draftValidate(strategy, crop, zone,
                "22", "1", "70", "5", "16", "1", "70", "5");
        strategyService.activate(strategy, "REQ-" + strategy);
        energyService.allocate(new BudgetAllocateRequest(zone, DAY, new BigDecimal("20.000")));
    }

    private void reading(String zone, String time, String temp, String hum) {
        readingService.ingest(new ReadingIngestRequest(zone, time, new BigDecimal(temp), new BigDecimal(hum)));
    }

    private void command(String zone, String type, String kwh, String req) {
        energyService.issue(new CommandIssueRequest(
                zone, type, new BigDecimal(kwh), "2026-09-25T10:00:00Z", req));
    }

    @Test
    void 未封存评估实时复算且能耗明细正确() {
        setupDay("C1", "Z1", "S1");
        // 白天带 [21,23]：4 个读数中 3 个达成。
        reading("Z1", "2026-09-25T10:00:00Z", "22.00", "70");
        reading("Z1", "2026-09-25T11:00:00Z", "21.00", "65");
        reading("Z1", "2026-09-25T12:00:00Z", "23.00", "75");
        reading("Z1", "2026-09-25T13:00:00Z", "30.00", "70");
        command("Z1", "HEATING", "4.500", "CMD-1");
        command("Z1", "LIGHTING", "2.250", "CMD-2");
        command("Z1", "VENTILATION", "0.000", "CMD-3");

        EvaluationResponse eval = evaluationService.get("Z1", DAY);
        assertEquals("OPEN", eval.status());
        assertEquals(4, eval.totalReadings());
        assertEquals(3, eval.achievedReadings());
        assertEquals(new BigDecimal("0.7500"), eval.achievementRate());
        assertEquals(new BigDecimal("6.750"), eval.totalKwh());
        assertEquals(new BigDecimal("4.500"), eval.heatingKwh());
        assertEquals(new BigDecimal("2.250"), eval.lightingKwh());
        assertEquals(1, eval.ventilationCount());
        assertEquals(4, eval.details().size());

        // 再补一个达成读数，实时复算结果变化。
        reading("Z1", "2026-09-25T14:00:00Z", "22.00", "70");
        EvaluationResponse recomputed = evaluationService.get("Z1", DAY);
        assertEquals(5, recomputed.totalReadings());
        assertEquals(4, recomputed.achievedReadings());
        assertEquals(new BigDecimal("0.8000"), recomputed.achievementRate());
    }

    @Test
    void 封存为终态且重复封存幂等() {
        setupDay("C2", "Z2", "S2");
        reading("Z2", "2026-09-25T10:00:00Z", "22.00", "70");
        reading("Z2", "2026-09-25T11:00:00Z", "30.00", "70");

        EvaluationResponse sealed = evaluationService.seal("Z2", DAY);
        assertEquals("SEALED", sealed.status());
        assertNotNull(sealed.sealedAt());
        assertEquals(1, sealed.achievedReadings());

        // 封存后再到达的读数只记迟到事件，不改变封存快照。
        reading("Z2", "2026-09-25T12:00:00Z", "22.00", "70");
        EvaluationResponse replay = evaluationService.seal("Z2", DAY);
        assertEquals("SEALED", replay.status());
        assertEquals(sealed.sealedAt(), replay.sealedAt());
        assertEquals(2, replay.totalReadings(), "封存快照不被迟到读数改变");
        assertEquals(1, replay.achievedReadings());
        assertEquals(1L, replay.lateReadingEvents());

        EvaluationResponse queried = evaluationService.get("Z2", DAY);
        assertEquals(2, queried.totalReadings());
        assertEquals(1L, queried.lateReadingEvents());
    }

    @Test
    void 并发封存仅产生一个终态结果() throws Exception {
        setupDay("C3", "Z3", "S3");
        reading("Z3", "2026-09-25T10:00:00Z", "22.00", "70");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger sameSealedAt = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                start.await();
                EvaluationResponse r = evaluationService.seal("Z3", DAY);
                if (r.status().equals("SEALED")) {
                    sameSealedAt.incrementAndGet();
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(20, TimeUnit.SECONDS);
        assertEquals(threads, sameSealedAt.get());
        Long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM daily_evaluation WHERE zone_id = (SELECT id FROM zone WHERE zone_code='Z3')",
                Long.class);
        assertEquals(1L, rows);
    }
}
