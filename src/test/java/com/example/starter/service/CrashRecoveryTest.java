package com.example.starter.service;

import com.example.starter.StarterApplication;
import com.example.starter.domain.AuditType;
import com.example.starter.repo.AuditRepository;
import com.example.starter.repo.DeviceMessageRepository;
import com.example.starter.upstream.SyntheticUpstreamClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨进程恢复：两个独立的 Spring 上下文（模拟两次进程启动）共用临时目录下同一 H2 文件库。
 * 第一次进程崩溃（未 ACK 直接关闭），第二次进程启动后必须：
 * <ol>
 *   <li>读出持久化的待发消息；</li>
 *   <li>把遗留 IN_FLIGHT 重置为 PENDING 并重新补发；</li>
 *   <li>审计记录跨进程保留。</li>
 * </ol>
 */
class CrashRecoveryTest {

    @TempDir
    Path tempDir;

    private String dbUrl() {
        // 转正斜杠：Windows 临时目录反斜杠在命令行参数中也保持字面量，正斜杠可被 H2/Java 接受
        return ("jdbc:h2:file:" + tempDir.resolve("forwarder-crash")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE").replace('\\', '/');
    }

    /**
     * 启动独立进程上下文。命令行参数优先级高于 application.yaml，
     * 确保使用本用例的临时文件库而非工作目录默认库。
     *
     * @param initiallyOnline 新进程启动时模拟链路是否在线（网络状态不持久化）
     */
    private ConfigurableApplicationContext startContext(boolean initiallyOnline) {
        return new SpringApplicationBuilder(StarterApplication.class).run(
                "--spring.datasource.url=" + dbUrl(),
                "--spring.datasource.username=sa",
                "--spring.datasource.password=",
                "--spring.sql.init.mode=always",
                "--forwarder.capacity=1000",
                "--forwarder.in-flight-timeout-ms=30000",
                "--forwarder.flush-batch-size=100",
                "--forwarder.flush-interval-ms=600000",
                "--forwarder.upstream.initially-online=" + initiallyOnline,
                "--server.port=0");
    }

    @Test
    void inflightMessagesFromDeadProcessAreRequeuedAndResent() {
        // ---- 第一次进程：在线写入两条，发送后不 ACK，模拟崩溃 ----
        ConfigurableApplicationContext first = startContext(true);
        try {
            ForwarderService service1 = first.getBean(ForwarderService.class);
            service1.ingest("dev-A", "/t", 1, 5, "crash-1", null);
            service1.ingest("dev-A", "/t", 2, 5, "crash-2", null);
            assertThat(first.getBean(SyntheticUpstreamClient.class).dispatchedKeys())
                    .containsExactly("crash-1", "crash-2");
            // 再 ACK 一条：该消息不应在重启后复活
            assertThat(service1.acknowledge("crash-1").outcome()).isEqualTo("DELIVERED");
        } finally {
            first.close();
        }

        // ---- 第二次进程：启动恢复应重置 crash-2 并重新补发 ----
        ConfigurableApplicationContext second = startContext(true);
        try {
            DeviceMessageRepository messages = second.getBean(DeviceMessageRepository.class);
            AuditRepository audits = second.getBean(AuditRepository.class);
            SyntheticUpstreamClient upstream2 =
                    second.getBean(SyntheticUpstreamClient.class);

            // crash-1 已 ACK，不会复活；crash-2 恢复后立即重发，重新进入 in-flight
            assertThat(messages.findByDedupKey("crash-1")).isEmpty();
            assertThat(messages.findByDedupKey("crash-2")).isPresent();
            assertThat(upstream2.dispatchedKeys()).containsExactly("crash-2");

            // 恢复审计跨进程可见
            assertThat(audits.findByType(AuditType.RETRY_RESET)).hasSize(1);
            assertThat(audits.findByType(AuditType.RETRY_RESET).get(0).dedupKey())
                    .isEqualTo("crash-2");
            assertThat(audits.countByType(AuditType.DELIVERED)).isEqualTo(1);

            // 重启后仍可正常确认
            ForwarderService service2 = second.getBean(ForwarderService.class);
            assertThat(service2.acknowledge("crash-2").outcome()).isEqualTo("DELIVERED");
            assertThat(messages.findAll()).isEmpty();
        } finally {
            second.close();
        }
    }

    @Test
    void pendingOfflineMessagesSurviveRestartAndFlushInOrderAfterRecovery() {
        // 第一次进程以离线启动，写入两条 PENDING 后崩溃
        ConfigurableApplicationContext first = startContext(false);
        try {
            ForwarderService service1 = first.getBean(ForwarderService.class);
            service1.ingest("dev-A", "/t", 2, 1, "p-low-2", null);
            service1.ingest("dev-A", "/t", 1, 9, "p-hi-1", null);
        } finally {
            first.close();
        }

        // 第二次进程同样以离线启动：RecoveryRunner 不得提前补发
        ConfigurableApplicationContext second = startContext(false);
        try {
            ForwarderService service2 = second.getBean(ForwarderService.class);
            DeviceMessageRepository messages = second.getBean(DeviceMessageRepository.class);

            // 离线期间的 PENDING 完整跨进程保留
            assertThat(messages.findAll()).hasSize(2);
            assertThat(messages.findAll()).allSatisfy(m ->
                    assertThat(m.status()).isEqualTo("PENDING"));
            assertThat(second.getBean(SyntheticUpstreamClient.class).dispatchCount()).isZero();

            // 恢复联网：高优先级先发，同级按序号升序
            service2.setOnline(true);
            assertThat(second.getBean(SyntheticUpstreamClient.class).dispatchedKeys())
                    .containsExactly("p-hi-1", "p-low-2");
        } finally {
            second.close();
        }
    }
}
