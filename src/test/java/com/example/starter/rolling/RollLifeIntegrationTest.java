package com.example.starter.rolling;

import com.example.starter.rolling.api.dto.RollActualView;
import com.example.starter.rolling.api.dto.RollFreezeRequest;
import com.example.starter.rolling.api.dto.RollLedgerView;
import com.example.starter.rolling.api.dto.RollMountRequest;
import com.example.starter.rolling.service.BusinessException;
import com.example.starter.rolling.service.ConflictException;
import com.example.starter.rolling.service.RollLifeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 轧辊寿命台账：实绩入库与里程同事务、恰达上限禁排、超限拒收、缺号补录、
 * 去重归位、上机拒绝、首用标记、日冻结迟到事件、幂等与台账复算。
 */
class RollLifeIntegrationTest extends AbstractRollingIntegrationTest {

    @Autowired
    private RollLifeService rollService;

    @Test
    void 实绩入库与累计里程更新同事务完成() {
        seedRoll("R1", "10", "0", 0, true, false);

        RollActualView view = rollService.reportActual(rollActual("ra-1", "R1", "3.5", 0));

        assertThat(view.accepted()).isTrue();
        assertThat(view.totalKm()).isEqualByComparingTo("3.5");
        assertThat(view.remainingKm()).isEqualByComparingTo("6.5");
        assertThat(view.banned()).isFalse();
        // 同事务：实绩行与台账里程同时可见。
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM roll_change_actual WHERE roll_no = 'R1'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT total_km FROM roll_ledger WHERE roll_no = 'R1'", BigDecimal.class))
                .isEqualByComparingTo("3.5");
    }

    @Test
    void 累计里程恰达周期上限立即禁排() {
        seedRoll("R2", "10", "6", 3, true, false);

        RollActualView atLimit = rollService.reportActual(rollActual("ra-2", "R2", "4", 0));

        assertThat(atLimit.accepted()).isTrue();
        assertThat(atLimit.totalKm()).isEqualByComparingTo("10");
        assertThat(atLimit.remainingKm()).isEqualByComparingTo("0");
        assertThat(atLimit.banned()).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT banned FROM roll_ledger WHERE roll_no = 'R2'", Boolean.class)).isTrue();
    }

