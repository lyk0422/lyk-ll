package com.example.starter.signing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.example.starter.signing.service.BusinessRuleException;
import com.example.starter.signing.service.SigningService;
import com.example.starter.signing.support.AbstractSigningTest;
import com.example.starter.signing.web.ActionResponse;
import com.example.starter.signing.web.SignatureSubmitRequest;

/**
 * 幂等请求指纹：同键同参返回首次结果；同键异参 409 且不新增事件。
 */
class IdempotencyTest extends AbstractSigningTest {

    @Test
    void sameKeySameParametersReplaysFirstResult() {
        String hash = docHash("doc-idem");
        String pkgId = createSimpleTwoStepPackage(hash);
        SignatureSubmitRequest request = signRequest("idem-1", "alice", 1, hash, 1);

        SigningService.SubmitOutcome first = service.submitSignature(pkgId, request);
        assertThat(first.status()).isEqualTo(HttpStatus.OK);
        ActionResponse firstBody = (ActionResponse) first.body();

        // 完全相同参数重放，返回首次结果（accepted、at 一致）
        SigningService.SubmitOutcome replay = service.submitSignature(pkgId, request);
        assertThat(replay.status()).isEqualTo(HttpStatus.OK);
        ActionResponse replayBody = (ActionResponse) replay.body();
        assertThat(replayBody.accepted()).isEqualTo(firstBody.accepted());
        assertThat(replayBody.at()).isEqualTo(firstBody.at());
        assertThat(replayBody.evidenceDigest()).isEqualTo(firstBody.evidenceDigest());

        // 只有一条有效签名、一条 SIGNED 事件，重放未重复落库
        Long signatures = jdbc.queryForObject(
                "SELECT COUNT(*) FROM signature_record WHERE package_id = ? AND signer = 'alice'",
                Long.class, pkgId);
        assertThat(signatures).isEqualTo(1L);
        Long signedEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sign_event WHERE package_id = ? AND event_type = 'SIGNED'",
                Long.class, pkgId);
        assertThat(signedEvents).isEqualTo(1L);
    }

    @Test
    void sameKeyDifferentParametersReturnsConflictAndAddsNoEvent() {
        String hash = docHash("doc-idem-conflict");
        String pkgId = createSimpleTwoStepPackage(hash);

        SignatureSubmitRequest first = signRequest("idem-2", "alice", 1, hash, 1);
        service.submitSignature(pkgId, first);

        long eventsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sign_event WHERE package_id = ?", Long.class, pkgId);

        // 同 requestId 但更换签署人为 bob（异参）
        SignatureSubmitRequest conflicting = signRequest("idem-2", "bob", 1, hash, 1);
        assertThatThrownBy(() -> service.submitSignature(pkgId, conflicting))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> {
                    BusinessRuleException bre = (BusinessRuleException) ex;
                    assertThat(bre.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(bre.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
                });

        long eventsAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sign_event WHERE package_id = ?", Long.class, pkgId);
        assertThat(eventsAfter).isEqualTo(eventsBefore);
    }

    @Test
    void rejectedResultIsAlsoReplayedWithSameStatus() {
        String hash = docHash("doc-idem-replay-reject");
        String pkgId = createSimpleTwoStepPackage(hash);

        // carol 的第二步尚未开放 -> 首次 409 并记录
        SignatureSubmitRequest blocked = signRequest("idem-3", "carol", 1, hash, 1);
        SigningService.SubmitOutcome first = service.submitSignature(pkgId, blocked);
        assertThat(first.status()).isEqualTo(HttpStatus.CONFLICT);

        // 同参重放，仍然返回首次 409（即使现在仍未开放，结果一致）
        SigningService.SubmitOutcome replay = service.submitSignature(pkgId, blocked);
        assertThat(replay.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(((SigningService.ErrorBody) replay.body()).error())
                .isEqualTo("STEP_NOT_OPEN");
    }
}
