package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.api.ConflictException;
import com.example.starter.batch.BatchResponse;
import com.example.starter.domain.BatchStatus;
import com.example.starter.domain.TestVerdict;
import com.example.starter.inspection.RapidTestResponse;
import com.example.starter.inspection.SampleResponse;
import org.junit.jupiter.api.Test;

/**
 * 快检判定：恰 50% 疑似、合格即放行（同事务）、重复送检拒绝、单检测链约束。
 */
class RapidTestFlowTest extends AbstractIntegrationTest {

    @Test
    void exactlyFiftyPercentIsSuspectedAndFrozen() {
        BatchResponse batch = registerBatch("t50-reg", "山东寿光", "番茄");
        sample("t50-sam", batch.id(), "有机磷");

        RapidTestResponse response = rapidTest("t50-test", batch.id(), "有机磷", "50");

        assertThat(response.verdict()).isEqualTo(TestVerdict.SUSPECTED);
        assertThat(response.batchStatus()).isEqualTo(BatchStatus.SUSPECTED);
        assertThat(batchRepository.findById(batch.id()).orElseThrow().status())
                .isEqualTo(BatchStatus.SUSPECTED);
    }

    @Test
    void belowFiftyPassesAndReleasesAtomically() {
        BatchResponse batch = registerBatch("t49-reg", "山东寿光", "番茄");
        sample("t49-sam", batch.id(), "有机磷");

        RapidTestResponse response = rapidTest("t49-test", batch.id(), "有机磷", "49.99");

        // 判定与放行同事务完成：检测记录与批次终态同时可见
        assertThat(response.verdict()).isEqualTo(TestVerdict.PASS);
        assertThat(response.batchStatus()).isEqualTo(BatchStatus.RELEASED);
        assertThat(rapidTestRepository.count()).isEqualTo(1);
        assertThat(batchRepository.findById(batch.id()).orElseThrow().status())
                .isEqualTo(BatchStatus.RELEASED);
    }

    @Test
    void rapidTestWithoutSampleRejected() {
        BatchResponse batch = registerBatch("nosam-reg", "山东寿光", "番茄");

        assertThatThrownBy(() -> rapidTest("nosam-test", batch.id(), "有机磷", "30"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("未抽样");
        assertThat(rapidTestRepository.count()).isZero();
    }

    @Test
    void duplicateSampleRejectedAndReplayReturnsFirst() {
        BatchResponse batch = registerBatch("dupsam-reg", "山东寿光", "番茄");
        SampleResponse first = sample("dupsam-1", batch.id(), "有机磷");

        // 同键同参重放：返回首次结果
        SampleResponse replay = sample("dupsam-1", batch.id(), "有机磷");
        assertThat(replay.id()).isEqualTo(first.id());

        // 异键重复送检：拒绝
        assertThatThrownBy(() -> sample("dupsam-2", batch.id(), "有机磷"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("重复送检");
        assertThat(sampleRepository.count()).isEqualTo(1);
    }

    @Test
    void duplicateRapidTestRejected() {
        BatchResponse batch = registerBatch("duptest-reg", "山东寿光", "番茄");
        sample("duptest-sam", batch.id(), "有机磷");
        rapidTest("duptest-1", batch.id(), "有机磷", "50");

        // 疑似冻结后再次送检同一项目被拒绝，且不产生新检测记录
        assertThatThrownBy(() -> rapidTest("duptest-2", batch.id(), "有机磷", "30"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("检测链");
        assertThat(rapidTestRepository.count()).isEqualTo(1);
    }

    @Test
    void secondChainRejectedWhileSuspected() {
        BatchResponse batch = registerBatch("chain-reg", "山东寿光", "番茄");
        sample("chain-sam1", batch.id(), "有机磷");
        sample("chain-sam2", batch.id(), "菊酯类");
        rapidTest("chain-test1", batch.id(), "有机磷", "55");

        // 疑似冻结期间，同一批次不允许第二条检测链
        assertThatThrownBy(() -> rapidTest("chain-test2", batch.id(), "菊酯类", "10"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("检测链");
        assertThat(rapidTestRepository.count()).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentParamsReturns409AndNoNewRecord() {
        BatchResponse batch = registerBatch("replay-reg", "山东寿光", "番茄");
        sample("replay-sam", batch.id(), "有机磷");
        rapidTest("replay-test", batch.id(), "有机磷", "30");

        assertThatThrownBy(() -> rapidTest("replay-test", batch.id(), "有机磷", "80"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("requestId");
        assertThat(rapidTestRepository.count()).isEqualTo(1);
        assertThat(batchRepository.findById(batch.id()).orElseThrow().status())
                .isEqualTo(BatchStatus.RELEASED);
    }

    @Test
    void sameKeySameParamsReplaysFirstResult() {
        BatchResponse batch = registerBatch("idem-reg", "山东寿光", "番茄");
        sample("idem-sam", batch.id(), "有机磷");
        RapidTestResponse first = rapidTest("idem-test", batch.id(), "有机磷", "50");

        RapidTestResponse replay = rapidTest("idem-test", batch.id(), "有机磷", "50");

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.verdict()).isEqualTo(TestVerdict.SUSPECTED);
        assertThat(rapidTestRepository.count()).isEqualTo(1);
    }
}
