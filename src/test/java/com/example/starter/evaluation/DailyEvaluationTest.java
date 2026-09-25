package com.example.starter.evaluation;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.api.dto.CommandDtos;
import com.example.starter.api.dto.EvaluationDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 日评估：SEALED 终态幂等、迟到读数不改变结果、设定达成率与能耗明细可复算、并发封存唯一成功。
 */
class DailyEvaluationTest extends AbstractIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2026, 1, 6);
    private static final Instant T0 = DAY.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();

    @BeforeEach
    void setUp() {
        seedZone();
        seedCrop();
        allocateBudget(DAY.toString(), "10.000");
        draftStrategy("S1", "24.00", "18.00", "70.00", "1.0", "5.0");
        dispatch("S1", "REQ-D", T0);
    }

    @Test
    @DisplayName("封存输出达成率与能耗明细；重复封存幂等返回首次结果且 replayed=true；迟到读数不改变封存值")
    void sealIsTerminalAndReplayable() {
        // 3 点在带内（含边界 25.0℃、湿度 75%RH），1 点偏离：达成率 0.75。
        ingest(T0, "24.00", "70");
        ingest(T0.plusSeconds(60), "25.00", "70");
        ingest(T0.plusSeconds(120), "25.01", "70");
        ingest(T0.plusSeconds(180), "24.00", "75");

        energyService.issueCommand(new CommandDtos.CommandRequest(
                "CMD-H", ZONE, "HEAT", bd("6.000"), T0.plusSeconds(10)));
        energyService.issueCommand(new CommandDtos.CommandRequest(
                "CMD-L", ZONE, "SUPPLEMENT_LIGHT", bd("2.000"), T0.plusSeconds(20)));
        energyService.issueCommand(new CommandDtos.CommandRequest(
                "CMD-V", ZONE, "VENTILATE", null, T0.plusSeconds(30)));

        EvaluationDtos.EvaluationResponse first = evaluationService.seal(
                new EvaluationDtos.EvaluationRequest(ZONE, DAY));
        assertThat(first.status()).isEqualTo("SEALED");
        assertThat(first.replayed()).isFalse();
        assertThat(first.inBandCount()).isEqualTo(3);
        assertThat(first.totalCount()).isEqualTo(4);
        assertThat(first.achievementRate()).isEqualByComparingTo("0.7500");
        assertThat(first.budgetKwh()).isEqualByComparingTo("10.000");
        assertThat(first.consumedKwh()).isEqualByComparingTo("8.000");

        Map<String, EvaluationDtos.EnergyItem> detail = new java.util.HashMap<>();
        first.energyDetail().forEach(i -> detail.put(i.commandType(), i));
        assertThat(detail.get("HEAT").commandCount()).isEqualTo(1);
        assertThat(detail.get("HEAT").energyKwh()).isEqualByComparingTo("6.000");
        assertThat(detail.get("SUPPLEMENT_LIGHT").energyKwh()).isEqualByComparingTo("2.000");
        assertThat(detail.get("VENTILATE").energyKwh()).isEqualByComparingTo("0.000");

        // 封存后迟到读数只记迟到事件。
        assertThat(ingest(T0.plusSeconds(900), "5.00", "5").late()).isTrue();

        EvaluationDtos.EvaluationResponse replay = evaluationService.seal(
                new EvaluationDtos.EvaluationRequest(ZONE, DAY));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.inBandCount()).isEqualTo(3);
        assertThat(replay.totalCount()).isEqualTo(4);
        assertThat(replay.consumedKwh()).isEqualByComparingTo("8.000");
        assertThat(replay.sealedAt()).isEqualTo(first.sealedAt());

        // 查询接口返回同一份终态结果。
        EvaluationDtos.EvaluationResponse loaded = evaluationService.get(ZONE, DAY);
        assertThat(loaded.id()).isEqualTo(first.id());
        assertThat(loaded.energyDetail()).hasSize(3);
    }

    @Test
    @DisplayName("无读数日封存：达成率为 0，能耗明细为空数组，仍为终态")
    void emptyDaySeal() {
        LocalDate quietDay = LocalDate.of(2026, 1, 7);
        EvaluationDtos.EvaluationResponse response = evaluationService.seal(
                new EvaluationDtos.EvaluationRequest(ZONE, quietDay));
        assertThat(response.totalCount()).isZero();
        assertThat(response.inBandCount()).isZero();
        assertThat(response.achievementRate()).isEqualByComparingTo("0.0000");
        assertThat(response.energyDetail()).isEmpty();
        assertThat(evaluationService.seal(new EvaluationDtos.EvaluationRequest(ZONE, quietDay)).replayed())
                .isTrue();
    }

    @Test
    @DisplayName("并发封存同一日：两个调用都成功且返回同一条终态记录")
    void concurrentSealHasSingleWinner() throws Exception {
        ingest(T0, "24.00", "70");
        int threads = 2;
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        Map<Integer, Long> ids = new ConcurrentHashMap<>();
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < threads; i++) {
                int idx = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    EvaluationDtos.EvaluationResponse r = evaluationService.seal(
                            new EvaluationDtos.EvaluationRequest(ZONE, DAY));
                    ids.put(idx, r.id());
                    return r;
                }));
            }
            start.countDown();
            for (var f : futures) {
                f.get(20, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(ids).hasSize(2);
        assertThat(ids.values()).containsExactly(ids.get(0), ids.get(0));
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM daily_evaluation WHERE zone_code = ? AND eval_date = ?",
                Integer.class, ZONE, java.sql.Date.valueOf(DAY));
        assertThat(count).isEqualTo(1);
    }
}