    @Test
    void 超限实绩不得入库且里程不变() {
        seedRoll("R3", "10", "6", 3, true, false);

        assertThatThrownBy(() -> rollService.reportActual(rollActual("ra-3", "R3", "4.001", 0)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("超限实绩不得入库")
                .hasMessageContaining("剩余 4");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM roll_change_actual WHERE roll_no = 'R3'", Integer.class))
                .isEqualTo(0);
        assertThat(jdbc.queryForObject(
                "SELECT total_km FROM roll_ledger WHERE roll_no = 'R3'", BigDecimal.class))
                .isEqualByComparingTo("6");
        assertThat(jdbc.queryForObject(
                "SELECT coils_used FROM roll_ledger WHERE roll_no = 'R3'", Integer.class))
                .isEqualTo(3);
    }

    @Test
    void 实绩缺轧辊号直接拒绝并提示补录且不增加里程() {
        seedRoll("R4", "10", "2", 1, true, false);

        assertThatThrownBy(() -> rollService.reportActual(rollActual("ra-4", null, "1", 0)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("补录");
        assertThatThrownBy(() -> rollService.reportActual(rollActual("ra-4b", "  ", "1", 0)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("补录");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM roll_change_actual", Integer.class)).isEqualTo(0);
        assertThat(jdbc.queryForObject(
                "SELECT total_km FROM roll_ledger WHERE roll_no = 'R4'", BigDecimal.class))
                .isEqualByComparingTo("2");
    }

    @Test
    void 换辊实绩按轧辊号与时刻去重() {
        seedRoll("R5", "10", "0", 0, true, false);

        RollActualView first = rollService.reportActual(rollActual("ra-5", "R5", "3", 1));
        RollActualView dup = rollService.reportActual(rollActual("ra-5-replay", "R5", "3", 1));

        assertThat(first.accepted()).isTrue();
        assertThat(dup.accepted()).isFalse();
        assertThat(dup.rollingKm()).isEqualByComparingTo("3");
        assertThat(dup.totalKm()).isEqualByComparingTo("3");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM roll_change_actual WHERE roll_no = 'R5'", Integer.class))
                .isEqualTo(1);
        // 里程只累计一次。
        assertThat(jdbc.queryForObject(
                "SELECT total_km FROM roll_ledger WHERE roll_no = 'R5'", BigDecimal.class))
                .isEqualByComparingTo("3");
    }

    @Test
    void 乱序回传按实绩时刻归位() {
        seedRoll("R6", "10", "0", 0, true, false);

        rollService.reportActual(rollActual("ra-6-late", "R6", "2", 5));
        rollService.reportActual(rollActual("ra-6-early", "R6", "1", 1));
        rollService.reportActual(rollActual("ra-6-mid", "R6", "3", 3));

        var actuals = rollService.listActuals("R6");
        assertThat(actuals).extracting(a -> a.recordedAt().getHour()).containsExactly(9, 11, 13);
        assertThat(actuals).extracting(a -> a.rollingKm().stripTrailingZeros().toPlainString())
                .containsExactly("1", "3", "2");
        // 台账累计与乱序无关。
        assertThat(rollService.getLedger("R6").totalKm()).isEqualByComparingTo("6");
    }

    @Test
    void 寿命到期轧辊申请上机直接拒绝并给出剩余里程() {
        seedRoll("R7", "10", "10", 8, false, true);

        assertThatThrownBy(() -> rollService.mount(new RollMountRequest("mt-1", "R7")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("寿命到期")
                .hasMessageContaining("剩余里程 0");

        assertThat(jdbc.queryForObject(
                "SELECT on_machine FROM roll_ledger WHERE roll_no = 'R7'", Boolean.class)).isFalse();
    }

    @Test
    void 新辊上机后前5卷加首用标记() {
        seedRoll("R8", "10", "0", 0, false, false);

        RollLedgerView mounted = rollService.mount(new RollMountRequest("mt-2", "R8"));
        assertThat(mounted.onMachine()).isTrue();
        assertThat(mounted.firstUseRemaining()).isEqualTo(5);

        for (int i = 1; i <= 6; i++) {
            RollActualView view = rollService.reportActual(
                    rollActual("ra-8-" + i, "R8", "1", i));
            assertThat(view.firstUse()).as("第 %d 卷首用标记", i).isEqualTo(i <= 5);
        }
        RollLedgerView ledger = rollService.getLedger("R8");
        assertThat(ledger.coilsUsed()).isEqualTo(6);
        assertThat(ledger.firstUseRemaining()).isEqualTo(0);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM roll_change_actual WHERE roll_no = 'R8' AND first_use = 1",
                Integer.class)).isEqualTo(5);
    }

    @Test
    void 日冻结后迟到实绩只记事件不改已冻结里程() {
        seedRoll("R9", "10", "4", 2, true, false);
        rollService.reportActual(rollActual("ra-9", "R9", "2", 0));
        rollService.freezeAll(new RollFreezeRequest("fz-1"));

        RollActualView late = rollService.reportActual(rollActual("ra-9-late", "R9", "3", 1));

        assertThat(late.late()).isTrue();
        assertThat(late.accepted()).isFalse();
        assertThat(late.rollingKm()).isNull();
        assertThat(late.totalKm()).isEqualByComparingTo("6");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM roll_late_event WHERE roll_no = 'R9'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM roll_change_actual WHERE roll_no = 'R9'", Integer.class))
                .isEqualTo(1);
        // 已冻结里程与禁排结论不变。
        RollLedgerView ledger = rollService.getLedger("R9");
        assertThat(ledger.totalKm()).isEqualByComparingTo("6");
        assertThat(ledger.banned()).isFalse();
        assertThat(ledger.frozenAt()).isNotNull();
    }

    @Test
    void 迟到实绩同键重放幂等且不重复登记事件() {
        seedRoll("R10", "10", "0", 0, true, false);
        rollService.freezeAll(new RollFreezeRequest("fz-2"));

        var req = rollActual("ra-10", "R10", "1", 0);
        RollActualView first = rollService.reportActual(req);
        RollActualView replay = rollService.reportActual(req);

        assertThat(first.late()).isTrue();
        assertThat(replay.late()).isTrue();
        assertThat(replay).isEqualTo(first);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM roll_late_event WHERE roll_no = 'R10'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void 同键同参返回首次结果同键异参409() {
        seedRoll("R11", "10", "0", 0, true, false);

        RollActualView first = rollService.reportActual(rollActual("ra-11", "R11", "2", 0));
        RollActualView replay = rollService.reportActual(rollActual("ra-11", "R11", "2", 0));
        assertThat(replay).isEqualTo(first);

        assertThatThrownBy(() -> rollService.reportActual(rollActual("ra-11", "R11", "3", 0)))
                .isInstanceOf(ConflictException.class);
        // 异参拒绝不产生任何写入。
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM roll_change_actual WHERE roll_no = 'R11'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT total_km FROM roll_ledger WHERE roll_no = 'R11'", BigDecimal.class))
                .isEqualByComparingTo("2");
    }

    @Test
    void 台账可复算且重复查询稳定() {
        seedRoll("R12", "10", "0", 0, true, false);
        rollService.reportActual(rollActual("ra-12-1", "R12", "1.5", 0));
        rollService.reportActual(rollActual("ra-12-2", "R12", "2.5", 1));

        RollLedgerView first = rollService.getLedger("R12");
        RollLedgerView second = rollService.getLedger("R12");

        assertThat(second).isEqualTo(first);
        assertThat(first.totalKm()).isEqualByComparingTo("4");
        // 台账里程可由实绩逐条复算。
        assertThat(first.recomputedTotalKm()).isEqualByComparingTo(first.totalKm());
        assertThat(jdbc.queryForObject(
                "SELECT COALESCE(SUM(rolling_km),0) FROM roll_change_actual WHERE roll_no = 'R12'",
                BigDecimal.class)).isEqualByComparingTo(first.totalKm());
    }

    @Test
    void 台账不存在返回404() {
        assertThatThrownBy(() -> rollService.getLedger("NOPE"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> rollService.reportActual(rollActual("ra-x", "NOPE", "1", 0)))
                .isInstanceOf(BusinessException.class);
    }
}
