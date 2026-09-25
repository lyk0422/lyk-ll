package com.example.starter;

import java.math.BigDecimal;
import java.time.Instant;

import com.example.starter.batch.BatchCreateRequest;
import com.example.starter.batch.BatchRepository;
import com.example.starter.batch.BatchResponse;
import com.example.starter.batch.BatchService;
import com.example.starter.domain.RecheckResult;
import com.example.starter.inspection.InspectionService;
import com.example.starter.inspection.RapidTestRepository;
import com.example.starter.inspection.RapidTestRequest;
import com.example.starter.inspection.RapidTestResponse;
import com.example.starter.inspection.SampleRepository;
import com.example.starter.inspection.SampleRequest;
import com.example.starter.inspection.SampleResponse;
import com.example.starter.recheck.RecheckRepository;
import com.example.starter.recheck.RecheckRequest;
import com.example.starter.recheck.RecheckResponse;
import com.example.starter.recheck.RecheckService;
import com.example.starter.recheck.RetentionRepository;
import com.example.starter.alert.AlertService;
import com.example.starter.alert.OriginAlertRepository;
import com.example.starter.event.LateEventRepository;
import com.example.starter.support.MutableClock;
import com.example.starter.support.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试基类：隔离的 H2 命名内存库（MODE=MySQL），每个用例前清空数据并重置时钟。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
public abstract class AbstractIntegrationTest {

    protected static final Instant T0 = Instant.parse("2026-09-26T08:00:00Z");

    @Autowired
    protected MutableClock clock;
    @Autowired
    protected JdbcTemplate jdbcTemplate;
    @Autowired
    protected BatchService batchService;
    @Autowired
    protected InspectionService inspectionService;
    @Autowired
    protected RecheckService recheckService;
    @Autowired
    protected AlertService alertService;
    @Autowired
    protected BatchRepository batchRepository;
    @Autowired
    protected SampleRepository sampleRepository;
    @Autowired
    protected RapidTestRepository rapidTestRepository;
    @Autowired
    protected RecheckRepository recheckRepository;
    @Autowired
    protected RetentionRepository retentionRepository;
    @Autowired
    protected OriginAlertRepository originAlertRepository;
    @Autowired
    protected LateEventRepository lateEventRepository;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM late_event");
        jdbcTemplate.update("DELETE FROM retention");
        jdbcTemplate.update("DELETE FROM recheck");
        jdbcTemplate.update("DELETE FROM rapid_test");
        jdbcTemplate.update("DELETE FROM sample");
        jdbcTemplate.update("DELETE FROM origin_alert");
        jdbcTemplate.update("DELETE FROM batch");
        clock.reset(T0);
    }

    protected BatchResponse registerBatch(String requestId, String origin, String variety) {
        return batchService.register(new BatchCreateRequest(requestId, origin, variety,
                new BigDecimal("1000.00")));
    }

    protected SampleResponse sample(String requestId, long batchId, String item) {
        return inspectionService.sample(batchId, new SampleRequest(requestId, item));
    }

    protected RapidTestResponse rapidTest(String requestId, long batchId, String item, String rate) {
        return inspectionService.rapidTest(batchId,
                new RapidTestRequest(requestId, item, new BigDecimal(rate)));
    }

    protected RecheckResponse recheck(String requestId, long batchId, RecheckResult result) {
        return recheckService.recheck(batchId, new RecheckRequest(requestId, result));
    }

    /**
     * 报备＋抽样＋快检疑似，返回批次ID。
     */
    protected long suspectedBatch(String keyPrefix, String origin, String variety) {
        BatchResponse batch = registerBatch(keyPrefix + "-reg", origin, variety);
        sample(keyPrefix + "-sam", batch.id(), "有机磷");
        rapidTest(keyPrefix + "-test", batch.id(), "有机磷", "60");
        return batch.id();
    }
}
