package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.starter.service.BusinessException;
import com.example.starter.web.dto.ApiDtos.AlarmResponse;
import com.example.starter.web.dto.ApiDtos.BatteryReadingResponse;
import com.example.starter.web.dto.ApiDtos.ConfirmGenerationRequest;
import com.example.starter.web.dto.ApiDtos.DispatchOrderView;
import com.example.starter.web.dto.ApiDtos.MainsRecoveryRequest;
import com.example.starter.web.dto.ApiDtos.RecoveryResponse;

/**
 * requestId 幂等测试：同键同参返回首次结果，同键异参 409 且不改变调度状态。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:test_idempotency;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class IdempotencyTest extends BaseIntegrationTest {

    private AlarmResponse dispatchableAlarm(String requestId) {
        givenStation("BS-1", "50", "100");
        givenGenerator("GEN-1");
        givenReading("req-r1", "BS-1", T0.minus(Duration.ofMinutes(5)), "99");
        return givenAlarm(requestId, "BS-1", T0);
    }

    @Test
    void sameKeySameParamsReturnsFirstResult() {
        AlarmResponse first = dispatchableAlarm("req-alarm-1");

        AlarmResponse replay = givenAlarm("req-alarm-1", "BS-1", T0);

        assertThat(replay).isEqualTo(first);
        assertThat(replay.dispatchOrder().id()).isEqualTo(first.dispatchOrder().id());
        // 重放不产生新数据。
        assertThat(count("outage_event")).isEqualTo(1);
        assertThat(count("dispatch_order")).isEqualTo(1);
        assertThat(count("idempotency_record")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void sameKeyDifferentParamsIsRejectedWithoutStateChange() {
        AlarmResponse first = dispatchableAlarm("req-alarm-2");

        // 同 requestId 携带不同停电起始时刻：409，调度状态不变。
        BusinessException conflict = catchThrowableOfType(
                () -> givenAlarm("req-alarm-2", "BS-1", T0.plus(Duration.ofMinutes(1))),
                BusinessException.class);
        assertThat(conflict.code()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(conflict.status().value()).isEqualTo(409);

        assertThat(count("outage_event")).isEqualTo(1);
        assertThat(count("dispatch_order")).isEqualTo(1);
        DispatchOrderView view = dispatchService.getOrder(first.dispatchOrder().id());
        assertThat(view.status()).isEqualTo("DISPATCHED");
        assertThat(view.generatorCode()).isEqualTo("GEN-1");
    }

    @Test
    void confirmGenerationIsIdempotent() {
        AlarmResponse alarm = dispatchableAlarm("req-alarm-3");
        long orderId = alarm.dispatchOrder().id();
        advance(Duration.ofMinutes(5));

        DispatchOrderView first = dispatchService.confirmGeneration(orderId,
                new ConfirmGenerationRequest("req-confirm-1"));
        DispatchOrderView replay = dispatchService.confirmGeneration(orderId,
                new ConfirmGenerationRequest("req-confirm-1"));

        assertThat(first.status()).isEqualTo("GENERATING");
        assertThat(replay).isEqualTo(first);
        assertThat(eventRepository.countByOrderAndType(orderId, "CONFIRMED")).isEqualTo(1);
    }

    @Test
    void readingIsIdempotentByRequestId() {
        givenStation("BS-2", "50", "100");
        BatteryReadingResponse first = givenReading("req-r9", "BS-2", T0, "80");

        BatteryReadingResponse replay = givenReading("req-r9", "BS-2", T0, "80");

        assertThat(replay).isEqualTo(first);
        assertThat(replay.deduplicated()).isFalse();
        assertThat(count("battery_reading")).isEqualTo(1);
    }

    @Test
    void recoveryIsIdempotentAndSecondRealRecoveryIsRejected() {
        AlarmResponse alarm = dispatchableAlarm("req-alarm-4");
        advance(Duration.ofMinutes(10));

        RecoveryResponse first = alarmService.mainsRecovery(
                new MainsRecoveryRequest("req-rec-1", "BS-1", now));
        RecoveryResponse replay = alarmService.mainsRecovery(
                new MainsRecoveryRequest("req-rec-1", "BS-1", now));

        assertThat(replay).isEqualTo(first);
        assertThat(replay.releasedOrderIds()).containsExactly(alarm.dispatchOrder().id());
        // 事件已恢复，新的恢复请求（不同 requestId）按业务规则拒绝。
        assertThatThrownBy(() -> alarmService.mainsRecovery(
                new MainsRecoveryRequest("req-rec-2", "BS-1", now)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).code())
                .isEqualTo("NO_ACTIVE_OUTAGE");
    }

    private static <T extends Throwable> T catchThrowableOfType(Runnable runnable,
                                                                Class<T> type) {
        try {
            runnable.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
            throw t;
        }
        throw new AssertionError("未抛出预期异常: " + type.getSimpleName());
    }
}
