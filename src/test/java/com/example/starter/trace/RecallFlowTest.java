package com.example.starter.trace;

import com.example.starter.trace.dto.CreateProcessingBatchRequest;
import com.example.starter.trace.dto.CreateRecallRequest;
import com.example.starter.trace.dto.ProcessingBatchResponse;
import com.example.starter.trace.dto.RecallResponse;
import com.example.starter.trace.dto.RetestRequest;
import com.example.starter.trace.dto.WithdrawRecallRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 来源召回主流程测试：创建冻结清单、后到批次自动关联、双来源召回、
 * 双人撤回、版本控制、幂等与失败分支。
 */
class RecallFlowTest extends BaseTraceTest {

    private CreateRecallRequest recallRequest(String requestId, long apiaryId, String flowerPeriod) {
        return new CreateRecallRequest(requestId, apiaryId, flowerPeriod,
                "合成召回原因", "CRED-" + requestId, "QC-CREATE");
    }

    private WithdrawRecallRequest withdrawRequest(String requestId, long expectedVersion) {
        return new WithdrawRecallRequest(requestId, expectedVersion,
                "QC-A", "QC-B", "WCRED-A-" + requestId, "WCRED-B-" + requestId);
    }

    @Test
    void 创建召回_冻结贡献批与已加工批_后到批次自动关联_查询展示两类清单() {
        long existing = newBatch(1L);
        rapid(existing, "SMP-R1", "10.00");
        ProcessingBatchResponse processing = processingBatchService.createProcessingBatch(
                new CreateProcessingBatchRequest(newRequestId("REQ-PB-R1"), "PB-R1", List.of(existing)));

        RecallResponse recall = recallService.createRecall(
                recallRequest(newRequestId("REQ-RC-1"), 1L, PERIOD));

        assertThat(recall.status()).isEqualTo("ACTIVE");
        assertThat(recall.version()).isEqualTo(1);
        assertThat(recall.harvestLinks())
                .singleElement()
                .satisfies(l -> {
                    assertThat(l.harvestBatchId()).isEqualTo(existing);
                    assertThat(l.origin()).isEqualTo("INITIAL");
                    assertThat(l.status()).isEqualTo("ACTIVE");
                });
        assertThat(recall.processingLinks())
                .singleElement()
                .satisfies(l -> {
                    assertThat(l.processingBatchId()).isEqualTo(processing.id());
                    assertThat(l.origin()).isEqualTo("INITIAL");
                    assertThat(l.status()).isEqualTo("ACTIVE");
                });

        // 被隔离采收批不得新增加工引用
        assertThatThrownBy(() -> processingBatchService.createProcessingBatch(
                new CreateProcessingBatchRequest(newRequestId("REQ-PB-R2"), "PB-R2", List.of(existing))))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).contains("召回隔离");
                });

        // 后来登记的同来源批自动受活动召回约束；其他蜂场、其他花期不受影响
        long later = newBatch(1L);
        long otherApiary = newBatch(2L);
        long otherPeriod = newBatch(1L, "2026-荆条");

        RecallResponse detail = recallService.getRecall(recall.id());
        assertThat(detail.harvestLinks())
                .anySatisfy(l -> {
                    assertThat(l.harvestBatchId()).isEqualTo(existing);
                    assertThat(l.origin()).isEqualTo("INITIAL");
                })
                .anySatisfy(l -> {
                    assertThat(l.harvestBatchId()).isEqualTo(later);
                    assertThat(l.origin()).isEqualTo("LATER");
                    assertThat(l.status()).isEqualTo("ACTIVE");
                });
        assertThat(detail.harvestLinks())
                .noneMatch(l -> l.harvestBatchId().equals(otherApiary))
                .noneMatch(l -> l.harvestBatchId().equals(otherPeriod));
    }

    @Test
    void 双来源召回_60比40贡献批_撤回一条仍被另一条隔离() {
        long batchId = newBatch6040();
        rapid(batchId, "SMP-R2", "12.00");

        RecallResponse recallA = recallService.createRecall(
                recallRequest(newRequestId("REQ-RC-A"), 1L, PERIOD));
        RecallResponse recallB = recallService.createRecall(
                recallRequest(newRequestId("REQ-RC-B"), 2L, PERIOD));
        assertThat(recallA.harvestLinks()).hasSize(1);
        assertThat(recallB.harvestLinks()).hasSize(1);

        // 两条召回同时生效，加工被拦截
        assertThatThrownBy(() -> processingBatchService.createProcessingBatch(
                new CreateProcessingBatchRequest(newRequestId("REQ-PB-DS"), "PB-DS", List.of(batchId))))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));

        // 只撤回蜂场一的召回：该条关联失效，蜂场二的隔离仍然生效
        RecallResponse withdrawnA = recallService.withdrawRecall(recallA.id(),
                withdrawRequest(newRequestId("REQ-WD-A"), 1L));
        assertThat(withdrawnA.status()).isEqualTo("WITHDRAWN");
        assertThat(withdrawnA.version()).isEqualTo(2);
        assertThat(withdrawnA.harvestLinks()).allMatch(l -> l.status().equals("RELEASED"));
        assertThat(recallService.getRecall(recallB.id()).harvestLinks())
                .allMatch(l -> l.status().equals("ACTIVE"));

        assertThatThrownBy(() -> processingBatchService.createProcessingBatch(
                new CreateProcessingBatchRequest(newRequestId("REQ-PB-DS2"), "PB-DS2", List.of(batchId))))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).contains("召回隔离");
                });

        // 再撤回蜂场二的召回后，加工放行
        recallService.withdrawRecall(recallB.id(), withdrawRequest(newRequestId("REQ-WD-B"), 1L));
        ProcessingBatchResponse processing = processingBatchService.createProcessingBatch(
                new CreateProcessingBatchRequest(newRequestId("REQ-PB-DS3"), "PB-DS3", List.of(batchId)));
        assertThat(processing.harvestBatchIds()).containsExactly(batchId);
    }

    @Test
    void 撤回召回后_原药残疑似未合格复检仍不许加工() {
        long batchId = newBatch(1L);
        rapid(batchId, "SMP-R3", "55.00");
        residueTestService.submitRetest(batchId, new RetestRequest(
                newRequestId("REQ-RT-R3"), "SMP-R3-RT", new BigDecimal("60.00"), false));

        RecallResponse recall = recallService.createRecall(
                recallRequest(newRequestId("REQ-RC-R3"), 1L, PERIOD));
        recallService.withdrawRecall(recall.id(), withdrawRequest(newRequestId("REQ-WD-R3"), 1L));

        assertThatThrownBy(() -> processingBatchService.createProcessingBatch(
                new CreateProcessingBatchRequest(newRequestId("REQ-PB-R3"), "PB-R3", List.of(batchId))))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).contains("冻结");
                });
    }

    @Test
    void 同蜂场同花期仅一条活动召回_撤回后可再建() {
        RecallResponse first = recallService.createRecall(
                recallRequest(newRequestId("REQ-RC-U1"), 1L, PERIOD));

        assertThatThrownBy(() -> recallService.createRecall(
                recallRequest(newRequestId("REQ-RC-U2"), 1L, PERIOD)))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));

        // 不同花期不受限
        RecallResponse otherPeriod = recallService.createRecall(
                recallRequest(newRequestId("REQ-RC-U3"), 1L, "2026-荆条"));
        assertThat(otherPeriod.status()).isEqualTo("ACTIVE");

        recallService.withdrawRecall(first.id(), withdrawRequest(newRequestId("REQ-WD-U1"), 1L));
        RecallResponse recreated = recallService.createRecall(
                recallRequest(newRequestId("REQ-RC-U4"), 1L, PERIOD));
        assertThat(recreated.status()).isEqualTo("ACTIVE");
        assertThat(recreated.version()).isEqualTo(1);
    }

    @Test
    void 撤回须两名不同质检员与相互独立凭据() {
        RecallResponse recall = recallService.createRecall(
                recallRequest(newRequestId("REQ-RC-I"), 1L, PERIOD));

        assertThatThrownBy(() -> recallService.withdrawRecall(recall.id(),
                new WithdrawRecallRequest(newRequestId("REQ-WD-I1"), 1L,
                        "QC-A", "QC-A", "WCRED-1", "WCRED-2")))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> recallService.withdrawRecall(recall.id(),
                new WithdrawRecallRequest(newRequestId("REQ-WD-I2"), 1L,
                        "QC-A", "QC-B", "WCRED-1", "WCRED-1")))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));

        // 失败不占键：同一 requestId 修正参数后可成功撤回
        RecallResponse withdrawn = recallService.withdrawRecall(recall.id(),
                new WithdrawRecallRequest("REQ-WD-I1", 1L,
                        "QC-A", "QC-B", "WCRED-1", "WCRED-2"));
        assertThat(withdrawn.status()).isEqualTo("WITHDRAWN");
    }

    @Test
    void 撤回版本过期409_重复撤回409() {
        RecallResponse recall = recallService.createRecall(
                recallRequest(newRequestId("REQ-RC-V"), 1L, PERIOD));

        assertThatThrownBy(() -> recallService.withdrawRecall(recall.id(),
                withdrawRequest(newRequestId("REQ-WD-V1"), 2L)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).contains("版本过期");
                });

        RecallResponse withdrawn = recallService.withdrawRecall(recall.id(),
                withdrawRequest(newRequestId("REQ-WD-V2"), 1L));
        assertThat(withdrawn.version()).isEqualTo(2);

        assertThatThrownBy(() -> recallService.withdrawRecall(recall.id(),
                withdrawRequest(newRequestId("REQ-WD-V3"), 2L)))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void 召回幂等_同参重放首次响应_异参409_失败不占键() {
        CreateRecallRequest request = recallRequest("REQ-RC-IDEM", 1L, PERIOD);
        RecallResponse first = recallService.createRecall(request);
        RecallResponse replay = recallService.createRecall(request);
        assertThat(replay).isEqualTo(first);

        assertThatThrownBy(() -> recallService.createRecall(
                new CreateRecallRequest("REQ-RC-IDEM", 1L, PERIOD,
                        "另一个原因", "CRED-X", "QC-CREATE")))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));

        // 失败不占键：蜂场不存在的请求回滚后，同一 requestId 可携带合法参数成功
        assertThatThrownBy(() -> recallService.createRecall(
                recallRequest("REQ-RC-FAIL", 99999L, PERIOD)))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
        RecallResponse recovered = recallService.createRecall(
                recallRequest("REQ-RC-FAIL", 3L, PERIOD));
        assertThat(recovered.status()).isEqualTo("ACTIVE");
    }

    @Test
    void 召回隔离中禁止快检与复检_撤回后恢复() {
        long released = newBatch(1L);
        long suspect = newBatch(1L);
        rapid(suspect, "SMP-R8", "55.00");

        RecallResponse recall = recallService.createRecall(
                recallRequest(newRequestId("REQ-RC-T"), 1L, PERIOD));

        assertThatThrownBy(() -> rapid(released, "SMP-R8B", "10.00"))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).contains("召回隔离");
                });
        assertThatThrownBy(() -> residueTestService.submitRetest(suspect, new RetestRequest(
                newRequestId("REQ-RT-R8"), "SMP-R8-RT", new BigDecimal("20.00"), true)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).contains("召回隔离");
                });

        recallService.withdrawRecall(recall.id(), withdrawRequest(newRequestId("REQ-WD-T"), 1L));
        assertThat(rapid(released, "SMP-R8C", "10.00").conclusion()).isEqualTo("PASS");
    }

    @Test
    void 召回不是一次新的快检_不产生检测记录() {
        long batchId = newBatch(1L);
        rapid(batchId, "SMP-R9", "10.00");
        int testsBefore = harvestBatchService.getBatch(batchId).tests().size();

        recallService.createRecall(recallRequest(newRequestId("REQ-RC-N"), 1L, PERIOD));

        assertThat(harvestBatchService.getBatch(batchId).tests()).hasSize(testsBefore);
        assertThat(harvestBatchService.getBatch(batchId).status()).isEqualTo("RELEASED");
    }
}
