package com.example.starter.service;

import com.example.starter.testsupport.ForwarderTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 网络恢复后的补发顺序：优先级从高到低，同级按设备本地序号升序。 */
class FlushOrderingTest extends ForwarderTestBase {

    @BeforeEach
    void goOffline() {
        service.setOnline(false);
    }

    @Test
    void flushesByPriorityDescThenSeqAsc() {
        // 两个设备交错写入，验证全局排序而非按设备分组
        service.ingest("dev-A", "/t", 2, 1, "a-2", null);
        service.ingest("dev-A", "/t", 1, 1, "a-1", null);
        service.ingest("dev-B", "/t", 1, 9, "b-1", null);
        service.ingest("dev-B", "/t", 2, 9, "b-2", null);
        service.ingest("dev-A", "/t", 3, 5, "a-3", null);

        // 仅切换链路状态（不经过 service.setOnline 的自动补发），手动触发以精确断言顺序
        upstream.setOnline(true);
        int sent = service.flush();

        assertThat(sent).isEqualTo(5);
        assertThat(upstream.dispatchedKeys()).containsExactly(
                "b-1", "b-2", "a-3", "a-1", "a-2");
        assertThat(messageRepository.findAll()).allSatisfy(m ->
                assertThat(m.status()).isEqualTo("IN_FLIGHT"));
    }

    @Test
    void noDispatchWhileOffline() {
        service.ingest("dev-A", "/t", 1, 5, "off-1", null);

        assertThat(service.flush()).isZero();
        assertThat(upstream.dispatchCount()).isZero();
        assertThat(messageRepository.findAll()).singleElement().satisfies(m ->
                assertThat(m.status()).isEqualTo("PENDING"));
    }

    @Test
    void setOnlineTriggersAutomaticFlush() {
        service.ingest("dev-A", "/t", 1, 5, "auto-1", null);
        service.ingest("dev-A", "/t", 2, 5, "auto-2", null);

        service.setOnline(true);

        assertThat(upstream.dispatchedKeys()).containsExactly("auto-1", "auto-2");
    }

    @Test
    void dispatchFailureRevertsToPendingForRetry() {
        service.ingest("dev-A", "/t", 1, 5, "jitter-1", null);
        // 恢复瞬间链路抖动：底层切在线，手动补发
        upstream.setOnline(true);
        upstream.setFailureRate(1.0);

        assertThat(service.flush()).isZero();
        assertThat(messageRepository.findAll()).singleElement().satisfies(m -> {
            assertThat(m.status()).isEqualTo("PENDING");
            assertThat(m.sentAt()).isNull();
        });

        // 链路稳定后下一轮补发成功
        upstream.setFailureRate(0);
        int sent = service.flush();
        assertThat(sent).isEqualTo(1);
        assertThat(upstream.dispatchedKeys()).containsExactly("jitter-1");
    }
}
