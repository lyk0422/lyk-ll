package com.example.starter.service;

import com.example.starter.domain.BaseStation;
import com.example.starter.testsupport.AbstractIntegrationTest;
import com.example.starter.testsupport.Fixtures;
import com.example.starter.web.ConflictException;
import com.example.starter.web.UnprocessableException;
import com.example.starter.web.dto.AlertRequest;
import com.example.starter.web.dto.AlertResponse;
import com.example.starter.web.dto.DispatchRequest;
import com.example.starter.web.dto.DispatchResponse;
import com.example.starter.web.dto.ReadingRequest;
import com.example.starter.web.dto.ReadingResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 主流程与失败分支：2 小时阈值、告警合并、抄表乱序归位、requestId 幂等同参/异参。
 */
class BackupPowerCoreTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private BackupPowerService service;
    @Autowired
    private IdempotencyService idempotency;
    @Autowired
    private Fixtures fixtures;

    @Test
    void alert_is_merged_when_same_station_and_same_outage_start() {
        fixtures.generator("GEN-1");
        fixtures.station("BS-1", "3000", "2000");

        AlertResponse first = service.handleAlert(
                new AlertRequest("REQ-A1", "BS-1", T0));
        AlertResponse repeated = service.handleAlert(
                new AlertRequest("REQ-A2", "BS-1", T0));

        assertThat(first.merged()).isFalse();
        assertThat(repeated.merged()).isTrue();
        assertThat(repeated.eventId()).isEqualTo(first.eventId());
        assertThat(count("outage_event")).isEqualTo(1);
    }

    @Test
    void alerts_with_different_outage_start_create_separate_events() {
        fixtures.station("BS-1", "3000", "2000");

        service.handleAlert(new AlertRequest("REQ-A1", "BS-1", T0));
        service.handleAlert(new AlertRequest("REQ-A2", "BS-1", T0.plusSeconds(3600)));

        assertThat(count("outage_event")).isEqualTo(2);
    }

    @Test
    void dispatch_created_when_support_time_below_two_hours() {
        fixtures.generator("GEN-1");
        BaseStation station = fixtures.station("BS-1", "3000", "2000");
        service.handleAlert(new AlertRequest("REQ-A1", "BS-1", T0));
        // 剩余 2000Wh / 2000W = 60 分钟 < 120 分钟。
        service.handleReading(new ReadingRequest("REQ-R1", "BS-1", T0, new BigDecimal("2000")));

        DispatchResponse response = service.handleDispatch(
                new DispatchRequest("REQ-D1", "BS-1", T0, null));

        assertThat(response.created()).isTrue();
        assertThat(response.estimatedMinutes()).isEqualTo(60);
        assertThat(response.order().status()).isEqualTo("DISPATCHED");
        assertThat(response.order().genCode()).isEqualTo("GEN-1");
        // 到达时限为 UTC 绝对时刻。
        assertThat(response.order().arrivalDeadlineUtc()).isEqualTo(T0.plusSeconds(3600));
    }

    @Test
    void dispatch_not_created_when_support_time_is_exactly_two_hours() {
        fixtures.generator("GEN-1");
        fixtures.station("BS-1", "4000", "2000");
        service.handleAlert(new AlertRequest("REQ-A1", "BS-1", T0));
        // 4000Wh / 2000W = 恰好 120 分钟。
        service.handleReading(new ReadingRequest("REQ-R1", "BS-1", T0, new BigDecimal("4000")));

        DispatchResponse response = service.handleDispatch(
                new DispatchRequest("REQ-D1", "BS-1", T0, null));

        assertThat(response.created()).isFalse();
        assertThat(response.estimatedMinutes()).isEqualTo(120);
        assertThat(response.reason()).isEqualTo("SUPPORT_TIME_SUFFICIENT");
        assertThat(count("dispatch_order")).isZero();
    }

    @Test
    void dispatch_not_created_when_support_time_above_two_hours() {
        fixtures.generator("GEN-1");
        fixtures.station("BS-1", "6000", "2000");
        service.handleAlert(new AlertRequest("REQ-A1", "BS-1", T0));
        service.handleReading(new ReadingRequest("REQ-R1", "BS-1", T0, new BigDecimal("6000")));

        DispatchResponse response = service.handleDispatch(
                new DispatchRequest("REQ-D1", "BS-1", T0, null));

        assertThat(response.created()).isFalse();
        assertThat(response.estimatedMinutes()).isEqualTo(180);
        assertThat(count("dispatch_order")).isZero();
    }

    @Test
    void second_dispatch_for_same_station_rejected_while_first_active() {
        fixtures.generator("GEN-1");
        fixtures.generator("GEN-2");
        fixtures.station("BS-1", "3000", "2000");
        service.handleAlert(new AlertRequest("REQ-A1", "BS-1", T0));
        service.handleReading(new ReadingRequest("REQ-R1", "BS-1", T0, new BigDecimal("1000")));
        service.handleDispatch(new DispatchRequest("REQ-D1", "BS-1", T0, null));

        assertThatThrownBy(() -> service.handleDispatch(
                new DispatchRequest("REQ-D2", "BS-1", T0, "GEN-2")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("未完成调度单");
        assertThat(count("dispatch_order")).isEqualTo(1);
    }

    @Test
    void readings_dedup_by_station_and_read_time_and_reorder_by_read_time() {
        BaseStation station = fixtures.station("BS-1", "3000", "2000");

        // 乱序上报：先报 00:10，再报 00:05。
        ReadingResponse later = service.handleReading(
                new ReadingRequest("REQ-R1", "BS-1", T0.plusSeconds(600), new BigDecimal("1800")));
        ReadingResponse earlier = service.handleReading(
                new ReadingRequest("REQ-R2", "BS-1", T0.plusSeconds(300), new BigDecimal("2400")));

        assertThat(later.latest()).isTrue();
        assertThat(earlier.latest()).isFalse();
        // 当前剩余容量以抄表时刻最新者为准（乱序归位），而非后入库的 2400。
        BigDecimal current = jdbc.queryForObject(
                "SELECT current_remaining_wh FROM base_station WHERE id = ?",
                BigDecimal.class, station.id());
        assertThat(current).isEqualByComparingTo("1800");

        // 同基站同抄表时刻重复上报：去重，不新增记录。
        ReadingResponse duplicate = service.handleReading(
                new ReadingRequest("REQ-R3", "BS-1", T0.plusSeconds(300), new BigDecimal("2400")));
        assertThat(duplicate.deduped()).isTrue();
        assertThat(count("capacity_reading")).isEqualTo(2);
    }

    @Test
    void dispatch_without_reading_is_rejected() {
        fixtures.generator("GEN-1");
        fixtures.station("BS-1", "3000", "2000");
        service.handleAlert(new AlertRequest("REQ-A1", "BS-1", T0));

        assertThatThrownBy(() -> service.handleDispatch(
                new DispatchRequest("REQ-D1", "BS-1", T0, null)))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("容量读数");
    }

    @Test
    void same_request_id_same_params_replays_first_result() {
        fixtures.generator("GEN-1");
        fixtures.station("BS-1", "3000", "2000");
        service.handleAlert(new AlertRequest("REQ-A1", "BS-1", T0));
        service.handleReading(new ReadingRequest("REQ-R1", "BS-1", T0, new BigDecimal("1000")));

        DispatchRequest request = new DispatchRequest("REQ-IDEM", "BS-1", T0, null);
        IdempotencyService.Outcome first = idempotency.execute(
                "REQ-IDEM", "DISPATCH", request,
                () -> service.handleDispatch(request));
        IdempotencyService.Outcome replayed = idempotency.execute(
                "REQ-IDEM", "DISPATCH", request,
                () -> service.handleDispatch(request));

        assertThat(first.replayed()).isFalse();
        assertThat(replayed.replayed()).isTrue();
        assertThat(replayed.status()).isEqualTo(200);
        // 重放不重复派单。
        assertThat(count("dispatch_order")).isEqualTo(1);
    }

    @Test
    void same_request_id_different_params_conflicts_without_state_change() {
        fixtures.generator("GEN-1");
        fixtures.station("BS-1", "3000", "2000");
        service.handleAlert(new AlertRequest("REQ-A1", "BS-1", T0));
        service.handleReading(new ReadingRequest("REQ-R1", "BS-1", T0, new BigDecimal("1000")));

        DispatchRequest request = new DispatchRequest("REQ-IDEM", "BS-1", T0, null);
        idempotency.execute("REQ-IDEM", "DISPATCH", request, () -> service.handleDispatch(request));

        DispatchRequest changed = new DispatchRequest("REQ-IDEM", "BS-1", T0, "GEN-1");
        assertThatThrownBy(() -> idempotency.execute(
                "REQ-IDEM", "DISPATCH", changed, () -> service.handleDispatch(changed)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("不同业务参数");

        assertThat(count("dispatch_order")).isEqualTo(1);
        // 异参冲突不改变首次响应快照。
        Integer status = jdbc.queryForObject(
                "SELECT http_status FROM idempotent_request WHERE request_id = 'REQ-IDEM'",
                Integer.class);
        assertThat(status).isEqualTo(200);
    }
}
