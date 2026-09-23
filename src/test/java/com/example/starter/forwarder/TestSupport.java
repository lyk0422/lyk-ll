package com.example.starter.forwarder;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * 测试支撑：可控时钟、录制上游、独立临时目录的 H2 文件库。
 */
final class TestSupport {

    private TestSupport() {
    }

    /**
     * 可控时钟：测试可手动推进时间，验证 in-flight 超时。
     */
    static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    /**
     * 录制上游：记录发送顺序，可切换失败以模拟网络抖动。
     */
    static final class RecordingUpstream implements UpstreamAdapter {
        private final List<String> sent = new ArrayList<>();
        private boolean failing;

        void setFailing(boolean failing) {
            this.failing = failing;
        }

        List<String> sent() {
            return sent;
        }

        @Override
        public synchronized boolean send(MessageView message) {
            if (failing) {
                return false;
            }
            sent.add(message.dedupKey());
            return true;
        }
    }

    /**
     * 在独立临时目录创建 H2 文件库（MySQL 兼容模式）并执行建表脚本。
     */
    static JdbcTemplate jdbcForFileDb(Path directory, String dbName) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:file:" + directory.resolve(dbName).toAbsolutePath()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(new ClassPathResource("schema.sql"));
        populator.execute(dataSource);
        return jdbc;
    }

    /**
     * 标准测试环境：默认 FIFO、容量 100、in-flight 超时 30 秒、离线。
     */
    static ForwarderService newService(JdbcTemplate jdbc, MutableClock clock,
                                       RecordingUpstream upstream, NetworkState network,
                                       ForwarderProperties properties) {
        return new ForwarderService(new MessageRepository(jdbc), new AuditRepository(jdbc),
                upstream, network, properties, clock);
    }

    static ForwarderProperties defaultProperties() {
        ForwarderProperties properties = new ForwarderProperties();
        properties.setCapacity(100);
        properties.setInFlightTimeoutSeconds(30);
        properties.setNetworkOnline(false);
        properties.setDefaultPolicy("FIFO");
        return properties;
    }

    static IncomingMessage msg(String deviceId, String topic, long seq, int priority, String dedupKey) {
        return new IncomingMessage(deviceId, topic, seq, priority, dedupKey, "payload-" + dedupKey);
    }
}
