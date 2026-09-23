package com.example.starter.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.enums.AuditEventType;
import com.example.starter.enums.MessageStatus;
import com.example.starter.enums.RetentionStrategy;
import com.example.starter.model.DeviceMessage;
import com.example.starter.model.TopicPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * 跨进程恢复测试：使用工作目录下的 H2 文件库（MODE=MySQL），
 * 关闭数据源后重新打开，验证待发消息、in-flight 状态、策略与审计记录持久化，
 * 且残留 in-flight 在重启后可回退待发并重新补发。
 */
class FileH2RecoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void statePersistsAcrossDataSourceRestartAndInflightRecovers() {
        String url = "jdbc:h2:file:" + tempDir.resolve("forwarderdb").toString().replace('\\', '/')
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE";

        // 第一个“进程”：初始化库、写入策略、待发消息、in-flight 消息与审计记录。
        try (SingleConnectionDataSource firstDataSource = dataSource(url)) {
            JdbcTemplate firstJdbc = new JdbcTemplate(firstDataSource);
            initSchema(firstDataSource);
            MessageRepository messages = new MessageRepository(firstJdbc);
            TopicPolicyRepository policies = new TopicPolicyRepository(firstJdbc);
            AuditRepository audits = new AuditRepository(firstJdbc);

            TopicPolicy policy = new TopicPolicy();
            policy.setTopic("cmd");
            policy.setStrategy(RetentionStrategy.BOUNDED);
            policy.setBound(3);
            policy.setCreatedAt(1000L);
            policy.setUpdatedAt(1000L);
            policies.upsert(policy);

            messages.insert(pending("m-pending", "dev1", 1L));
            messages.insert(inflight("m-inflight-1", "dev1", 2L, 5000L));
            messages.insert(inflight("m-inflight-2", "dev2", 1L, 6000L));
            DeviceMessage forwarded = pending("m-forwarded", "dev1", 3L);
            forwarded.setStatus(MessageStatus.FORWARDED);
            forwarded.setAckedAt(7000L);
            messages.insert(forwarded);

            DeviceMessage droppedForAudit = pending("m-dropped", "dev1", 4L);
            audits.record(AuditEventType.DROP_POLICY, droppedForAudit, "模拟断网丢弃", 8000L);

            assertThat(messages.countActive()).isEqualTo(3);
        }
        shutdown(url);

        // 第二个“进程”：重新打开同一文件库，验证数据仍在。
        try (SingleConnectionDataSource secondDataSource = dataSource(url)) {
            JdbcTemplate secondJdbc = new JdbcTemplate(secondDataSource);
            initSchema(secondDataSource);
            MessageRepository messages = new MessageRepository(secondJdbc);
            TopicPolicyRepository policies = new TopicPolicyRepository(secondJdbc);
            AuditRepository audits = new AuditRepository(secondJdbc);

            assertThat(messages.countActive()).isEqualTo(3);
            assertThat(messages.findByMessageId("m-pending").getStatus())
                    .isEqualTo(MessageStatus.PENDING);
            assertThat(messages.findByMessageId("m-inflight-1").getStatus())
                    .isEqualTo(MessageStatus.INFLIGHT);
            assertThat(messages.findByMessageId("m-forwarded").getStatus())
                    .isEqualTo(MessageStatus.FORWARDED);

            TopicPolicy reloadedPolicy = policies.findByTopic("cmd");
            assertThat(reloadedPolicy.getStrategy()).isEqualTo(RetentionStrategy.BOUNDED);
            assertThat(reloadedPolicy.getBound()).isEqualTo(3);
            assertThat(audits.findByEventType(AuditEventType.DROP_POLICY))
                    .extracting(audit -> audit.getMessageId())
                    .containsExactly("m-dropped");

            // 启动恢复：残留 in-flight 回退 PENDING，已确认消息不受影响。
            int recovered = messages.recoverInflightOnStartup();
            assertThat(recovered).isEqualTo(2);
            List<DeviceMessage> pending = messages.findPendingSnapshot();
            assertThat(pending)
                    .extracting(DeviceMessage::getMessageId)
                    .containsExactly("m-pending", "m-inflight-2", "m-inflight-1");
            assertThat(messages.findByMessageId("m-forwarded").getStatus())
                    .isEqualTo(MessageStatus.FORWARDED);
            assertThat(Files.exists(tempDir.resolve("forwarderdb.mv.db"))).isTrue();
        }
        shutdown(url);
    }

    private SingleConnectionDataSource dataSource(String url) {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource();
        dataSource.setUrl(url);
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        dataSource.setSuppressClose(true);
        return dataSource;
    }

    private void initSchema(javax.sql.DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        } catch (SQLException ex) {
            throw new IllegalStateException("初始化测试 schema 失败", ex);
        }
    }

    private void shutdown(String url) {
        // 使用独立连接执行 SHUTDOWN 释放文件库，模拟进程退出。
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SHUTDOWN");
        } catch (SQLException ex) {
            throw new IllegalStateException("关闭 H2 文件库失败", ex);
        }
    }

    private DeviceMessage pending(String key, String deviceId, long seq) {
        DeviceMessage message = new DeviceMessage();
        message.setMessageId(key);
        message.setDeviceId(deviceId);
        message.setTopic("cmd");
        message.setSeq(seq);
        message.setPriority(0);
        message.setPayload("payload-" + key);
        message.setStatus(MessageStatus.PENDING);
        message.setAttempts(0);
        message.setEnqueuedAt(seq * 1000);
        return message;
    }

    private DeviceMessage inflight(String key, String deviceId, long seq, long inflightAt) {
        DeviceMessage message = pending(key, deviceId, seq);
        message.setStatus(MessageStatus.INFLIGHT);
        message.setAttempts(1);
        message.setInflightAt(inflightAt);
        message.setForwardedAt(inflightAt);
        return message;
    }
}
