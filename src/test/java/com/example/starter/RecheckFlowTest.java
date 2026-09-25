package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDateTime;

import com.example.starter.api.ConflictException;
import com.example.starter.domain.BatchStatus;
import com.example.starter.domain.RecheckResult;
import com.example.starter.recheck.RecheckResponse;
import com.example.starter.recheck.Retention;
import org.junit.jupiter.api.Test;

/**
 * 复检与结案：合格放行、不合格处置留样 48 小时、终态幂等、迟到事件。
 */
class RecheckFlowTest extends AbstractIntegrationTest {

    @Test
    void recheckPassReleasesBatch() {
        long batchId = suspectedBatch("rp", "山东寿光", "番茄");

        RecheckResponse response = recheck("rp-recheck", batchId, RecheckResult.PASS);

        assertThat(response.batchStatus()).isEqualTo(BatchStatus.RELEASED);
        assertThat(response.retentionUntil()).isNull();
        assertThat(batchRepository.findById(batchId).orElseThrow().status())
                .isEqualTo(BatchStatus.RELEASED);
    }

    @Test
    void recheckFailDisposesAndRetains48Hours() {
        long batchId = suspectedBatch("rf", "山东寿光", "番茄");
        LocalDateTime disposedAt = LocalDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC);

        RecheckResponse response = recheck("rf-recheck", batchId, RecheckResult.FAIL);

        assertThat(response.batchStatus()).isEqualTo(BatchStatus.DISPOSED);
        assertThat(response.retentionUntil()).isEqualTo(disposedAt.plusHours(48));
        Retention retention = retentionRepository.findByBatchId(batchId).orElseThrow();
        assertThat(retention.retainUntil()).isEqualTo(disposedAt.plus(Duration.ofHours(48)));
        assertThat(retention.createdAt()).isEqualTo(disposedAt);
    }

    @Test
    void recheckRejectedWhenNotSuspected() {
        long batchId = registerBatch("rn-reg", "山东寿光", "番茄").id();

        assertThatThrownBy(() -> recheck("rn-recheck", batchId, RecheckResult.PASS))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("疑似");
        assertThat(recheckRepository.count()).isZero();
    }

    @Test
    void repeatClosureIsIdempotentSuccess() {
        long batchId = suspectedBatch("rc", "山东寿光", "番茄");
        RecheckResponse first = recheck("rc-recheck", batchId, RecheckResult.FAIL);
        assertThat(first.batchStatus()).isEqualTo(BatchStatus.DISPOSED);

        RecheckResponse replay = recheck("rc-recheck", batchId, RecheckResult.FAIL);

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.batchStatus()).isEqualTo(BatchStatus.DISPOSED);
        assertThat(replay.retentionUntil()).isEqualTo(first.retentionUntil());
        assertThat(replay.late()).isFalse();
        assertThat(recheckRepository.count()).isEqualTo(1);
        assertThat(retentionRepository.count()).isEqualTo(1);
        assertThat(lateEventRepository.count()).isZero();
    }

    @Test
    void sameKeyDifferentResultReturns409() {
        long batchId = suspectedBatch("r409", "山东寿光", "番茄");
        recheck("r409-recheck", batchId, RecheckResult.FAIL);

        assertThatThrownBy(() -> recheck("r409-recheck", batchId, RecheckResult.PASS))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("requestId");
        assertThat(recheckRepository.count()).isEqualTo(1);
        assertThat(batchRepository.findById(batchId).orElseThrow().status())
                .isEqualTo(BatchStatus.DISPOSED);
    }

    @Test
    void lateRecheckAfterClosureRecordsEventOnly() {
        long batchId = suspectedBatch("late-r", "山东寿光", "番茄");
        recheck("late-r-recheck", batchId, RecheckResult.FAIL);

        RecheckResponse late = recheck("late-r-new", batchId, RecheckResult.PASS);

        assertThat(late.late()).isTrue();
        assertThat(late.batchStatus()).isEqualTo(BatchStatus.DISPOSED);
        assertThat(recheckRepository.count()).isEqualTo(1);
        assertThat(lateEventRepository.count()).isEqualTo(1);
        assertThat(lateEventRepository.findByBatchId(batchId).get(0).eventType()).isEqualTo("RECHECK");
        assertThat(batchRepository.findById(batchId).orElseThrow().status())
                .isEqualTo(BatchStatus.DISPOSED);
    }

    @Test
    void lateRapidTestAfterReleaseRecordsEventOnly() {
        long batchId = registerBatch("late-t-reg", "山东寿光", "番茄").id();
        sample("late-t-sam", batchId, "有机磷");
        rapidTest("late-t-test", batchId, "有机磷", "20");
        assertThat(batchRepository.findById(batchId).orElseThrow().status())
                .isEqualTo(BatchStatus.RELEASED);

        var late = rapidTest("late-t-new", batchId, "有机磷", "99");

        assertThat(late.late()).isTrue();
        assertThat(late.batchStatus()).isEqualTo(BatchStatus.RELEASED);
        assertThat(rapidTestRepository.count()).isEqualTo(1);
        assertThat(lateEventRepository.count()).isEqualTo(1);
        assertThat(lateEventRepository.findByBatchId(batchId).get(0).eventType()).isEqualTo("RAPID_TEST");
    }
}
