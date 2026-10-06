package com.example.starter.trace;

import com.example.starter.trace.dto.HarvestBatchResponse;
import com.example.starter.trace.dto.RetestRequest;
import com.example.starter.trace.dto.TestSubmissionResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 药残快检/复检流程测试：恰 50% 边界、冻结与解冻、换样复检、原快检记录保留。
 */
class ResidueTestFlowTest extends BaseTraceTest {

    @Test
    void 快检抑制率49点99_合格放行() {
        long batchId = newBatch(1L);
        TestSubmissionResponse response = rapid(batchId, "SMP-1", "49.99");
        assertThat(response.conclusion()).isEqualTo("PASS");
        assertThat(response.batchStatus()).isEqualTo("RELEASED");
        assertThat(harvestBatchService.getBatch(batchId).status()).isEqualTo("RELEASED");
    }

    @Test
    void 快检抑制率恰50_判疑似并冻结() {
        long batchId = newBatch(1L);
        TestSubmissionResponse response = rapid(batchId, "SMP-1", "50.00");
        assertThat(response.conclusion()).isEqualTo("SUSPECT");
        assertThat(response.batchStatus()).isEqualTo("FROZEN");
        assertThat(harvestBatchService.getBatch(batchId).status()).isEqualTo("FROZEN");
    }

    @Test
    void 同批存在未闭环检测链_再次快检拒绝() {
        long batchId = newBatch(1L);
        rapid(batchId, "SMP-1", "60.00");
        assertThatThrownBy(() -> rapid(batchId, "SMP-2", "10.00"))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).contains("一条检测链");
                });
    }

    @Test
    void 复检使用同一样品_拒绝() {
        long batchId = newBatch(1L);
        rapid(batchId, "SMP-1", "55.00");
        assertThatThrownBy(() -> residueTestService.submitRetest(batchId,
                new RetestRequest("REQ-RT-1", "SMP-1", new BigDecimal("20.00"), true)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).contains("不同样品");
                });
    }

    @Test
    void 复检合格_解除冻结且原快检记录保留可查() {
        long batchId = newBatch(1L);
        rapid(batchId, "SMP-1", "55.00");
        TestSubmissionResponse retest = residueTestService.submitRetest(batchId,
                new RetestRequest("REQ-RT-2", "SMP-2", new BigDecimal("20.00"), true));
        assertThat(retest.conclusion()).isEqualTo("OVERTURNED");
        assertThat(retest.batchStatus()).isEqualTo("RELEASED");

        HarvestBatchResponse detail = harvestBatchService.getBatch(batchId);
        assertThat(detail.status()).isEqualTo("RELEASED");
        assertThat(detail.tests()).hasSize(2);
        assertThat(detail.tests().get(0).testType()).isEqualTo("RAPID");
        assertThat(detail.tests().get(0).conclusion()).isEqualTo("SUSPECT");
        assertThat(detail.tests().get(1).testType()).isEqualTo("RETEST");
        assertThat(detail.tests().get(1).conclusion()).isEqualTo("OVERTURNED");
    }

    @Test
    void 复检不合格_维持冻结不得进入加工() {
        long batchId = newBatch(1L);
        rapid(batchId, "SMP-1", "55.00");
        TestSubmissionResponse retest = residueTestService.submitRetest(batchId,
                new RetestRequest("REQ-RT-3", "SMP-2", new BigDecimal("58.00"), false));
        assertThat(retest.conclusion()).isEqualTo("CONFIRMED");
        assertThat(retest.batchStatus()).isEqualTo("FROZEN");
        assertThat(harvestBatchService.getBatch(batchId).status()).isEqualTo("FROZEN");
    }

    @Test
    void 无进行中检测链_复检拒绝() {
        long batchId = newBatch(1L);
        assertThatThrownBy(() -> residueTestService.submitRetest(batchId,
                new RetestRequest("REQ-RT-4", "SMP-9", new BigDecimal("20.00"), true)))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void 快检合格后检测链闭环_可再次发起新快检() {
        long batchId = newBatch(1L);
        rapid(batchId, "SMP-1", "10.00");
        TestSubmissionResponse second = rapid(batchId, "SMP-2", "12.00");
        assertThat(second.conclusion()).isEqualTo("PASS");
        assertThat(harvestBatchService.getBatch(batchId).tests()).hasSize(2);
    }
}
