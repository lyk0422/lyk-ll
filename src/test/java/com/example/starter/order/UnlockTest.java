package com.example.starter.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.error.ApiException;
import com.example.starter.order.OrderDtos.UnlockRequest;
import com.example.starter.support.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 紧急解锁测试：双人授权、全程留痕、解锁与闭锁恢复同事务原子完成。
 */
class UnlockTest extends BaseIntegrationTest {

    @Test
    void 紧急解锁须双人授权() {
        long orderId = draftReviewIssue("unlock-auth", "INT-1101",
                step(1, "BR-1101", "OPEN_BREAKER", "OFF"));

        assertThatThrownBy(() -> orderService.unlock(orderId,
                new UnlockRequest("授权人甲", "授权人甲", "DIS-1101", "单人授权")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("DUAL_AUTH_REQUIRED");
        assertThatThrownBy(() -> orderService.unlock(orderId,
                new UnlockRequest("授权人甲", null, "DIS-1101", "缺少第二授权人")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("DUAL_AUTH_REQUIRED");
        assertThat(count("unlock_record", "1 = 1")).isZero();
    }

    @Test
    void 解锁与闭锁恢复同事务完成_全程留痕() {
        long orderId = draftReviewIssue("unlock-ok", "INT-1101",
                step(1, "BR-1101", "OPEN_BREAKER", "OFF"));

        var view = orderService.unlock(orderId,
                new UnlockRequest("授权人甲", "授权人乙", "DIS-1101", "刀闸机构卡涩紧急处理"));
        assertThat(view.unlockedAt()).isNotNull();
        assertThat(view.restoredAt()).isNotNull();

        // 稳态：闭锁已恢复
        Boolean unlocked = jdbc.queryForObject(
                "SELECT unlocked FROM device_state WHERE device_id = 'DIS-1101'",
                Boolean.class);
        assertThat(unlocked).isFalse();
        // 留痕：解锁记录与事件均可复算
        assertThat(count("unlock_record", "order_id = ? AND device_id = 'DIS-1101'", orderId))
                .isEqualTo(1);
        var trace = orderService.trace(orderId);
        assertThat(trace.unlocks()).hasSize(1);
        assertThat(trace.unlocks().get(0).authorizedBy1()).isEqualTo("授权人甲");
        assertThat(trace.unlocks().get(0).authorizedBy2()).isEqualTo("授权人乙");
        assertThat(trace.events()).extracting("eventType")
                .contains("UNLOCK", "INTERLOCK_RESTORED");
    }

    @Test
    void 解锁失败整体回滚_不得只解锁不恢复() {
        long orderId = draftReviewIssue("unlock-rollback", "INT-1101",
                step(1, "BR-1101", "OPEN_BREAKER", "OFF"));

        // 设备不存在：解锁失败，事务整体回滚
        assertThatThrownBy(() -> orderService.unlock(orderId,
                new UnlockRequest("授权人甲", "授权人乙", "DIS-9999", "设备不存在")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(count("unlock_record", "1 = 1")).isZero();
        assertThat(count("order_event",
                "order_id = ? AND event_type IN ('UNLOCK','INTERLOCK_RESTORED')", orderId))
                .isZero();
    }

    @Test
    void 同票同设备重复解锁冲突_不产生第二条记录() {
        long orderId = draftReviewIssue("unlock-dup", "INT-1101",
                step(1, "BR-1101", "OPEN_BREAKER", "OFF"));
        orderService.unlock(orderId,
                new UnlockRequest("授权人甲", "授权人乙", "DIS-1101", "首次解锁"));

        assertThatThrownBy(() -> orderService.unlock(orderId,
                new UnlockRequest("授权人甲", "授权人乙", "DIS-1101", "重复解锁")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("UNLOCK_EXISTS");
        assertThat(count("unlock_record", "order_id = ?", orderId)).isEqualTo(1);
        Boolean unlocked = jdbc.queryForObject(
                "SELECT unlocked FROM device_state WHERE device_id = 'DIS-1101'",
                Boolean.class);
        assertThat(unlocked).isFalse();
    }
}
