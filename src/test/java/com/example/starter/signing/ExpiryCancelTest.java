package com.example.starter.signing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.example.starter.signing.service.BusinessRuleException;
import com.example.starter.signing.service.SigningService;
import com.example.starter.signing.support.AbstractSigningTest;
import com.example.starter.signing.web.CancelRequest;
import com.example.starter.signing.web.PackageStatusResponse;

/**
 * 到期、取消终态与迟到签名。
 */
class ExpiryCancelTest extends AbstractSigningTest {

    @Test
    void signatureAtOrAfterExpiryIsRejectedAndPackageBecomesExpired() {
        Instant expires = BASE_TIME.plusSeconds(60);
        String hash = docHash("exp-doc");
        String pkgId = createPackage(hash, expires,
                java.util.List.of(new com.example.starter.signing.web.CreatePackageRequest
                        .StepSpec(1, java.util.List.of("alice"))));

        // 推进到到期时刻（边界：不早于 expiresAt 即过期）
        clock.setInstant(expires);
        SigningService.SubmitOutcome outcome = service.submitSignature(pkgId,
                signRequest("exp-1", "alice", 1, hash, 1));
        assertThat(outcome.status()).isEqualTo(HttpStatus.GONE);
        assertThat(((SigningService.ErrorBody) outcome.body()).error())
                .isEqualTo("PACKAGE_EXPIRED");

        PackageStatusResponse status = service.getStatus(pkgId);
        assertThat(status.status()).isEqualTo("EXPIRED");

        // 即使时钟回退到有效期内，终态也不可复活
        clock.setInstant(BASE_TIME);
        SigningService.SubmitOutcome late = service.submitSignature(pkgId,
                signRequest("exp-2", "alice", 1, hash, 1));
        assertThat(late.status()).isEqualTo(HttpStatus.GONE);
    }

    @Test
    void statusQueryLazilyTransitionsToExpired() {
        Instant expires = BASE_TIME.plusSeconds(30);
        String hash = docHash("lazy-exp");
        String pkgId = createPackage(hash, expires,
                java.util.List.of(new com.example.starter.signing.web.CreatePackageRequest
                        .StepSpec(1, java.util.List.of("alice"))));

        clock.advance(Duration.ofSeconds(31));
        PackageStatusResponse status = service.getStatus(pkgId);
        assertThat(status.status()).isEqualTo("EXPIRED");

        var type = jdbc.queryForObject(
                "SELECT event_type FROM sign_event WHERE package_id = ? ORDER BY seq DESC LIMIT 1",
                String.class, pkgId);
        assertThat(type).isEqualTo("EXPIRED");
    }

    @Test
    void cancelledFlowRejectsSignaturesAndRevision() {
        String hash = docHash("cancel-doc");
        String pkgId = createSimpleTwoStepPackage(hash);
        sign(pkgId, "ca-1", "alice", 1, hash);

        service.cancel(pkgId, new CancelRequest("客户撤销"));
        assertThat(service.getStatus(pkgId).status()).isEqualTo("CANCELLED");

        SigningService.SubmitOutcome bob = service.submitSignature(pkgId,
                signRequest("ca-2", "bob", 1, hash, 1));
        assertThat(bob.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(((SigningService.ErrorBody) bob.body()).error())
                .isEqualTo("PACKAGE_CANCELLED");

        assertThatThrownBy(() -> service.revise(pkgId,
                        new com.example.starter.signing.web.RevisionRequest(docHash("cancel-v2"))))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getCode())
                        .isIn("PACKAGE_CANCELLED", "REVISION_AFTER_COMPLETION"));
    }

    @Test
    void lateSignatureAfterCompletionIsRejectedAndCannotReviveFlow() {
        String hash = docHash("late-doc");
        String pkgId = createSimpleTwoStepPackage(hash);
        sign(pkgId, "l-a", "alice", 1, hash);
        sign(pkgId, "l-b", "bob", 1, hash);
        sign(pkgId, "l-c", "carol", 1, hash);
        assertThat(service.getStatus(pkgId).status()).isEqualTo("COMPLETED");

        SigningService.SubmitOutcome late = service.submitSignature(pkgId,
                signRequest("l-late", "alice", 1, hash, 1));
        assertThat(late.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(((SigningService.ErrorBody) late.body()).error())
                .isEqualTo("FLOW_COMPLETED");

        // 状态仍为 COMPLETED，证据包摘要未变
        PackageStatusResponse status = service.getStatus(pkgId);
        assertThat(status.status()).isEqualTo("COMPLETED");
        assertThat(status.evidenceDigest())
                .isEqualTo(service.getEvidence(pkgId).digest());
    }

    @Test
    void signatureJustBeforeExpiryStillSucceeds() {
        Instant expires = BASE_TIME.plusSeconds(60);
        String hash = docHash("edge-doc");
        String pkgId = createPackage(hash, expires,
                java.util.List.of(new com.example.starter.signing.web.CreatePackageRequest
                        .StepSpec(1, java.util.List.of("alice"))));

        clock.setInstant(expires.minusSeconds(1));
        var response = sign(pkgId, "edge-1", "alice", 1, hash);
        assertThat(response.completed()).isTrue();
        assertThat(service.getStatus(pkgId).status()).isEqualTo("COMPLETED");
    }
}
