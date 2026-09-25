package com.example.starter;

import com.example.starter.domain.AdjustmentTask;
import com.example.starter.service.BalanceService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 失衡判定：温差不超过 2℃ 判平衡（恰 2℃ 合格），失衡单元分欠流/过流。
 */
class BalanceServiceTest extends IntegrationTestBase {

    @Test
    void 温差恰2摄氏度判定为平衡() {
        submitReadings(40.00, 42.00, 41.00, 40.50);

        BalanceService.BalanceView view = balanceService.evaluateBalance(STATION_ID, clock.instant());

        assertThat(view.diff()).isEqualByComparingTo(new BigDecimal("2.00"));
        assertThat(view.balanced()).isTrue();
    }

    @Test
    void 温差超过2摄氏度判定为失衡并区分欠流过流() {
        submitReadings(40.00, 42.50, 41.00, 41.00);

        BalanceService.BalanceView view = balanceService.evaluateBalance(STATION_ID, clock.instant());

        assertThat(view.diff()).isEqualByComparingTo(new BigDecimal("2.50"));
        assertThat(view.balanced()).isFalse();
        assertThat(view.units())
                .filteredOn(u -> u.unitId() == 1L)
                .singleElement()
                .extracting(BalanceService.UnitTemperature::classification)
                .isEqualTo(AdjustmentTask.ImbalanceType.UNDERFLOW);
        assertThat(view.units())
                .filteredOn(u -> u.unitId() == 2L)
                .singleElement()
                .extracting(BalanceService.UnitTemperature::classification)
                .isEqualTo(AdjustmentTask.ImbalanceType.OVERFLOW);
    }

    @Test
    void 温度分布按最新采集时刻取值可复算() {
        submitReadings(40.00, 42.00, 41.00, 40.00);
        clock.advance(java.time.Duration.ofHours(1));
        submitReadings(41.00, 41.20, 41.10, 41.00);

        BalanceService.BalanceView view = balanceService.evaluateBalance(STATION_ID, clock.instant());

        assertThat(view.diff()).isEqualByComparingTo(new BigDecimal("0.20"));
        assertThat(view.balanced()).isTrue();
    }
}
