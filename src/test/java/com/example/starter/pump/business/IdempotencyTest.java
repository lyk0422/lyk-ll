package com.example.starter.pump.business;

import com.example.starter.pump.error.ApiException;
import com.example.starter.pump.service.PumpdownFacade;
import com.example.starter.pump.testsupport.AbstractIntegrationTest;
import com.example.starter.pump.web.dto.ArchiveRequest;
import com.example.starter.pump.web.dto.PumpCommandRequest;
import com.example.starter.pump.web.dto.WarningRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * requestId 幂等：同键同参返回首次结果；同键异参返回 409 且不改变泵站状态。
 */
class IdempotencyTest extends AbstractIntegrationTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 26, 8, 0);

    @Autowired
    private PumpdownFacade facade;

    @Test
    void warning_sameKeySameParams_replaysFirstResult() {
        var req = new WarningRequest("w-same", "ZN", "ORANGE", T0);
        var first = facade.handleWarning(req);
        var replay = facade.handleWarning(req);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.pumpdownId()).isEqualTo(first.pumpdownId());
        // 未重复创建预降事件
        assertThat(count("pumpdown_event")).isEqualTo(1);
    }

    @Test
    void warning_sameKeyDifferentParams_returns409_andChangesNothing() {
        facade.handleWarning(new WarningRequest("w-conf", "ZN", "ORANGE", T0));
        long eventsBefore = count("pumpdown_event");

        assertThatThrownBy(() -> facade.handleWarning(
                new WarningRequest("w-conf", "ZN", "RED", T0)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus().value()).isEqualTo(409);
                    assertThat(api.getCode()).isEqualTo("IDEMPOTENT_CONFLICT");
                });

        assertThat(count("pumpdown_event")).isEqualTo(eventsBefore);
    }

    @Test
    void pumpCommand_sameKeyReplays_differentParams409WithoutStateChange() {
        Long id = facade.handleWarning(new WarningRequest("w-p", "ZN", "ORANGE", T0)).pumpdownId();
        var startReq = new PumpCommandRequest("t-same", id, "PS-01", "START", T0, null);
        var first = facade.handlePumpCommand(startReq);
        var replay = facade.handlePumpCommand(startReq);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.taskId()).isEqualTo(first.taskId());
        assertThat(count("pump_task")).isEqualTo(1);

        long tasksBefore = count("pump_task");
        assertThatThrownBy(() -> facade.handlePumpCommand(
                new PumpCommandRequest("t-same", id, "PS-01", "STOP", T0, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("IDEMPOTENT_CONFLICT"));
        assertThat(count("pump_task")).isEqualTo(tasksBefore);
    }

    @Test
    void failedBusinessRequest_doesNotOccupyRequestId() {
        // 蓝色预警不触发但属于成功受理；这里构造非法级别导致业务失败，事务回滚不占位
        assertThatThrownBy(() -> facade.handleWarning(
                new WarningRequest("w-fail", "ZN", "PURPLE", T0)))
                .isInstanceOf(ApiException.class);

        // 同一 requestId 随后可正常受理（未被失败占位锁死）
        var ok = facade.handleWarning(new WarningRequest("w-fail", "ZN", "ORANGE", T0));
        assertThat(ok.triggered()).isTrue();
    }
}
