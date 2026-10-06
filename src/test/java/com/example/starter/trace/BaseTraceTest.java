package com.example.starter.trace;

import com.example.starter.trace.dto.CreateHarvestBatchRequest;
import com.example.starter.trace.dto.HarvestBatchResponse;
import com.example.starter.trace.dto.RapidTestRequest;
import com.example.starter.trace.dto.TestSubmissionResponse;
import com.example.starter.trace.service.HarvestBatchService;
import com.example.starter.trace.service.ProcessingBatchService;
import com.example.starter.trace.service.ResidueTestService;
import com.example.starter.trace.service.WarningService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 追溯服务测试基类：独立 H2 内存库、固定可控时钟、每个用例前清理数据。
 */
@SpringBootTest
@Sql(scripts = "/cleanup.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
public abstract class BaseTraceTest {

    protected static final String PERIOD = "2026-洋槐";

    @Autowired
    protected HarvestBatchService harvestBatchService;
    @Autowired
    protected ResidueTestService residueTestService;
    @Autowired
    protected ProcessingBatchService processingBatchService;
    @Autowired
    protected WarningService warningService;

    private final AtomicInteger batchSeq = new AtomicInteger();
    private final AtomicInteger requestSeq = new AtomicInteger();

    @BeforeEach
    void resetSequences() {
        batchSeq.set(0);
        requestSeq.set(0);
    }

    /**
     * 登记一个单蜂场采收批并返回批次 ID。
     */
    protected long newBatch(long apiaryId) {
        return newBatch(apiaryId, PERIOD);
    }

    /**
     * 登记一个指定花期的单蜂场采收批并返回批次 ID。
     */
    protected long newBatch(long apiaryId, String flowerPeriod) {
        int seq = batchSeq.incrementAndGet();
        HarvestBatchResponse response = harvestBatchService.createBatch(new CreateHarvestBatchRequest(
                "REQ-BATCH-" + seq + "-" + System.nanoTime(),
                "HB-T-" + seq + "-" + System.nanoTime(),
                flowerPeriod,
                List.of(new CreateHarvestBatchRequest.ContributionItem(apiaryId, new BigDecimal("100.00")))));
        return response.id();
    }

    /**
     * 提交一次快检。
     */
    protected TestSubmissionResponse rapid(long batchId, String sampleNo, String inhibitionRate) {
        return residueTestService.submitRapidTest(batchId, new RapidTestRequest(
                "REQ-RAPID-" + requestSeq.incrementAndGet() + "-" + System.nanoTime(),
                sampleNo, new BigDecimal(inhibitionRate)));
    }

    /**
     * 测试用固定时钟，保证时间相关断言可控。
     */
    @TestConfiguration
    static class FixedClockTestConfig {

        @Bean
        @Primary
        Clock fixedTestClock() {
            return Clock.fixed(Instant.parse("2026-06-01T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
        }
    }
}
