package com.example.starter.trace;

import com.example.starter.trace.api.TraceDtos.ContributionItem;
import com.example.starter.trace.api.TraceDtos.ProcessingBatchRequest;
import com.example.starter.trace.api.TraceDtos.ProcessingBatchResponse;
import com.example.starter.trace.api.TraceDtos.RapidTestRequest;
import com.example.starter.trace.api.TraceDtos.RegisterHarvestBatchRequest;
import com.example.starter.trace.api.TraceDtos.RetestRequest;
import com.example.starter.trace.service.BusinessException;
import com.example.starter.trace.service.HarvestService;
import com.example.starter.trace.service.ProcessingService;
import com.example.starter.trace.service.TestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 加工批：仅可引用已放行采收批，冻结批拦截，写幂等。
 */
class ProcessingServiceTest extends BaseTraceTest {

    @Autowired
    private HarvestService harvestService;

    @Autowired
    private TestService testService;

    @Autowired
    private ProcessingService processingService;

    private Long apiaryId(String code) {
        return jdbc.queryForObject("SELECT id FROM apiary WHERE code = ?", Long.class, code);
    }

    private Long registerBatch(String requestId, String batchNo) {
        return harvestService.register(new RegisterHarvestBatchRequest(requestId, batchNo, "2026-洋槐",
                List.of(new ContributionItem(apiaryId("AP-001"), new BigDecimal("100.00"))))).id();
    }

    @Test
    void 引用已放行采收批_创建成功() {
        Long batchId = registerBatch("req-p1", "HB-P1");
        testService.rapidTest(batchId, new RapidTestRequest("req-prt-1", "SMP-P1", new BigDecimal("10.00")));

        ProcessingBatchResponse response = processingService.create(
                new ProcessingBatchRequest("req-pb-1", "PB-001", batchId));

        assertThat(response.id()).isNotNull();
        assertThat(response.harvestBatchId()).isEqualTo(batchId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processing_batch", Integer.class)).isEqualTo(1);
    }

    @Test
    void 冻结批不得被引用() {
        Long batchId = registerBatch("req-p2", "HB-P2");
        testService.rapidTest(batchId, new RapidTestRequest("req-prt-2", "SMP-P2", new BigDecimal("50.00")));

        assertThatThrownBy(() -> processingService.create(
                new ProcessingBatchRequest("req-pb-2", "PB-002", batchId)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processing_batch", Integer.class)).isZero();
    }

    @Test
    void 未检测登记批不得被引用() {
        Long batchId = registerBatch("req-p3", "HB-P3");

        assertThatThrownBy(() -> processingService.create(
                new ProcessingBatchRequest("req-pb-3", "PB-003", batchId)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void 复检不合格批不得被引用() {
        Long batchId = registerBatch("req-p4", "HB-P4");
        testService.rapidTest(batchId, new RapidTestRequest("req-prt-4", "SMP-P4", new BigDecimal("66.00")));
        testService.retest(batchId, new RetestRequest("req-pre-4", "SMP-P4-B", "UNQUALIFIED"));

        assertThatThrownBy(() -> processingService.create(
                new ProcessingBatchRequest("req-pb-4", "PB-004", batchId)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void 复检合格解冻后_可被引用() {
        Long batchId = registerBatch("req-p5", "HB-P5");
        testService.rapidTest(batchId, new RapidTestRequest("req-prt-5", "SMP-P5", new BigDecimal("66.00")));
        testService.retest(batchId, new RetestRequest("req-pre-5", "SMP-P5-B", "QUALIFIED"));

        ProcessingBatchResponse response = processingService.create(
                new ProcessingBatchRequest("req-pb-5", "PB-005", batchId));

        assertThat(response.id()).isNotNull();
    }

    @Test
    void 加工批幂等_同键同参返回首次结果_异参409() {
        Long batchId = registerBatch("req-p6", "HB-P6");
        testService.rapidTest(batchId, new RapidTestRequest("req-prt-6", "SMP-P6", new BigDecimal("10.00")));

        ProcessingBatchResponse first = processingService.create(
                new ProcessingBatchRequest("req-pb-6", "PB-006", batchId));
        ProcessingBatchResponse replay = processingService.create(
                new ProcessingBatchRequest("req-pb-6", "PB-006", batchId));
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processing_batch", Integer.class)).isEqualTo(1);

        assertThatThrownBy(() -> processingService.create(
                new ProcessingBatchRequest("req-pb-6", "PB-006-DIFF", batchId)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
    }
}
