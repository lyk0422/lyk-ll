package com.example.starter.trace;

import com.example.starter.trace.dto.CreateProcessingBatchRequest;
import com.example.starter.trace.dto.ProcessingBatchResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 加工批次测试：仅可引用已放行采收批，冻结拦截，引用与放行判定同事务校验。
 */
class ProcessingBatchServiceTest extends BaseTraceTest {

    @Test
    void 引用已放行采收批_创建加工批次成功() {
        long released = newBatch(1L);
        rapid(released, "SMP-P1", "10.00");

        ProcessingBatchResponse response = processingBatchService.createProcessingBatch(
                new CreateProcessingBatchRequest("REQ-PB-1", "PB-1", List.of(released)));
        assertThat(response.harvestBatchIds()).containsExactly(released);
    }

    @Test
    void 引用冻结采收批_拒绝且不落库() {
        long frozen = newBatch(1L);
        rapid(frozen, "SMP-P2", "55.00");

        assertThatThrownBy(() -> processingBatchService.createProcessingBatch(
                new CreateProcessingBatchRequest("REQ-PB-2", "PB-2", List.of(frozen))))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).contains("冻结");
                });
    }

    @Test
    void 引用未检测采收批_拒绝() {
        long registered = newBatch(1L);
        assertThatThrownBy(() -> processingBatchService.createProcessingBatch(
                new CreateProcessingBatchRequest("REQ-PB-3", "PB-3", List.of(registered))))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void 引用不存在采收批_返回404语义() {
        assertThatThrownBy(() -> processingBatchService.createProcessingBatch(
                new CreateProcessingBatchRequest("REQ-PB-4", "PB-4", List.of(99999L))))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void 冻结批复检合格放行后_可被引用() {
        long batchId = newBatch(1L);
        rapid(batchId, "SMP-P5", "55.00");
        residueTestService.submitRetest(batchId, new com.example.starter.trace.dto.RetestRequest(
                "REQ-PB-5-RT", "SMP-P6", new java.math.BigDecimal("18.00"), true));

        ProcessingBatchResponse response = processingBatchService.createProcessingBatch(
                new CreateProcessingBatchRequest("REQ-PB-5", "PB-5", List.of(batchId)));
        assertThat(response.harvestBatchIds()).containsExactly(batchId);
    }

    @Test
    void 加工批次幂等_同键同参返回首次结果_同键异参409() {
        long released = newBatch(1L);
        rapid(released, "SMP-P7", "10.00");

        ProcessingBatchResponse first = processingBatchService.createProcessingBatch(
                new CreateProcessingBatchRequest("REQ-PB-6", "PB-6", List.of(released)));
        ProcessingBatchResponse replay = processingBatchService.createProcessingBatch(
                new CreateProcessingBatchRequest("REQ-PB-6", "PB-6", List.of(released)));
        assertThat(replay).isEqualTo(first);

        assertThatThrownBy(() -> processingBatchService.createProcessingBatch(
                new CreateProcessingBatchRequest("REQ-PB-6", "PB-6-DIFFERENT", List.of(released))))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
    }
}
