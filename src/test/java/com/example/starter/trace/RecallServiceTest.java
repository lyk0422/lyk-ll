package com.example.starter.trace;

import com.example.starter.trace.dto.CreateHarvestBatchRequest;
import com.example.starter.trace.dto.CreateProcessingBatchRequest;
import com.example.starter.trace.dto.CreateRecallRequest;
import com.example.starter.trace.dto.ProcessingBatchResponse;
import com.example.starter.trace.dto.RecallResponse;
import com.example.starter.trace.dto.RetestRequest;
import com.example.starter.trace.dto.WithdrawRecallRequest;
import com.example.starter.trace.service.RecallService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 来源召回业务测试：快照冻结、加工拦截、后来批次追加、双来源召回、双人撤回、
 * 版本与幂等、撤回后药残阻断仍然生效。
 */
class RecallServiceTest extends BaseTraceTest {

    @Autowired
    private RecallService recallService;

    @Test
    void 创建召回_快照冻结相关采收批并标记已引用加工批() {
        long released = newBatch(1L);
        rapid(released, "SMP-RC1", "10.00");
        ProcessingBatchResponse processing = newProcessing(released);
        long registered = newBatch(1L);
        long otherApiary = newBatch(2L);

        RecallResponse recall = newRecall(1L, PERIOD, "RC1");

        assertThat(recall.status()).isEqualTo("ACTIVE");
        assertThat(recall.version()).isEqualTo(1);
        assertThat(recall.createdAt()).isEqualTo(LocalDateTime.of(2026, 6, 1, 2, 0));
        assertThat(recall.withdrawnAt()).isNull();
        assertThat(recall.initialFrozenLinks())
                .extracting(RecallResponse.LinkView::harvestBatchId)
                .containsExactlyInAnyOrder(released, registered);
        assertThat(recall.initialFrozenLinks())
                .extracting(RecallResponse.LinkView::harvestBatchId)
                .doesNotContain(otherApiary);
        assertThat(recall.initialFrozenLinks()).allMatch(l -> l.status().equals("ACTIVE"));
        assertThat(recall.appendedLinks()).isEmpty();
        assertThat(recall.processingMarks())
                .extracting(RecallResponse.ProcessingMarkView::processingBatchId)
                .containsExactly(processing.id());
    }

