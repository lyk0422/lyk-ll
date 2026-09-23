package com.example.starter.signing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.example.starter.signing.service.SigningService;
import com.example.starter.signing.support.AbstractSigningTest;

/**
 * 签名提交校验：文档摘要、修订号/期望版本、合成签名。
 */
class SignatureValidationTest extends AbstractSigningTest {

    @Test
    void digestMismatchIsRejected() {
        String hash = docHash("doc-v1");
        String pkgId = createSimpleTwoStepPackage(hash);
        String wrongHash = docHash("tampered-doc");

        SigningService.SubmitOutcome outcome = service.submitSignature(pkgId,
                signRequest("req-digest", "alice", 1, wrongHash, 1));
        assertThat(outcome.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(((SigningService.ErrorBody) outcome.body()).error())
                .isEqualTo("DIGEST_MISMATCH");
    }

    @Test
    void staleRevisionIsRejected() {
        String hash = docHash("doc-rev");
        String pkgId = createSimpleTwoStepPackage(hash);
        service.revise(pkgId, new com.example.starter.signing.web.RevisionRequest(docHash("doc-rev-2")));

        // 当前修订号为 2，提交 revision=1 的签名
        SigningService.SubmitOutcome outcome = service.submitSignature(pkgId,
                signRequest("req-stale", "alice", 1, hash, 1));
        assertThat(outcome.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(((SigningService.ErrorBody) outcome.body()).error())
                .isEqualTo("STALE_VERSION");
    }

    @Test
    void expectedVersionMismatchIsRejected() {
        String hash = docHash("doc-ev");
        String pkgId = createSimpleTwoStepPackage(hash);

        // revision 匹配当前版本 1，但 expectedVersion 传 2
        String signature = verifier.compose("alice", 1, hash);
        com.example.starter.signing.web.SignatureSubmitRequest request =
                new com.example.starter.signing.web.SignatureSubmitRequest(
                        "req-ev", "alice", 1, hash, 2, signature);
        SigningService.SubmitOutcome outcome = service.submitSignature(pkgId, request);
        assertThat(outcome.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(((SigningService.ErrorBody) outcome.body()).error())
                .isEqualTo("STALE_VERSION");
    }

    @Test
    void invalidSyntheticSignatureIsRejected() {
        String hash = docHash("doc-sig");
        String pkgId = createSimpleTwoStepPackage(hash);

        com.example.starter.signing.web.SignatureSubmitRequest request =
                new com.example.starter.signing.web.SignatureSubmitRequest(
                        "req-bad-sig", "alice", 1, hash, 1,
                        "a".repeat(64));
        SigningService.SubmitOutcome outcome = service.submitSignature(pkgId, request);
        assertThat(outcome.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(((SigningService.ErrorBody) outcome.body()).error())
                .isEqualTo("INVALID_SIGNATURE");
    }

    @Test
    void rejectedSubmissionCreatesNoSigningEvent() {
        String hash = docHash("doc-noevent");
        String pkgId = createSimpleTwoStepPackage(hash);

        service.submitSignature(pkgId,
                signRequest("req-bad", "alice", 1, docHash("other"), 1));

        Long events = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sign_event WHERE package_id = ?", Long.class, pkgId);
        Long signatures = jdbc.queryForObject(
                "SELECT COUNT(*) FROM signature_record WHERE package_id = ?", Long.class, pkgId);
        // 仅保留建包事件，没有签名记录
        assertThat(events).isEqualTo(1L);
        assertThat(signatures).isZero();
    }
}
