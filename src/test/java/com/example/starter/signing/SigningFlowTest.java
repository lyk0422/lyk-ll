package com.example.starter.signing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.example.starter.signing.service.BusinessRuleException;
import com.example.starter.signing.service.SigningService;
import com.example.starter.signing.support.AbstractSigningTest;
import com.example.starter.signing.web.ActionResponse;
import com.example.starter.signing.web.PackageStatusResponse;
import com.example.starter.signing.web.SignatureSubmitRequest;

/**
 * 主流程：并行步骤门槛、重复签署、状态推进。
 */
class SigningFlowTest extends AbstractSigningTest {

    @Test
    void parallelStepMustFullyCompleteBeforeNextStepOpens() {
        String hash = docHash("doc-v1");
        String pkgId = createSimpleTwoStepPackage(hash);

        // 第二步在第一步完成前不开放
        SigningService.SubmitOutcome earlyCarol = service.submitSignature(pkgId,
                signRequest("req-carol-early", "carol", 1, hash, 1));
        assertThat(earlyCarol.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(((SigningService.ErrorBody) earlyCarol.body()).error())
                .isEqualTo("STEP_NOT_OPEN");

        // 第一步两个并行签署人之一完成，第二步仍不开放
        sign(pkgId, "req-alice", "alice", 1, hash);
        SigningService.SubmitOutcome carolStillBlocked = service.submitSignature(pkgId,
                signRequest("req-carol-blocked", "carol", 1, hash, 1));
        assertThat(carolStillBlocked.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(((SigningService.ErrorBody) carolStillBlocked.body()).error())
                .isEqualTo("STEP_NOT_OPEN");

        // bob 完成第一步后，第二步开放
        sign(pkgId, "req-bob", "bob", 1, hash);

        PackageStatusResponse status = service.getStatus(pkgId);
        assertThat(status.steps().get(0).open()).isTrue();
        assertThat(status.steps().get(0).signers()).extracting(PackageStatusResponse.SignerStatus::signed)
                .containsExactly(true, true);
        assertThat(status.steps().get(1).open()).isTrue();

        // carol 完成最后一步 -> 流程完成冻结
        ActionResponse last = sign(pkgId, "req-carol", "carol", 1, hash);
        assertThat(last.completed()).isTrue();
        assertThat(last.evidenceDigest()).hasSize(64);

        PackageStatusResponse finalStatus = service.getStatus(pkgId);
        assertThat(finalStatus.status()).isEqualTo("COMPLETED");
        assertThat(finalStatus.evidenceDigest()).isEqualTo(last.evidenceDigest());
    }

    @Test
    void duplicateSignerWithinSameRevisionIsRejected() {
        String hash = docHash("doc-dup");
        String pkgId = createSimpleTwoStepPackage(hash);
        sign(pkgId, "req-a1", "alice", 1, hash);

        SigningService.SubmitOutcome duplicate = service.submitSignature(pkgId,
                signRequest("req-a2", "alice", 1, hash, 1));
        assertThat(duplicate.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(((SigningService.ErrorBody) duplicate.body()).error())
                .isEqualTo("ALREADY_SIGNED");
    }

    @Test
    void signerNotInStepsIsRejected() {
        String hash = docHash("doc-unknown");
        String pkgId = createSimpleTwoStepPackage(hash);

        SigningService.SubmitOutcome outcome = service.submitSignature(pkgId,
                signRequest("req-x", "mallory", 1, hash, 1));
        assertThat(outcome.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(((SigningService.ErrorBody) outcome.body()).error())
                .isEqualTo("SIGNER_NOT_FOUND");
    }

    @Test
    void statusShowsOpenStepAndProgress() {
        String hash = docHash("doc-status");
        String pkgId = createSimpleTwoStepPackage(hash);

        PackageStatusResponse initial = service.getStatus(pkgId);
        assertThat(initial.status()).isEqualTo("PENDING");
        assertThat(initial.currentRevision()).isEqualTo(1);
        assertThat(initial.steps()).hasSize(2);
        assertThat(initial.steps().get(0).open()).isTrue();
        assertThat(initial.steps().get(1).open()).isFalse();

        sign(pkgId, "req-s1", "alice", 1, hash);
        PackageStatusResponse partial = service.getStatus(pkgId);
        List<Boolean> firstStepSigned = partial.steps().get(0).signers().stream()
                .map(PackageStatusResponse.SignerStatus::signed).toList();
        assertThat(firstStepSigned).contains(true, false);
        assertThat(partial.steps().get(1).open()).isFalse();
    }

    @Test
    void missingPackageReturnsNotFound() {
        assertThatThrownBy(() -> service.getStatus("no-such-package"))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }
}
