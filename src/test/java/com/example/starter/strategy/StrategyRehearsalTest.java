package com.example.starter.strategy;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.api.dto.CommandDtos;
import com.example.starter.api.dto.StrategyDtos;
import com.example.starter.repo.RehearsalRepository;
import com.example.starter.service.RehearsalService;
import com.example.starter.web.BusinessRuleException;
import com.example.starter.web.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 下发前能耗预演：合成模型估算执行期能耗，超当日剩余预算拒绝、恰等允许、未分配预算不校验；
 * 预演留档随策略版本保存且可按同版本模型复算。
 */
class StrategyRehearsalTest extends AbstractIntegrationTest {

    /** 设定点 20/14/60 时模型速率恰为 0.05 kWh/h；正午生效执行期 12h，预估恰 0.600 kWh。 */
    private static final Instant NOON = Instant.parse("2026-01-10T12:00:00Z");
    private static final String DAY = "2026-01-10";

    @BeforeEach
    void setUp() {
        seedZone();
        seedCrop();
    }

    @Test
    @DisplayName("预估能耗恰等于当日剩余预算时允许下发，预演留档可复算")
    void exactBudgetAllowedAndRehearsalArchived() {
        allocateBudget(DAY, "0.600");
        draftStrategy("S1", "20.00", "14.00", "60.00", "0.5", "5");

        StrategyDtos.StrategyResponse response = dispatch("S1", "REQ-E1", NOON);
        assertThat(response.status()).isEqualTo("DISPATCHED");

        RehearsalRepository.Rehearsal rehearsal = rehearsalService.latest("S1");
        assertThat(rehearsal.verdict()).isEqualTo("PASS");
        assertThat(rehearsal.modelVersion()).isEqualTo(RehearsalService.MODEL_VERSION);
        assertThat(rehearsal.durationMinutes()).isEqualTo(720);
        assertThat(rehearsal.hourlyRateKwh()).isEqualByComparingTo("0.050");
        assertThat(rehearsal.estimatedKwh()).isEqualByComparingTo("0.600");
        assertThat(rehearsal.budgetKwh()).isEqualByComparingTo("0.600");
        assertThat(rehearsal.consumedKwh()).isEqualByComparingTo("0.000");
        assertThat(rehearsal.remainingKwh()).isEqualByComparingTo("0.600");
        assertThat(rehearsal.handlingConflict()).isNull();

        // 复算：同版本模型由留档输入重算，速率与预估能耗必须一致。
        BigDecimal rate = RehearsalService.hourlyRate(
                rehearsal.dayTemp(), rehearsal.nightTemp(), rehearsal.humiditySetpoint());
        assertThat(rate).isEqualByComparingTo(rehearsal.hourlyRateKwh());
        BigDecimal recomputed = rate.multiply(BigDecimal.valueOf(rehearsal.durationMinutes()))
                .divide(BigDecimal.valueOf(60), 3, java.math.RoundingMode.HALF_UP);
        assertThat(recomputed).isEqualByComparingTo(rehearsal.estimatedKwh());
    }

    @Test
    @DisplayName("预估能耗超过当日剩余预算直接拒绝：策略保持 VALIDATED，拒绝结论独立事务留档可查")
    void overBudgetRejectedAndArchived() {
        allocateBudget(DAY, "0.599");
        draftStrategy("S1", "20.00", "14.00", "60.00", "0.5", "5");

        assertThatThrownBy(() -> dispatch("S1", "REQ-E2", NOON))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("拒绝下发")
                .hasMessageContaining("0.600")
                .hasMessageContaining("0.599");

        // 拒绝不产生副作用：策略未下发、未写生效时刻。
        StrategyDtos.StrategyResponse strategy = strategyService.get("S1");
        assertThat(strategy.status()).isEqualTo("VALIDATED");
        assertThat(strategy.effectiveTime()).isNull();

        // 拒绝的预演结论仍留档可查（独立事务提交，不随业务回滚丢失）。
        RehearsalRepository.Rehearsal rehearsal = rehearsalService.latest("S1");
        assertThat(rehearsal.verdict()).isEqualTo("REJECTED");
        assertThat(rehearsal.estimatedKwh()).isEqualByComparingTo("0.600");
        assertThat(rehearsal.remainingKwh()).isEqualByComparingTo("0.599");

        // 拒绝后策略仍可下发：缩短执行期（23:00 生效，预估 0.050）即可通过。
        StrategyDtos.StrategyResponse late = dispatch("S1", "REQ-E2B",
                Instant.parse("2026-01-10T23:00:00Z"));
        assertThat(late.status()).isEqualTo("DISPATCHED");
    }

