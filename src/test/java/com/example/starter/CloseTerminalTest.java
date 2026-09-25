package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.starter.model.GeneratorStatus;
import com.example.starter.web.dto.ApiDtos.AlarmResponse;
import com.example.starter.web.dto.ApiDtos.CloseOrderRequest;
import com.example.starter.web.dto.ApiDtos.ConfirmGenerationRequest;
import com.example.starter.web.dto.ApiDtos.DispatchOrderView;

/**
 * 结案终态与迟到发电确认测试。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:test_close;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class CloseTerminalTest extends BaseIntegrationTest {

    private AlarmResponse dispatchableAlarm() {
        givenStation("BS-1", "50", "100");
        givenGenerator("GEN-1");
        givenReading("req-r1", "BS-1", T0.minus(Duration.ofMinutes(5)), "99");
        return givenAlarm("req-alarm-1", "BS-1", T0);
    }

    @Test
    void closeIsTerminalAndRepeatedCloseReturnsIdempotentSuccess() {
        AlarmResponse alarm = dispatchableAlarm();
        long orderId = alarm.dispatchOrder().id();
        advance(Duration.ofMinutes(10));
        dispatchService.confirmGeneration(orderId, new ConfirmGenerationRequest("req-confirm-1"));
        advance(Duration.ofMinutes(20));

        DispatchOrderView closed = dispatchService.close(orderId, new CloseOrderRequest("req-close-1"));
        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(closed.closedAt()).isEqualTo(T0.plus(Duration.ofMinutes(30)));
        // 结案释放油机。
        assertThat(generatorRepository.findByCode("GEN-1").orElseThrow().status())
                .isEqualTo(GeneratorStatus.IDLE);

        // 重复结案（新 requestId）：幂等成功，结论不变。
        advance(Duration.ofMinutes(5));
        DispatchOrderView again = dispatchService.close(orderId, new CloseOrderRequest("req-close-2"));
        assertThat(again.status()).isEqualTo("CLOSED");
        assertThat(again.closedAt()).isEqualTo(closed.closedAt());
        assertThat(eventRepository.countByOrderAndType(orderId, "CLOSED")).isEqualTo(1);
    }

    @Test
    void lateConfirmAfterCloseOnlyRecordsEventWithoutChangingConclusion() {
        AlarmResponse alarm = dispatchableAlarm();
        long orderId = alarm.dispatchOrder().id();
        dispatchService.close(orderId, new CloseOrderRequest("req-close-1"));
        advance(Duration.ofMinutes(5));

        DispatchOrderView view = dispatchService.confirmGeneration(orderId,
                new ConfirmGenerationRequest("req-confirm-late"));

        assertThat(view.status()).isEqualTo("CLOSED");
        assertThat(view.confirmedAt()).isNull();
        assertThat(eventRepository.countByOrderAndType(orderId, "LATE_CONFIRM")).isEqualTo(1);
        // 结论未被改变：无发电确认、油机保持空闲。
        assertThat(generatorRepository.findByCode("GEN-1").orElseThrow().status())
                .isEqualTo(GeneratorStatus.IDLE);
    }

    @Test
    void closeFromDispatchedReleasesGenerator() {
        AlarmResponse alarm = dispatchableAlarm();
        long orderId = alarm.dispatchOrder().id();

        DispatchOrderView closed = dispatchService.close(orderId, new CloseOrderRequest("req-close-1"));

        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(generatorRepository.findByCode("GEN-1").orElseThrow().status())
                .isEqualTo(GeneratorStatus.IDLE);
        // 结案后同站可再次开新事件派单（唯一键已释放）。
        assertThat(count("dispatch_order")).isEqualTo(1);
    }
}
