package com.example.starter.rosbridge.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.rosbridge.ros.SubscriptionKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * H2（MODE=MySQL）状态仓库事务测试：引用计数、单调游标、ACK 仅前进、
 * Service/Goal 的 PENDING 比较交换（迟到结果与重复取消幂等）。
 */
@SpringBootTest
@ActiveProfiles("test")
class GatewayRepositoryTest {

    @Autowired
    private GatewayRepository repository;

    private final SubscriptionKey key = new SubscriptionKey("/t", "std_msgs/String", "{}");

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void refcountIncrementDecrementAndGraceRowLifecycle() {
        repository.ensureSubscriptionRef(key);
        repository.ensureSubscriptionRef(key); // 幂等
        assertThat(repository.incrementRefcount(key)).isEqualTo(1);
        assertThat(repository.incrementRefcount(key)).isEqualTo(2);
        assertThat(repository.decrementRefcount(key, 123L)).isEqualTo(1);

        assertThat(repository.insertClientSubscriptionIfAbsent("c1", key, "s1", 1L)).isTrue();
        assertThat(repository.insertClientSubscriptionIfAbsent("c1", key, "s1", 1L)).isFalse();
        assertThat(repository.deleteClientSubscription("c1", key)).isTrue();
        assertThat(repository.deleteClientSubscription("c1", key)).isFalse();
        assertThat(repository.listSubscribers(key)).isEmpty();

        assertThat(repository.decrementRefcount(key, 123L)).isZero();
        assertThat(repository.deleteRefIfZero(key)).isTrue();
        assertThat(repository.deleteRefIfZero(key)).isFalse();
    }

    @Test
    void sharedCursorMonotonicAndAckMovesForwardOnly() {
        assertThat(repository.nextCursor(key)).isEqualTo(1);
        assertThat(repository.nextCursor(key)).isEqualTo(2);
        assertThat(repository.getLastCursor(key)).isEqualTo(2);

        assertThat(repository.ackCursor("c1", key, 2, 1L)).isEqualTo(2);
        assertThat(repository.ackCursor("c1", key, 1, 2L)).isEqualTo(2); // 不回退
        assertThat(repository.ackCursor("c1", key, 5, 3L)).isEqualTo(5);
        assertThat(repository.queryAck("c1", key)).isEqualTo(5);

        SubscriptionKey other = new SubscriptionKey("/t", "std_msgs/String", "{\"depth\":10}");
        assertThat(repository.nextCursor(other)).isEqualTo(1); // 不同 QoS 独立游标
        assertThat(repository.listCursorKeysForTopic("/t")).hasSize(2);
    }

    @Test
    void serviceCallCasCompletesOnlyOnce() {
        assertThat(repository.insertCallIfAbsent("c1", "r1", "/svc", 1L)).isTrue();
        assertThat(repository.insertCallIfAbsent("c1", "r1", "/svc", 1L)).isFalse();

        // 先到的结果完成
        assertThat(repository.casCall("c1", "r1", "COMPLETED", "{}", null, 2L)).isTrue();
        // 迟到结果/重复超时均失败，请求不会再次完成
        assertThat(repository.casCall("c1", "r1", "TIMEOUT", null, "timeout", 3L)).isFalse();
        assertThat(repository.casCall("c1", "r1", "COMPLETED", "{}", null, 4L)).isFalse();
        assertThat(repository.getCallStatus("c1", "r1")).isEqualTo("COMPLETED");
    }

    @Test
    void serviceCallTimeoutWinsThenLateResultFails() {
        repository.insertCallIfAbsent("c1", "r2", "/svc", 1L);
        assertThat(repository.casCall("c1", "r2", "TIMEOUT", null, "timeout", 2L)).isTrue();
        assertThat(repository.casCall("c1", "r2", "COMPLETED", "{}", null, 3L)).isFalse();
        assertThat(repository.getCallStatus("c1", "r2")).isEqualTo("TIMEOUT");
    }

    @Test
    void goalCancelAndResultAreIdempotent() {
        assertThat(repository.insertGoalIfAbsent("c1", "g1", "/act", 1L)).isTrue();
        assertThat(repository.insertGoalIfAbsent("c1", "g1", "/act", 1L)).isFalse();

        assertThat(repository.casGoal("c1", "g1", "CANCELLED", null, null, 2L)).isTrue();
        // 取消后适配器迟到的成功结果不能改变终态
        assertThat(repository.casGoal("c1", "g1", "SUCCEEDED", "{}", null, 3L)).isFalse();
        assertThat(repository.casGoal("c1", "g1", "CANCELLED", null, null, 4L)).isFalse();
        assertThat(repository.getGoalStatus("c1", "g1")).isEqualTo("CANCELLED");
    }

    @Test
    void goalSuccessThenCancelFails() {
        repository.insertGoalIfAbsent("c1", "g2", "/act", 1L);
        assertThat(repository.casGoal("c1", "g2", "SUCCEEDED", "{}", null, 2L)).isTrue();
        assertThat(repository.casGoal("c1", "g2", "CANCELLED", null, null, 3L)).isFalse();
        assertThat(repository.getGoalStatus("c1", "g2")).isEqualTo("SUCCEEDED");
    }
}