    @Test
    @DisplayName("当日已耗预算压缩剩余额度：剩余不足即拒绝")
    void consumedBudgetShrinksRemaining() {
        allocateBudget(DAY, "1.000");
        energyService.issueCommand(new CommandDtos.CommandRequest(
                "CMD-H1", ZONE, "HEAT", bd("0.500"), NOON.minusSeconds(60)));
        draftStrategy("S1", "20.00", "14.00", "60.00", "0.5", "5");

        // 剩余 0.500 < 预估 0.600，拒绝。
        assertThatThrownBy(() -> dispatch("S1", "REQ-E3", NOON))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("拒绝下发");
        RehearsalRepository.Rehearsal rehearsal = rehearsalService.latest("S1");
        assertThat(rehearsal.verdict()).isEqualTo("REJECTED");
        assertThat(rehearsal.consumedKwh()).isEqualByComparingTo("0.500");
        assertThat(rehearsal.remainingKwh()).isEqualByComparingTo("0.500");
    }

    @Test
    @DisplayName("当日未分配预算不校验能耗：最高设定点也放行，留档预算字段为空")
    void noBudgetSkipsEnergyCheck() {
        draftStrategy("S1", "28.00", "20.00", "80.00", "0.5", "5");
        StrategyDtos.StrategyResponse response = dispatch("S1", "REQ-E4",
                Instant.parse("2026-01-11T00:00:00Z"));
        assertThat(response.status()).isEqualTo("DISPATCHED");

        RehearsalRepository.Rehearsal rehearsal = rehearsalService.latest("S1");
        assertThat(rehearsal.verdict()).isEqualTo("PASS");
        assertThat(rehearsal.budgetKwh()).isNull();
        assertThat(rehearsal.remainingKwh()).isNull();
        // 速率 0.05+0.03*8+0.02*6+0.01*20 = 0.61 kWh/h，执行期 24h，预估 14.640 kWh。
        assertThat(rehearsal.hourlyRateKwh()).isEqualByComparingTo("0.610");
        assertThat(rehearsal.estimatedKwh()).isEqualByComparingTo("14.640");
    }

    @Test
    @DisplayName("预演下放幂等：同键同参重放返回首次结果且留档不重复，同键异参 409")
    void dispatchIdempotencyWithRehearsal() {
        allocateBudget(DAY, "0.600");
        draftStrategy("S1", "20.00", "14.00", "60.00", "0.5", "5");

        StrategyDtos.StrategyResponse first = dispatch("S1", "REQ-E5", NOON);
        StrategyDtos.StrategyResponse replay = dispatch("S1", "REQ-E5", NOON);
        assertThat(replay.status()).isEqualTo(first.status());
        assertThat(replay.effectiveTime()).isEqualTo(first.effectiveTime());

        Integer rehearsals = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM strategy_rehearsal WHERE strategy_code = 'S1'", Integer.class);
        assertThat(rehearsals).isEqualTo(1);

        assertThatThrownBy(() -> dispatch("S1", "REQ-E5", NOON.plusSeconds(3600)))
                .isInstanceOf(ConflictException.class);
    }
}
