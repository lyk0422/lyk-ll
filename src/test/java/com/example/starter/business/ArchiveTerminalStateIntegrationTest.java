package com.example.starter.business;

import com.example.starter.domain.ValveAction;
import com.example.starter.dto.ArchiveResponse;
import com.example.starter.dto.TemperatureReportResponse;
import com.example.starter.service.BalanceService;
import com.example.starter.testsupport.AbstractIntegrationTest;
import com.example.starter.testsupport.Requests;
import com.example.starter.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 归档终态测试：重复归档幂等成功；归档后拒绝调节/评估；迟到温度只记迟到事件。
 */
class ArchiveTerminalStateIntegrationTest extends AbstractIntegrationTest {

    private static final String STATION = "ST-T";
    private static final List<String> UNITS = List.of("ST-T-U01", "ST-T-U02", "ST-T-U03");

    @Autowired
    private BalanceService balanceService;

    @Test
    void archiveIsTerminal_repeatArchiveIdempotent_lateTemperatureRecordedAsEvent() {
        createStation(STATION, 3);
        balanceService.reportTemperature(Requests.stationReadings(
                "t-temp-1", STATION, UNITS, List.of("44.0", "45.0", "46.0"), clock.instant()));
        balanceService.adjust(Requests.adjust("t-adj-1", STATION,
                Requests.step(UNITS.get(0), ValveAction.OPEN, "2")));
        ArchiveResponse first = balanceService.archive(Requests.archive("t-arc-1", STATION));
        assertThat(first.archived()).isTrue();
        // active_station_id 已释放，状态为终态
        String status = jdbc.queryForObject("SELECT status FROM adjustment_task", String.class);
        Long active = jdbc.queryForObject("SELECT active_station_id FROM adjustment_task", Long.class);
        assertThat(status).isEqualTo("ARCHIVED");
        assertThat(active).isNull();

        // 同一 requestId 重复归档：幂等成功
        ArchiveResponse replay = balanceService.archive(Requests.archive("t-arc-1", STATION));
        assertThat(replay.replay()).isTrue();
        assertThat(replay.archivedAt()).isEqualTo(first.archivedAt());

        // 新 requestId 重复归档：同样成功（终态幂等），不产生第二个任务
        ArchiveResponse again = balanceService.archive(Requests.archive("t-arc-2", STATION));
        assertThat(again.archived()).isTrue();
        assertThat(again.archivedAt()).isEqualTo(first.archivedAt());
        assertThat(count("adjustment_task")).isEqualTo(1);

        // 归档后不能再调节、再评估
        assertThatThrownBy(() -> balanceService.adjust(Requests.adjust("t-adj-after", STATION,
                Requests.step(UNITS.get(0), ValveAction.OPEN, "1"))))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        assertThatThrownBy(() -> balanceService.evaluate(
                Requests.evaluate("t-eval-after", STATION, "JUDGMENT")))
                .isInstanceOf(ApiException.class);
        // 失败的调节未改开度、未建任务
        assertThat(valveOpening(STATION, UNITS.get(0))).isEqualByComparingTo("52.000");
        assertThat(count("adjustment_task")).isEqualTo(1);

        // 归档后迟到温度：只记迟到事件，不进温度表
        long readingsBefore = count("temperature_reading");
        TemperatureReportResponse late = balanceService.reportTemperature(Requests.stationReadings(
                "t-temp-late", STATION, UNITS, List.of("40.0", "40.0", "40.0"), clock.instant()));
        assertThat(late.late()).isTrue();
        assertThat(late.accepted()).isZero();
        assertThat(count("temperature_reading")).isEqualTo(readingsBefore);
        assertThat(count("late_temperature_event")).isEqualTo(3);

        // 迟到事件以同一 requestId 重放，不重复落事件
        TemperatureReportResponse lateReplay = balanceService.reportTemperature(Requests.stationReadings(
                "t-temp-late", STATION, UNITS, List.of("40.0", "40.0", "40.0"), clock.instant()));
        assertThat(lateReplay.replay()).isTrue();
        assertThat(count("late_temperature_event")).isEqualTo(3);
    }

    @Test
    void archiveWithoutTask_isRejected() {
        createStation(STATION, 3);
        assertThatThrownBy(() -> balanceService.archive(Requests.archive("t-arc-empty", STATION)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("尚无调节任务");
        assertThat(count("idempotent_request")).isZero();
    }
}