    @Test
    void 同蜂场同花期已存在活动召回_拒绝409_撤回后可再建() {
        newRecall(1L, PERIOD, "RC2A");
        assertThatThrownBy(() -> newRecall(1L, PERIOD, "RC2B"))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).contains("已存在活动召回");
                });
        // 不同花期不受限
        newRecall(1L, "2026-荆条", "RC2C");

        RecallResponse first = recallService.getRecall(
                recallService.createRecall(new CreateRecallRequest("REQ-RC2D", 1L, "2026-油菜",
                        "合成原因", "CRED-RC2D", "QC-RC2D")).id());
        RecallResponse withdrawn = withdraw(first.id(), 1, "RC2D");
        assertThat(withdrawn.status()).isEqualTo("WITHDRAWN");
        // 撤回后同来源可重新建立召回
        RecallResponse again = newRecall(1L, "2026-油菜", "RC2E");
        assertThat(again.status()).isEqualTo("ACTIVE");
    }

    @Test
    void 召回后加工受理被拦截_即使采收批已放行() {
        long released = newBatch(1L);
        rapid(released, "SMP-RC3", "10.00");
        newRecall(1L, PERIOD, "RC3");

        assertThatThrownBy(() -> newProcessing(released))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).contains("召回隔离");
                });
    }

    @Test
    void 成功加工与后来召回共存_加工被查入影响范围且新引用被拦() {
        long released = newBatch(1L);
        rapid(released, "SMP-RC4", "10.00");
        ProcessingBatchResponse processing = newProcessing(released);

        RecallResponse recall = newRecall(1L, PERIOD, "RC4");
        assertThat(recall.processingMarks())
                .extracting(RecallResponse.ProcessingMarkView::processingBatchId)
                .containsExactly(processing.id());
        assertThatThrownBy(() -> newProcessing(released))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void 后来登记的含来源批自动追加关联_放行后仍被召回拦截() {
        RecallResponse recall = newRecall(1L, PERIOD, "RC5");
        long late = newBatch(1L);

        RecallResponse detail = recallService.getRecall(recall.id());
        assertThat(detail.initialFrozenLinks()).isEmpty();
        assertThat(detail.appendedLinks())
                .extracting(RecallResponse.LinkView::harvestBatchId)
                .containsExactly(late);

        // 快检合格放行也不能穿透召回隔离
        rapid(late, "SMP-RC5", "8.00");
        assertThatThrownBy(() -> newProcessing(late))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).contains("召回隔离");
                });
    }

    @Test
    void 双来源召回_撤回一条仍被另一条隔离_全部撤回后放行可加工() {
        long batch = newBatch6040();
        rapid(batch, "SMP-RC6", "10.00");
        RecallResponse recall1 = newRecall(1L, PERIOD, "RC6A");
        RecallResponse recall2 = newRecall(2L, PERIOD, "RC6B");

        assertThatThrownBy(() -> newProcessing(batch))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));

        RecallResponse withdrawn1 = withdraw(recall1.id(), 1, "RC6A");
        assertThat(withdrawn1.status()).isEqualTo("WITHDRAWN");
        assertThat(withdrawn1.version()).isEqualTo(2);
        assertThat(withdrawn1.initialFrozenLinks()).allMatch(l -> l.status().equals("RELEASED"));
        // 另一条召回的关联不受影响
        RecallResponse stillActive = recallService.getRecall(recall2.id());
        assertThat(stillActive.status()).isEqualTo("ACTIVE");
        assertThat(stillActive.initialFrozenLinks()).allMatch(l -> l.status().equals("ACTIVE"));
        // 仍被第二条召回隔离
        assertThatThrownBy(() -> newProcessing(batch))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).contains("召回隔离");
                });

        withdraw(recall2.id(), 1, "RC6B");
        ProcessingBatchResponse processing = newProcessing(batch);
        assertThat(processing.harvestBatchIds()).containsExactly(batch);
    }

    @Test
    void 撤回须两名不同质检员且凭据独立() {
        RecallResponse recall = newRecall(1L, PERIOD, "RC7");
        assertThatThrownBy(() -> recallService.withdrawRecall(recall.id(), new WithdrawRecallRequest(
                "REQ-RC7-SAME-QC", 1, "QC-甲", "CRED-1", "QC-甲", "CRED-2")))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).contains("不同质检员");
                });
        assertThatThrownBy(() -> recallService.withdrawRecall(recall.id(), new WithdrawRecallRequest(
                "REQ-RC7-SAME-CRED", 1, "QC-甲", "CRED-1", "QC-乙", "CRED-1")))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).contains("独立的凭据");
                });
        // 校验失败不占幂等键，也不影响召回状态
        assertThat(recallService.getRecall(recall.id()).status()).isEqualTo("ACTIVE");
    }

    @Test
    void 撤回版本过期409_成功撤回版本加一_重复撤回409() {
        RecallResponse recall = newRecall(1L, PERIOD, "RC8");
        assertThatThrownBy(() -> withdraw(recall.id(), 2, "RC8-STALE"))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).contains("版本已过期");
                });

        RecallResponse withdrawn = withdraw(recall.id(), 1, "RC8");
        assertThat(withdrawn.version()).isEqualTo(2);
        assertThat(withdrawn.withdrawnAt()).isEqualTo(LocalDateTime.of(2026, 6, 1, 2, 0));

        assertThatThrownBy(() -> withdraw(recall.id(), 2, "RC8-AGAIN"))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).contains("已撤回");
                });
    }

    @Test
    void 撤回召回后_原药残疑似未合格复检仍不许加工() {
        long batch = newBatch(1L);
        rapid(batch, "SMP-RC9", "55.00");
        RecallResponse recall = newRecall(1L, PERIOD, "RC9");
        withdraw(recall.id(), 1, "RC9");

        residueTestService.submitRetest(batch, new RetestRequest(
                "REQ-RC9-RT", "SMP-RC9-RT", new BigDecimal("61.00"), false));
        assertThatThrownBy(() -> newProcessing(batch))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).contains("冻结");
                });
    }

    @Test
    void 召回幂等_同键同参重放首次响应_异参409_失败不占键() {
        CreateRecallRequest request = new CreateRecallRequest(
                "REQ-RC10", 1L, PERIOD, "合成原因", "CRED-RC10", "QC-RC10");
        RecallResponse first = recallService.createRecall(request);
        RecallResponse replay = recallService.createRecall(request);
        assertThat(replay).isEqualTo(first);

        assertThatThrownBy(() -> recallService.createRecall(new CreateRecallRequest(
                "REQ-RC10", 1L, PERIOD, "改过的原因", "CRED-RC10", "QC-RC10")))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));

        // 业务失败不占幂等键：同键修正参数后可成功
        assertThatThrownBy(() -> recallService.createRecall(new CreateRecallRequest(
                "REQ-RC10-FAIL", 999L, PERIOD, "合成原因", "CRED-X", "QC-X")))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        RecallResponse recovered = recallService.createRecall(new CreateRecallRequest(
                "REQ-RC10-FAIL", 2L, PERIOD, "合成原因", "CRED-X", "QC-X"));
        assertThat(recovered.apiaryId()).isEqualTo(2L);
    }

    @Test
    void 撤回幂等_同键同参重放_异参409() {
        RecallResponse recall = newRecall(1L, PERIOD, "RC11");
        WithdrawRecallRequest request = new WithdrawRecallRequest(
                "REQ-RC11-WD", 1, "QC-甲", "CRED-甲", "QC-乙", "CRED-乙");
        RecallResponse first = recallService.withdrawRecall(recall.id(), request);
        RecallResponse replay = recallService.withdrawRecall(recall.id(), request);
        assertThat(replay).isEqualTo(first);

        assertThatThrownBy(() -> recallService.withdrawRecall(recall.id(), new WithdrawRecallRequest(
                "REQ-RC11-WD", 2, "QC-甲", "CRED-甲", "QC-乙", "CRED-乙")))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void 召回指定不存在蜂场_400_查询不存在召回_404() {
        assertThatThrownBy(() -> newRecall(999L, PERIOD, "RC12"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> recallService.getRecall(99999L))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    private RecallResponse newRecall(long apiaryId, String flowerPeriod, String suffix) {
        return recallService.createRecall(new CreateRecallRequest(
                "REQ-RC-" + suffix + "-" + System.nanoTime(), apiaryId, flowerPeriod,
                "合成原因-" + suffix, "CRED-" + suffix, "QC-" + suffix));
    }

    private RecallResponse withdraw(long recallId, int expectedVersion, String suffix) {
        return recallService.withdrawRecall(recallId, new WithdrawRecallRequest(
                "REQ-WD-" + suffix + "-" + System.nanoTime(), expectedVersion,
                "QC-甲-" + suffix, "CRED-甲-" + suffix, "QC-乙-" + suffix, "CRED-乙-" + suffix));
    }

    private long newBatch6040() {
        return harvestBatchService.createBatch(new CreateHarvestBatchRequest(
                "REQ-B6040-" + System.nanoTime(), "HB-6040-" + System.nanoTime(), PERIOD,
                List.of(new CreateHarvestBatchRequest.ContributionItem(1L, new BigDecimal("60.00")),
                        new CreateHarvestBatchRequest.ContributionItem(2L, new BigDecimal("40.00")))))
                .id();
    }

    private ProcessingBatchResponse newProcessing(long... harvestBatchIds) {
        return processingBatchService.createProcessingBatch(new CreateProcessingBatchRequest(
                "REQ-PB-" + System.nanoTime(), "PB-" + System.nanoTime(),
                LongStream.of(harvestBatchIds).boxed().toList()));
    }
}
