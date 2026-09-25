package com.example.starter.energy;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.api.dto.CommandDtos;
import com.example.starter.web.BusinessRuleException;
import com.example.starter.web.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 每日能耗预算：恰用尽只允许零能耗通风、并发入账不超预算、requestId 幂等与同键异参 409。
 */
class EnergyBudgetTest extends AbstractIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2026, 1, 4);
    private static final Instant T0 = DAY.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();

    @BeforeEach
    void setUp() {
        seedZone();
        allocateBudget(DAY.toString(), "10.000");
    }

    private CommandDtos.CommandResponse heat(String requestId, String kwh, long seconds) {
        return energyService.issueCommand(new CommandDtos.CommandRequest(
                requestId, ZONE, "HEAT", bd(kwh), T0.plusSeconds(seconds)));
    }

    private CommandDtos.CommandResponse ventilate(String requestId, long seconds) {
        return energyService.issueCommand(new CommandDtos.CommandRequest(
                requestId, ZONE, "VENTILATE", null, T0.plusSeconds(seconds)));
    }

    @Test
    @DisplayName("预算恰用尽：最后一份加热（累计恰10kWh）成功，之后加热被拒、通风零能耗仍成功")
    void exactBudgetExhaustionOnlyVentilationAllowedAfterwards() {
        CommandDtos.CommandResponse first = heat("REQ-H1", "6.000", 0);
        assertThat(first.remainingKwh()).isEqualByComparingTo("4.000");
        // 恰好用尽最后 4kWh。
        CommandDtos.CommandResponse second = heat("REQ-H2", "4.000", 60);
        assertThat(second.remainingKwh()).isEqualByComparingTo("0.000");

        assertThatThrownBy(() -> heat("REQ-H3", "0.001", 120))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("剩余预算");
        assertThatThrownBy(() -> energyService.issueCommand(new CommandDtos.CommandRequest(
                "REQ-L", ZONE, "SUPPLEMENT_LIGHT", bd("0.100"), T0.plusSeconds(180))))
                .isInstanceOf(BusinessRuleException.class);

        CommandDtos.CommandResponse vent = ventilate("REQ-V", 240);
        assertThat(vent.energyKwh()).isEqualByComparingTo("0.000");
        assertThat(vent.remainingKwh()).isEqualByComparingTo("0.000");

        CommandDtos.BudgetResponse budget = energyService.getBudget(ZONE, DAY);
        assertThat(budget.consumedKwh()).isEqualByComparingTo("10.000");
    }

    @Test
    @DisplayName("耗能指令缺正能耗被拒绝（400）")
    void energyCommandRequiresPositiveEnergy() {
        assertThatThrownBy(() -> energyService.issueCommand(new CommandDtos.CommandRequest(
                "REQ-X", ZONE, "HEAT", bd("0"), T0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("同 requestId 同参重放返回首次指令，不重复扣预算；异参返回 409")
    void commandIdempotency() {
        CommandDtos.CommandResponse first = heat("REQ-IDEM", "3.000", 0);
        CommandDtos.CommandResponse replay = heat("REQ-IDEM", "3.000", 0);
        assertThat(replay.commandId()).isEqualTo(first.commandId());
        assertThat(energyService.getBudget(ZONE, DAY).consumedKwh()).isEqualByComparingTo("3.000");

        assertThatThrownBy(() -> heat("REQ-IDEM", "4.000", 0))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("并发加热竞争剩余预算：恰有一方成功，另一方 422，最终消耗绝不超过预算")
    void concurrentCommandsNeverExceedBudget() throws Exception {
        // 剩余 10kWh，两个并发请求各要 7kWh，只允许一个成功。
        int threads = 2;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        heat("REQ-C" + idx, "7.000", idx + 1);
                        success.incrementAndGet();
                    } catch (BusinessRuleException e) {
                        rejected.incrementAndGet();
                    } catch (Exception e) {
                        // 唯一键/其他冲突同样视为未入账。
                        rejected.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(success.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(1);
        CommandDtos.BudgetResponse budget = energyService.getBudget(ZONE, DAY);
        assertThat(budget.consumedKwh()).isEqualByComparingTo("7.000");
        assertThat(budget.consumedKwh()).isLessThanOrEqualTo(budget.budgetKwh());
    }

    @Test
    @DisplayName("同 requestId 并发重放：只入账一次，两个调用都返回同一指令结果")
    void concurrentSameRequestIdConsumesOnce() throws Exception {
        int threads = 4;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            java.util.List<Future<CommandDtos.CommandResponse>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return heat("REQ-SAME", "2.500", 5);
                }));
            }
            start.countDown();
            Long firstId = null;
            for (Future<CommandDtos.CommandResponse> f : futures) {
                CommandDtos.CommandResponse r = f.get(20, TimeUnit.SECONDS);
                if (firstId == null) {
                    firstId = r.commandId();
                }
                assertThat(r.commandId()).isEqualTo(firstId);
            }
            assertThat(energyService.getBudget(ZONE, DAY).consumedKwh()).isEqualByComparingTo("2.500");
        } finally {
            pool.shutdownNow();
        }
    }
}
