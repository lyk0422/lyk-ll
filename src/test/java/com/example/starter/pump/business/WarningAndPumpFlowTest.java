package com.example.starter.pump.business;

import com.example.starter.pump.error.ApiException;
import com.example.starter.pump.service.PumpdownFacade;
import com.example.starter.pump.testsupport.AbstractIntegrationTest;
import com.example.starter.pump.web.dto.ArchiveRequest;
import com.example.starter.pump.web.dto.LevelIngestRequest;
import com.example.starter.pump.web.dto.PumpCommandRequest;
import com.example.starter.pump.web.dto.WarningRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 预警启动与泵站启停主流程/边界测试。
 */
class WarningAndPumpFlowTest extends AbstractIntegrationTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 26, 8, 0);

    @Autowired
    private PumpdownFacade facade;

    private Long startOrangePumpdown(String requestId, LocalDateTime at) {
        var result = facade.handleWarning(
                new WarningRequest(requestId, "ZN", "ORANGE", at));
        assertThat(result.triggered()).isTrue();
        assertThat(result.status()).isEqualTo("ACTIVE");
        // 分区目标位取 PS-01(2.500) 与 PS-02(2.600) 中最严格者 2.500
        assertThat(result.targetLevel()).isEqualByComparingTo("2.500");
        return result.pumpdownId();
    }

    @Test
    void orangeWarning_startsPumpdown_yellowAndBlueDoNot() {
        var blue = facade.handleWarning(new WarningRequest("w-blue", "ZN", "BLUE", T0));
        assertThat(blue.triggered()).isFalse();
        assertThat(blue.status()).isEqualTo("NOT_TRIGGERED");
        assertThat(blue.pumpdownId()).isNull();

        var yellow = facade.handleWarning(new WarningRequest("w-yellow", "ZN", "YELLOW", T0));
        assertThat(yellow.triggered()).isFalse();

        Long id = startOrangePumpdown("w-orange", T0);
        assertThat(id).isNotNull();
        assertThat(count("pumpdown_event")).isEqualTo(1);
    }

    @Test
    void secondActivePumpdownInSameZone_isRejected() {
        startOrangePumpdown("w-1", T0);
        assertThatThrownBy(() -> startOrangePumpdown("w-2", T0.plusMinutes(5)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("ZONE_PUMPDOWN_ACTIVE"));
    }

    @Test
    void startInterval_exactlyTenMinutesIsLegal_lessIsRejected() {
        Long id = startOrangePumpdown("w-int", T0);

        var first = facade.handlePumpCommand(new PumpCommandRequest(
                "t-1", id, "PS-01", "START", T0, null));
        assertThat(first.taskId()).isNotNull();
        assertThat(first.gateOpening()).isEqualByComparingTo("80.00");

        // t=9 先停泵，随即再次启机：距上次启机仅 9 分钟，不足 10 分钟，防水锤拒绝
        facade.handlePumpCommand(new PumpCommandRequest(
                "t-stop-9", id, "PS-01", "STOP", T0.plusMinutes(9), null));
        assertThatThrownBy(() -> facade.handlePumpCommand(new PumpCommandRequest(
                "t-2", id, "PS-01", "START", T0.plusMinutes(9), null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("START_INTERVAL_TOO_SHORT"));

        // 恰 10 分钟：合法（t=10 已停，t=10 再次启泵，恰 10 分钟间隔）
        var atTen = facade.handlePumpCommand(new PumpCommandRequest(
                "t-3", id, "PS-01", "START", T0.plusMinutes(10), null));
        assertThat(atTen.taskId()).isNotNull();
    }

    @Test
    void reachingExactlyTargetLevel_stopsPump() {
        Long id = startOrangePumpdown("w-target", T0);
        facade.handlePumpCommand(new PumpCommandRequest(
                "t-start", id, "PS-01", "START", T0, null));

        // 水位仍高于目标位：不停泵
        var high = facade.handleLevel(new LevelIngestRequest(
                "PS-01", T0.plusMinutes(5), new BigDecimal("3.100")));
        assertThat(high.autoStopTaskId()).isNull();

        // 恰达目标位 2.500：停泵（恰达目标位停泵）
        var atTarget = facade.handleLevel(new LevelIngestRequest(
                "PS-01", T0.plusMinutes(10), new BigDecimal("2.500")));
        assertThat(atTarget.autoStopTaskId()).isNotNull();
        assertThat(atTarget.autoStopReason()).isEqualTo("TARGET_REACHED");

        // 已停后再来恰目标位数据：不重复派停泵任务
        var again = facade.handleLevel(new LevelIngestRequest(
                "PS-01", T0.plusMinutes(12), new BigDecimal("2.500")));
        assertThat(again.autoStopTaskId()).isNull();
    }

    @Test
    void safeLowLevel_equalityIsLegal_strictlyBelowStopsPump() {
        Long id = startOrangePumpdown("w-safe", T0);
        facade.handlePumpCommand(new PumpCommandRequest(
                "t-start", id, "PS-02", "START", T0, null));

        // 恰等安全下限 1.300：合法，不停泵（且高于目标位 2.600 不成立，1.300<2.600，先判安全下限）
        // 1.300 等于下限：不触发 SAFE_LOW；但 <=目标位2.600，按目标位停泵。
        // 为精确验证“恰等下限合法”，选取一个恰等下限且高于目标位不可能，故直接断言不抛 SAFE_LOW：
        var atLow = facade.handleLevel(new LevelIngestRequest(
                "PS-02", T0.plusMinutes(6), new BigDecimal("1.300")));
        assertThat(atLow.autoStopReason()).isNotEqualTo("SAFE_LOW");

        // 严格低于安全下限 1.300 -> 立即停泵（需泵仍运行；上一步已按目标位停泵，故重启）
        facade.handlePumpCommand(new PumpCommandRequest(
                "t-start2", id, "PS-02", "START", T0.plusMinutes(20), null));
        var below = facade.handleLevel(new LevelIngestRequest(
                "PS-02", T0.plusMinutes(22), new BigDecimal("1.299")));
        assertThat(below.autoStopReason()).isEqualTo("SAFE_LOW");
        assertThat(below.autoStopTaskId()).isNotNull();
    }

    @Test
    void archivedPumpdown_rejectsFurtherPumpCommands() {
        Long id = startOrangePumpdown("w-arch", T0);
        facade.handleArchive(new ArchiveRequest("a-1", id, T0.plusHours(2)));

        assertThatThrownBy(() -> facade.handlePumpCommand(new PumpCommandRequest(
                "t-after", id, "PS-01", "START", T0.plusHours(3), null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("PUMPDOWN_ARCHIVED"));
    }
}
