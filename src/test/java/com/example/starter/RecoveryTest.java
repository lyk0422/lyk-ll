package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.starter.model.GeneratorStatus;
import com.example.starter.service.BusinessException;
import com.example.starter.web.dto.ApiDtos.AlarmResponse;
import com.example.starter.web.dto.ApiDtos.ConfirmGenerationRequest;
import com.example.starter.web.dto.ApiDtos.DispatchOrderView;
import com.example.starter.web.dto.ApiDtos.MainsRecoveryRequest;
import com.example.starter.web.dto.ApiDtos.RecoveryResponse;

/**
 * 市电恢复主流程及“恢复 × 油机启动确认”并发竞态测试。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:test_recovery;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class RecoveryTest extends BaseIntegrationTest {

    private AlarmResponse dispatchableAlarm() {
        givenStation("BS-1", "50", "100");
        givenGenerator("GEN-1");
        givenReading("req-r1", "BS-1", T0.minus(Duration.ofMinutes(5)), "99");
        return givenAlarm("req-alarm-1", "BS-1", T0);
    }

    @Test
    void recoveryResolvesEventAndReleasesOrderAndGenerator() {
        AlarmResponse alarm = dispatchableAlarm();
        long orderId = alarm.dispatchOrder().id();
        advance(Duration.ofMinutes(20));

        RecoveryResponse response = alarmService.mainsRecovery(
                new MainsRecoveryRequest("req-rec-1", "BS-1", now));

        assertThat(response.resolved()).isTrue();
        assertThat(response.eventId()).isEqualTo(alarm.eventId());
        assertThat(response.releasedOrderIds()).containsExactly(orderId);
        DispatchOrderView view = dispatchService.getOrder(orderId);
        assertThat(view.status()).isEqualTo("RELEASED");
        assertThat(view.generatorCode()).isNull();
        assertThat(generatorRepository.findByCode("GEN-1").orElseThrow().status())
                .isEqualTo(GeneratorStatus.IDLE);
        // 事件已恢复，可再次开新事件。
        Long activeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outage_event WHERE status = 'ACTIVE'", Long.class);
        assertThat(activeCount).isZero();
    }

    @Test
    void recoveryWithoutActiveOutageIsRejected() {
        givenStation("BS-2", "50", "100");

        assertThatThrownBy(() -> alarmService.mainsRecovery(
                new MainsRecoveryRequest("req-rec-2", "BS-2", now)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).code())
                .isEqualTo("NO_ACTIVE_OUTAGE");
    }

    @Test
    void concurrentRecoveryAndConfirmProduceSingleTerminalState() throws Exception {
        AlarmResponse alarm = dispatchableAlarm();
        long orderId = alarm.dispatchOrder().id();
        // 确认窗口内（10 分钟），恢复与启动确认并发。
        advance(Duration.ofMinutes(10));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<?> recovery = executor.submit(() -> {
                ready.countDown();
                awaitQuietly(go);
                alarmService.mainsRecovery(new MainsRecoveryRequest("req-rec-race", "BS-1", now));
                return null;
            });
            Future<?> confirm = executor.submit(() -> {
                ready.countDown();
                awaitQuietly(go);
                dispatchService.confirmGeneration(orderId,
                        new ConfirmGenerationRequest("req-confirm-race"));
                return null;
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            recovery.get(30, TimeUnit.SECONDS);
            confirm.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        // 唯一终态：调度单已解除、油机已释放，不得既解除又保持发电。
        DispatchOrderView view = dispatchService.getOrder(orderId);
        assertThat(view.status()).isEqualTo("RELEASED");
        assertThat(view.generatorCode()).isNull();
        assertThat(generatorRepository.findByCode("GEN-1").orElseThrow().status())
                .isEqualTo(GeneratorStatus.IDLE);
        // 确认动作只生效一次：要么先确认后被解除，要么迟到确认仅记事件。
        assertThat(eventRepository.countByOrderAndType(orderId, "CONFIRMED")
                + eventRepository.countByOrderAndType(orderId, "LATE_CONFIRM")).isEqualTo(1);
        assertThat(eventRepository.countByOrderAndType(orderId, "RELEASED")).isEqualTo(1);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
