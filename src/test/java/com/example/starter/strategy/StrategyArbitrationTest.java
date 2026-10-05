package com.example.starter.strategy;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.api.dto.AlarmDtos;
import com.example.starter.api.dto.StrategyDtos;
import com.example.starter.repo.StrategyRepository;
import com.example.starter.web.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 策略冲突仲裁：进行中告警处置与不兼容新策略冲突时下发挂起（无副作用），
 * 兼容或处置完结后正常下发；挂起结论与状态推进同一事务留档；并发下结果唯一。
 */
class StrategyArbitrationTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-01-11T00:00:00Z");
    private static final Instant NEXT = T0.plusSeconds(31 * 60);

    @Autowired
    private StrategyRepository strategyRepository;

    private Long alarmId;

    @BeforeEach
    void setUp() {
        seedZone();
        seedCrop();
        // 在管策略 S1：昼 24±1、夜 18±1、湿度 70±5。
        draftStrategy("S1", "24.00", "18.00", "70.00", "1.0", "5.0");
        dispatch("S1", "REQ-D1", T0.minusSeconds(60));
        // 连续 3 点偏离（27℃ 超出白天带 [23,25]）恰在第 3 点触发告警，处置进行中。
        ingest(T0, "27.00", "70");
        ingest(T0.plusSeconds(60), "27.00", "70");
        alarmId = ingest(T0.plusSeconds(120), "27.00", "70").alarmId();
        assertThat(alarmId).isNotNull();
    }

    @Test
    @DisplayName("不兼容新策略挂起：不产生任何副作用，仲裁结论与状态推进同事务留档")
    void incompatibleStrategySuspendedWithoutSideEffects() {
        allocateBudget("2026-01-11", "10.000");
        // 昼带 [25,27] 不被告警策略带 [23,25] 包含 → 不兼容。
        draftStrategy("S2", "26.00", "18.00", "70.00", "1.0", "5.0");

        StrategyDtos.StrategyResponse response = dispatch("S2", "REQ-S2", NEXT);
        assertThat(response.status()).isEqualTo("SUSPENDED");

        // 无副作用：S2 不写生效时刻/请求号，S1 仍生效未失效，告警仍在处置，预算零消耗。
        StrategyRepository.Strategy s2 = strategyRepository.findByCode("S2").orElseThrow();
        assertThat(s2.effectiveTime()).isNull();
        assertThat(s2.requestId()).isNull();
        assertThat(s2.dispatchedAt()).isNull();
        StrategyRepository.Strategy s1 = strategyRepository.findByCode("S1").orElseThrow();
        assertThat(s1.status()).isEqualTo("DISPATCHED");
        assertThat(s1.expiredAt()).isNull();
        assertThat(alarmService.listActive(ZONE)).hasSize(1);
        assertThat(energyService.getBudget(ZONE, java.time.LocalDate.of(2026, 1, 11)).consumedKwh())
                .isEqualByComparingTo("0.000");
        Integer commands = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM control_command WHERE zone_code = ?", Integer.class, ZONE);
        assertThat(commands).isZero();

        // 仲裁结论与状态推进同一事务：挂起留档随事务提交可见。
        List<StrategyDtos.RehearsalResponse> rehearsals = strategyService.rehearsals("S2");
        assertThat(rehearsals).hasSize(1);
        assertThat(rehearsals.get(0).decision()).isEqualTo("SUSPENDED_ALARM");
        assertThat(rehearsals.get(0).reason()).contains("告警 #" + alarmId);

        // 同键同参重放返回首次挂起结果，不重复执行（留档仍只有一条）；同键异参 409。
        StrategyDtos.StrategyResponse replay = dispatch("S2", "REQ-S2", NEXT);
        assertThat(replay.status()).isEqualTo("SUSPENDED");
        assertThat(strategyService.rehearsals("S2")).hasSize(1);
        assertThatThrownBy(() -> dispatch("S2", "REQ-S2", NEXT.plusSeconds(3600)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("设定带相同（新带⊆旧带）的新策略与处置兼容，告警进行中仍可下发")
    void compatibleStrategyDispatchesDespiteActiveAlarm() {
        draftStrategy("S2", "24.00", "18.00", "70.00", "1.0", "5.0");
        StrategyDtos.StrategyResponse response = dispatch("S2", "REQ-COMPAT", NEXT);
        assertThat(response.status()).isEqualTo("DISPATCHED");
        assertThat(strategyRepository.findByCode("S1").orElseThrow().status()).isEqualTo("EXPIRED");
        assertThat(strategyService.rehearsals("S2").get(0).decision()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("挂起等待处置完结：告警确认后用新 requestId 重新下发成功")
    void suspendedStrategyDispatchesAfterHandlingCompleted() {
        draftStrategy("S2", "26.00", "18.00", "70.00", "1.0", "5.0");
        assertThat(dispatch("S2", "REQ-S2", NEXT).status()).isEqualTo("SUSPENDED");

        alarmService.acknowledge(new AlarmDtos.AckRequest(alarmId, "ACK-1"));
        assertThat(alarmService.listActive(ZONE)).isEmpty();

        StrategyDtos.StrategyResponse response = dispatch("S2", "REQ-S2-RETRY", NEXT);
        assertThat(response.status()).isEqualTo("DISPATCHED");
        assertThat(response.effectiveTime()).isEqualTo(NEXT);
        assertThat(strategyRepository.findByCode("S1").orElseThrow().status()).isEqualTo("EXPIRED");

        // 留档完整：先挂起后通过，两次结论均可查。
        assertThat(strategyService.rehearsals("S2"))
                .extracting(StrategyDtos.RehearsalResponse::decision)
                .containsExactly("SUSPENDED_ALARM", "APPROVED");
    }

    @Test
    @DisplayName("并发下发不兼容策略：都安全挂起且无副作用；处置完结后并发重发恰一个生效")
    void concurrentArbitrationIsSerialized() throws Exception {
        draftStrategy("S2", "26.00", "18.00", "70.00", "1.0", "5.0");
        draftStrategy("S3", "27.00", "19.00", "70.00", "1.0", "5.0");

        // 第一阶段：告警处置进行中，并发下发两个不兼容策略。
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<StrategyDtos.StrategyResponse>> futures = List.of(
                    pool.submit(() -> {
                        start.await();
                        return dispatch("S2", "REQ-P2", NEXT);
                    }),
                    pool.submit(() -> {
                        start.await();
                        return dispatch("S3", "REQ-P3", NEXT);
                    }));
            start.countDown();
            for (Future<StrategyDtos.StrategyResponse> f : futures) {
                assertThat(f.get(15, TimeUnit.SECONDS).status()).isEqualTo("SUSPENDED");
            }
        } finally {
            pool.shutdownNow();
        }
        // 均挂起且无副作用：S1 仍生效，无策略被失效，无指令入账。
        assertThat(strategyRepository.findByCode("S1").orElseThrow().status()).isEqualTo("DISPATCHED");
        Integer expired = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM climate_strategy WHERE zone_code = ? AND status = 'EXPIRED'",
                Integer.class, ZONE);
        assertThat(expired).isZero();
        assertThat(strategyService.rehearsals("S2")).hasSize(1);
        assertThat(strategyService.rehearsals("S3")).hasSize(1);

        // 第二阶段：处置完结，并发重发同一生效时刻，恰一个生效。
        alarmService.acknowledge(new AlarmDtos.AckRequest(alarmId, "ACK-2"));
        CountDownLatch start2 = new CountDownLatch(1);
        ExecutorService pool2 = Executors.newFixedThreadPool(2);
        int success = 0;
        int failed = 0;
        try {
            List<Future<?>> futures = List.of(
                    pool2.submit(() -> {
                        start2.await();
                        return dispatch("S2", "REQ-R2", NEXT);
                    }),
                    pool2.submit(() -> {
                        start2.await();
                        return dispatch("S3", "REQ-R3", NEXT);
                    }));
            start2.countDown();
            for (Future<?> f : futures) {
                try {
                    f.get(15, TimeUnit.SECONDS);
                    success++;
                } catch (Exception e) {
                    failed++;
                }
            }
        } finally {
            pool2.shutdownNow();
        }
        assertThat(success).isEqualTo(1);
        assertThat(failed).isEqualTo(1);
        Integer dispatched = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM climate_strategy WHERE zone_code = ? AND status = 'DISPATCHED'",
                Integer.class, ZONE);
        assertThat(dispatched).isEqualTo(1);
        assertThat(strategyRepository.findByCode("S1").orElseThrow().status()).isEqualTo("EXPIRED");
    }
}
