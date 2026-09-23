package com.example.starter.forwarder.recovery;

import com.example.starter.forwarder.model.AuditEventType;
import com.example.starter.forwarder.model.DeviceMessage;
import com.example.starter.forwarder.repo.AuditRepository;
import com.example.starter.forwarder.repo.MessageRepository;
import com.example.starter.forwarder.service.ForwarderService;
import com.example.starter.forwarder.testsupport.MutableClock;
import com.example.starter.forwarder.testsupport.RecordingUpstreamAdapter;
import com.example.starter.forwarder.upstream.UpstreamAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.example.starter.forwarder.config.ForwarderProperties;
import com.example.starter.forwarder.support.Clock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 跨进程恢复测试：第一个上下文把消息发到 in-flight 后直接关闭（模拟崩溃），
 * 第二个上下文指向同一个 H2 文件库启动，启动恢复必须把遗留 in-flight 重置为待发，
 * 且联网后能重新补发。
 */
class CrashRecoveryH2Test {

    @Configuration
    @EnableAutoConfiguration
    @EnableConfigurationProperties(ForwarderProperties.class)
    @Import({com.example.starter.forwarder.config.ForwarderConfig.class,
            com.example.starter.forwarder.repo.MessageRepository.class,
            com.example.starter.forwarder.repo.AuditRepository.class,
            com.example.starter.forwarder.service.ForwarderService.class,
            com.example.starter.forwarder.schedule.DispatchScheduler.class,
            StartupRecovery.class})
    static class TestApp {
        @Bean
        Clock clock() {
            return new MutableClock();
        }

        @Bean
        UpstreamAdapter upstreamAdapter() {
            return new RecordingUpstreamAdapter();
        }
    }

    private String[] baseArgs(String dbFile) {
        // 命令行参数必须带 -- 前缀才会被 Spring Boot 识别为属性覆盖
        return new String[]{
                "--spring.datasource.url=jdbc:h2:file:" + dbFile
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "--spring.datasource.username=sa",
                "--spring.datasource.password=",
                "--forwarder.capacity=10000",
                "--forwarder.dispatch-batch-size=200",
                "--forwarder.ack-timeout-ms=5000",
                "--forwarder.dispatch-interval-ms=3600000",
                "--forwarder.online-on-startup=false",
                "--logging.level.root=WARN"
        };
    }

    @Test
    void inflightMessagesSurviveRestartAndAreRedelivered() throws IOException {
        Path tempDir = Files.createTempDirectory("forwarder-h2-crash-");
        String dbFile = tempDir.resolve("db").toString();
        String dedupKey = "crash-msg-1";

        // 第一个进程：写入并发到上游，使其处于 in-flight，随后直接关闭模拟崩溃。
        ConfigurableApplicationContext first = new SpringApplicationBuilder(TestApp.class)
                .web(WebApplicationType.NONE)
                .run(concat(baseArgs(dbFile)));
        try {
            ForwarderService service1 = first.getBean(ForwarderService.class);
            com.example.starter.forwarder.web.IngestRequest request =
                    new com.example.starter.forwarder.web.IngestRequest();
            request.setDeviceId("dev1");
            request.setTopic("/sensor/fifo");
            request.setSeq(7L);
            request.setPriority(3);
            request.setDedupKey(dedupKey);
            request.setPayload("crash-payload");
            service1.ingest(request);
            service1.setOnline(true);
            assertEquals(1, service1.dispatchOnce());

            DeviceMessage stored = first.getBean(MessageRepository.class).findByDedupKey(dedupKey);
            assertEquals(com.example.starter.forwarder.model.MessageStatus.IN_FLIGHT, stored.getStatus());
        } finally {
            // 直接关闭，不等 ACK：模拟进程崩溃。
            first.close();
        }

        // 第二个进程：指向同一文件库启动；ApplicationRunner 自动执行启动恢复。
        ConfigurableApplicationContext second = new SpringApplicationBuilder(TestApp.class)
                .web(WebApplicationType.NONE)
                .run(concat(baseArgs(dbFile)));
        try {
            MessageRepository repository2 = second.getBean(MessageRepository.class);
            AuditRepository audit2 = second.getBean(AuditRepository.class);
            ForwarderService service2 = second.getBean(ForwarderService.class);
            RecordingUpstreamAdapter upstream2 =
                    (RecordingUpstreamAdapter) second.getBean(UpstreamAdapter.class);

            DeviceMessage afterRestart = repository2.findByDedupKey(dedupKey);
            assertNotNull(afterRestart);
            assertEquals(com.example.starter.forwarder.model.MessageStatus.PENDING,
                    afterRestart.getStatus(), "遗留 in-flight 必须在启动时重置为待发");
            assertEquals(1, audit2.countByType(AuditEventType.RESET_INFLIGHT));

            // 恢复联网后应能重新补发这条消息
            service2.setOnline(true);
            assertEquals(1, service2.dispatchOnce());
            assertEquals(List.of(dedupKey), upstream2.getSentKeys());
        } finally {
            second.close();
        }
    }

    private String[] concat(String[] base) {
        return base;
    }
}
