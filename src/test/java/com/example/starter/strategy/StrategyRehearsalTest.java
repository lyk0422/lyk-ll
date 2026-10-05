package com.example.starter.strategy;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.api.dto.CommandDtos;
import com.example.starter.api.dto.StrategyDtos;
import com.example.starter.repo.StrategyRepository;
import com.example.starter.web.BusinessRuleException;
import com.example.starter.web.ConflictException;
import com.example.starter.web.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 下发前预演：合成能耗模型估算执行期能耗，超过当日剩余预算直接拒绝（恰等允许），
 * 未分配预算不拦截；预演结果随策略版本留档可复算，拒绝留档在业务回滚后依然可查。
 */
class StrategyRehearsalTest extends AbstractIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2026, 1, 10);
    private static final Instant T0 = DAY.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
    /** 中午生效：执行期恰 720 分钟。 */
    private static final Instant NOON = T0.plusSeconds(12 * 3600);

    @Autowired
    private StrategyRepository strategyRepository;

    @BeforeEach
    void setUp() {
        seedZone();
        seedCrop();
    }

    @Test
    @DisplayName("估算恰等当日剩余预算允许下发，预演留档 APPROVED 且可复算")
    void exactRemainingBudgetAllowsDispatch() {
        // 24/18℃ 策略：时均负荷 = 0.200 + 0.050×(21−18) = 0.350kW；720 分钟 → 恰 4.200kWh。
        allocateBudget(DAY.toString(), "4.200");
        draftStrategy("S1", "24.00", "18.00", "70.00", "1.0", "5.0");

        StrategyDtos.StrategyResponse response = dispatch("S1", "REQ-EXACT", NOON);
        assertThat(response.status()).isEqualTo("DISPATCHED");

        List<StrategyDtos.RehearsalResponse> rehearsals = strategyService.rehearsals("S1");
        assertThat(rehearsals).hasSize(1);
        StrategyDtos.RehearsalResponse rehearsal = rehearsals.get(0);
        assertThat(rehearsal.decision()).isEqualTo("APPROVED");
        assertThat(rehearsal.execMinutes()).isEqualTo(720);
        // 复算：0.350kW × 720/60h = 4.200kWh，与留档一致。
        assertThat(rehearsal.estimatedKwh()).isEqualByComparingTo("4.200");
        assertThat(rehearsal.budgetKwh()).isEqualByComparingTo("4.200");
        assertThat(rehearsal.remainingKwh()).isEqualByComparingTo("4.200");
        assertThat(rehearsal.budgetDate()).isEqualTo(DAY);
    }

    @Test
    @DisplayName("估算超当日剩余预算直接拒绝：策略保持 VALIDATED，拒绝留档在业务回滚后仍可查")
    void overBudgetRejectedAndArchived() {
        allocateBudget(DAY.toString(), "4.199");
        draftStrategy("S1", "24.00", "18.00", "70.00", "1.0", "5.0");

        assertThatThrownBy(() -> dispatch("S1", "REQ-OVER", NOON))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("超过当日剩余预算");

        // 拒绝无状态推进：策略仍为 VALIDATED，分区无生效策略。
        StrategyRepository.Strategy strategy = strategyRepository.findByCode("S1").orElseThrow();
        assertThat(strategy.status()).isEqualTo("VALIDATED");
        assertThat(strategy.effectiveTime()).isNull();
        assertThat(strategyRepository.findActiveByZone(ZONE)).isEmpty();

        // 拒绝结论独立事务留档：业务回滚后预演结果仍随策略版本可查。
        List<StrategyDtos.RehearsalResponse> rehearsals = strategyService.rehearsals("S1");
        assertThat(rehearsals).hasSize(1);
        assertThat(rehearsals.get(0).decision()).isEqualTo("REJECTED_BUDGET");
        assertThat(rehearsals.get(0).estimatedKwh()).isEqualByComparingTo("4.200");
        assertThat(rehearsals.get(0).remainingKwh()).isEqualByComparingTo("4.199");

        // 同键同参可重试（首次失败已释放占位），仍因预算不足被拒；同键异参 409。
        assertThatThrownBy(() -> dispatch("S1", "REQ-OVER", NOON))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> dispatch("S1", "REQ-OVER", NOON.plusSeconds(3600)))
                .isInstanceOf(ConflictException.class);
        // 两次真实执行各留一档。
        assertThat(strategyService.rehearsals("S1")).hasSize(2);
    }

    @Test
    @DisplayName("剩余预算按已消耗扣减：指令入账后剩余不足时拒绝下发")
    void consumedBudgetReducesRemaining() {
        allocateBudget(DAY.toString(), "10.000");
        energyService.issueCommand(new CommandDtos.CommandRequest(
                "CMD-H", ZONE, "HEAT", bd("6.000"), T0.plusSeconds(1)));
        draftStrategy("S1", "24.00", "18.00", "70.00", "1.0", "5.0");

        // 剩余 4.000kWh < 估算 4.200kWh。
        assertThatThrownBy(() -> dispatch("S1", "REQ-REM", NOON))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("超过当日剩余预算");
        assertThat(strategyService.rehearsals("S1").get(0).remainingKwh())
                .isEqualByComparingTo("4.000");
    }

    @Test
    @DisplayName("当日未分配预算不拦截下发，留档预算字段为空")
    void missingBudgetDoesNotBlock() {
        draftStrategy("S1", "24.00", "18.00", "70.00", "1.0", "5.0");
        StrategyDtos.StrategyResponse response = dispatch("S1", "REQ-NOBUDGET", T0);
        assertThat(response.status()).isEqualTo("DISPATCHED");

        StrategyDtos.RehearsalResponse rehearsal = strategyService.rehearsals("S1").get(0);
        assertThat(rehearsal.decision()).isEqualTo("APPROVED");
        assertThat(rehearsal.execMinutes()).isEqualTo(1440);
        // 复算：0.350kW × 24h = 8.400kWh。
        assertThat(rehearsal.estimatedKwh()).isEqualByComparingTo("8.400");
        assertThat(rehearsal.budgetKwh()).isNull();
        assertThat(rehearsal.remainingKwh()).isNull();
    }

    @Test
    @DisplayName("预演结果查询接口：按策略返回留档；策略不存在返回 404")
    void rehearsalQueryEndpoint() {
        draftStrategy("S1", "24.00", "18.00", "70.00", "1.0", "5.0");
        assertThat(strategyService.rehearsals("S1")).isEmpty();
        dispatch("S1", "REQ-Q", T0);
        assertThat(strategyService.rehearsals("S1")).hasSize(1);

        assertThatThrownBy(() -> strategyService.rehearsals("NOPE"))
                .isInstanceOf(NotFoundException.class);
    }
}
