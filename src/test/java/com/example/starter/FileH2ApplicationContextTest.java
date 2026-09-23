package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.adapter.SyntheticUpstreamAdapter;
import com.example.starter.dto.MessageRequest;
import com.example.starter.dto.TopicPolicyRequest;
import com.example.starter.enums.RetentionStrategy;
import com.example.starter.model.TopicPolicy;
import com.example.starter.service.ForwarderService;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 使用生产默认形态（H2 文件库 + MODE=MySQL，仅把数据目录指向临时目录）启动完整上下文，
 * 验证默认本地启动入口、自动建表与核心写入链路可用；上下文随用例结束自动关闭。
 */
@SpringBootTest
class FileH2ApplicationContextTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void fileH2DataSource(DynamicPropertyRegistry registry) {
        String dbPath = tempDir.resolve("forwarderdb").toString().replace('\\', '/');
        // 显式取消任何 DB_URL 覆盖，使用文件模式 H2（与 application.yaml 默认形态一致）。
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:file:" + dbPath + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        // 不指定 driver-class-name，与生产默认 yaml 一致，验证按 H2 URL 自动识别驱动。
    }

    @Autowired
    private ForwarderService forwarderService;
    @Autowired
    private SyntheticUpstreamAdapter upstreamAdapter;

    @Test
    void applicationBootsWithFileH2AndProcessesMessage() {
        upstreamAdapter.setOnline(false);

        TopicPolicyRequest policyRequest = new TopicPolicyRequest();
        policyRequest.setStrategy("FIFO");
        TopicPolicy policy = forwarderService.configurePolicy("file-topic", policyRequest);
        assertThat(policy.getStrategy()).isEqualTo(RetentionStrategy.FIFO);

        MessageRequest request = new MessageRequest();
        request.setDedupKey("file-key-1");
        request.setDeviceId("dev-file");
        request.setTopic("file-topic");
        request.setSeq(1L);
        request.setPriority(3);
        request.setPayload("synthetic-file-payload");

        assertThat(forwarderService.ingest(request).outcome()).isEqualTo("ACCEPTED");
        assertThat(forwarderService.snapshotPending())
                .extracting(message -> message.getMessageId())
                .containsExactly("file-key-1");
    }
}
