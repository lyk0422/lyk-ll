package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.starter.model.GeneratorStatus;
import com.example.starter.service.BusinessException;
import com.example.starter.web.dto.ApiDtos.AlarmResponse;
import com.example.starter.web.dto.ApiDtos.ConfirmGenerationRequest;
import com.example.starter.web.dto.ApiDtos.DispatchOrderView;
import com.example.starter.web.dto.ApiDtos.ReassignRequest;

/**
 * 发电确认 30 分钟超时（恰满判超时）与改派原子性测试。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:test_timeout;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class TimeoutReassignTest extends BaseIntegrationTest {

    private AlarmResponse dispatchableAlarm() {
        givenStation("BS-1", "50", "100");
        givenGenerator("GEN-1");
        givenGenerator("GEN-2");
        givenReading("req-r1", "BS-1", T0.minus(Duration.ofMinutes(5)), "99");
        return givenAlarm("req-alarm-1", "BS-1", T0);
    }

    @Test
    void confirmWithinThirtyMinutesSucceeds() {
        AlarmResponse alarm = dispatchableAlarm();
        long orderId = alarm.dispatchOrder().id();
        // 29 分 59 秒：未超时。
        advance(Duration.ofMinutes(29).plusSeconds(59));

        DispatchOrderView view = dispatchService.confirmGeneration(orderId,
                new ConfirmGenerationRequest("req-confirm-1"));

        assertThat(view.status()).isEqualTo("GENERATING");
        assertThat(view.confirmedAt()).isEqualTo(T0.plus(Duration.ofMinutes(29).plusSeconds(59)));
        assertThat(generatorRepository.findByCode("GEN-1").orElseThrow().status())
                .isEqualTo(GeneratorStatus.ASSIGNED);
    }

    @Test
    void confirmAtExactlyThirtyMinutesEscalatesToAbnormal() {
        AlarmResponse alarm = dispatchableAlarm();
        long orderId = alarm.dispatchOrder().id();
        // 恰满 30 分钟：判超时。
        advance(Duration.ofMinutes(30));

        DispatchOrderView view = dispatchService.confirmGeneration(orderId,
                new ConfirmGenerationRequest("req-confirm-2"));

        assertThat(view.status()).isEqualTo("GENERATION_ABNORMAL");
        assertThat(view.generatorCode()).isNull();
        // 超时升级释放原油机。
        assertThat(generatorRepository.findByCode("GEN-1").orElseThrow().status())
                .isEqualTo(GeneratorStatus.IDLE);
        assertThat(eventRepository.countByOrderAndType(orderId, "ESCALATED")).isEqualTo(1);
    }

    @Test
    void reassignAfterTimeoutIsAtomicAndKeepsSingleGeneratorOnStation() {
        AlarmResponse alarm = dispatchableAlarm();
        long orderId = alarm.dispatchOrder().id();
        advance(Duration.ofMinutes(30));

        // 在途单恰满超时：改派请求在同一事务内完成升级与换机。
        DispatchOrderView view = dispatchService.reassign(orderId,
                new ReassignRequest("req-reassign-1", "GEN-2"));

        assertThat(view.status()).isEqualTo("DISPATCHED");
        assertThat(view.generatorCode()).isEqualTo("GEN-2");
        assertThat(view.assignedAt()).isEqualTo(T0.plus(Duration.ofMinutes(30)));
        // 原子性结果：原油机释放、新油机在役，同站只有一台油机在途服务。
        assertThat(generatorRepository.findByCode("GEN-1").orElseThrow().status())
                .isEqualTo(GeneratorStatus.IDLE);
        assertThat(generatorRepository.findByCode("GEN-2").orElseThrow().status())
                .isEqualTo(GeneratorStatus.ASSIGNED);
        Long busyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dispatch_order WHERE station_code = 'BS-1'"
                        + " AND busy_generator_key IS NOT NULL", Long.class);
        assertThat(busyCount).isEqualTo(1);
        assertThat(eventRepository.countByOrderAndType(orderId, "ESCALATED")).isEqualTo(1);
        assertThat(eventRepository.countByOrderAndType(orderId, "REASSIGNED")).isEqualTo(1);
    }

    @Test
    void reassignIsRejectedWhenOrderNotAbnormal() {
        AlarmResponse alarm = dispatchableAlarm();
        long orderId = alarm.dispatchOrder().id();
        // 未超时（在途 10 分钟）：不允许改派。
        advance(Duration.ofMinutes(10));

        assertThatThrownBy(() -> dispatchService.reassign(orderId,
                new ReassignRequest("req-reassign-2", "GEN-2")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).code())
                .isEqualTo("ORDER_NOT_REASSIGNABLE");
        // 状态不变：原单仍在途，油机占用不变。
        DispatchOrderView view = dispatchService.getOrder(orderId);
        assertThat(view.status()).isEqualTo("DISPATCHED");
        assertThat(view.generatorCode()).isEqualTo("GEN-1");
        assertThat(generatorRepository.findByCode("GEN-2").orElseThrow().status())
                .isEqualTo(GeneratorStatus.IDLE);
    }

    @Test
    void reassignToBusyGeneratorIsRejectedAndOrderStaysAbnormal() {
        AlarmResponse alarm = dispatchableAlarm();
        long orderId = alarm.dispatchOrder().id();
        // GEN-2 被另一基站占用。
        givenStation("BS-2", "50", "100");
        givenReading("req-r2", "BS-2", T0.minus(Duration.ofMinutes(5)), "99");
        AlarmResponse other = givenAlarm("req-alarm-2", "BS-2", T0);
        assertThat(other.dispatchOrder().generatorCode()).isEqualTo("GEN-2");

        advance(Duration.ofMinutes(30));
        dispatchService.confirmGeneration(orderId, new ConfirmGenerationRequest("req-confirm-3"));

        assertThatThrownBy(() -> dispatchService.reassign(orderId,
                new ReassignRequest("req-reassign-3", "GEN-2")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).code())
                .isEqualTo("GENERATOR_BUSY");
        // 改派失败不改变调度状态：仍为发电异常且无在役油机。
        DispatchOrderView view = dispatchService.getOrder(orderId);
        assertThat(view.status()).isEqualTo("GENERATION_ABNORMAL");
        assertThat(view.generatorCode()).isNull();
    }

    @Test
    void getOrderLazilyEscalatesTimedOutOrder() {
        AlarmResponse alarm = dispatchableAlarm();
        long orderId = alarm.dispatchOrder().id();
        advance(Duration.ofMinutes(31));

        DispatchOrderView view = dispatchService.getOrder(orderId);

        assertThat(view.status()).isEqualTo("GENERATION_ABNORMAL");
        assertThat(generatorRepository.findByCode("GEN-1").orElseThrow().status())
                .isEqualTo(GeneratorStatus.IDLE);
    }
}
