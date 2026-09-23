package com.example.starter.signing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.example.starter.signing.model.SignatureStatus;
import com.example.starter.signing.repo.SigningRepository;
import com.example.starter.signing.service.BusinessRuleException;
import com.example.starter.signing.support.AbstractSigningTest;
import com.example.starter.signing.web.PackageStatusResponse;
import com.example.starter.signing.web.RevisionRequest;

/**
 * 文档修订：新版本、旧签名失效保留、流程重置第一步；完成后禁止修订。
 */
class RevisionTest extends AbstractSigningTest {

    @Test
    void revisionInvalidatesOldSignaturesAndResetsToFirstStep() {
        String v1 = docHash("contract-v1");
        String pkgId = createSimpleTwoStepPackage(v1);

        // 第一步全部完成（alice、bob），第二步尚未完成
        sign(pkgId, "r-a1", "alice", 1, v1);
        sign(pkgId, "r-b1", "bob", 1, v1);

        String v2 = docHash("contract-v2");
        var revisionResponse = service.revise(pkgId, new RevisionRequest(v2));
        assertThat(revisionResponse.revision()).isEqualTo(2);
        assertThat(revisionResponse.accepted()).isTrue();

        // 当前修订号与文档摘要更新
        PackageStatusResponse status = service.getStatus(pkgId);
        assertThat(status.currentRevision()).isEqualTo(2);
        assertThat(status.documentHash()).isEqualTo(v2);
        // 流程重置到第一步：第二步不开放，第一步签署人在修订 2 下均未签
        assertThat(status.steps().get(1).open()).isFalse();
        assertThat(status.steps().get(0).signers())
                .allSatisfy(signer -> assertThat(signer.signed()).isFalse());

        // 旧签名记录仍保留但全部 INVALIDATED
        var all = jdbc.query(
                "SELECT status FROM signature_record WHERE package_id = ?",
                (rs, n) -> SignatureStatus.valueOf(rs.getString("status")), pkgId);
        assertThat(all).containsExactly(SignatureStatus.INVALIDATED, SignatureStatus.INVALIDATED);

        // 失效字段记录被哪个修订失效
        Long invalidatedBy2 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM signature_record WHERE package_id = ? AND invalidated_by_revision = 2",
                Long.class, pkgId);
        assertThat(invalidatedBy2).isEqualTo(2L);

        // 修订后必须用新修订号与新摘要签名；旧参数被拒
        var stale = service.submitSignature(pkgId,
                signRequest("r-stale", "alice", 1, v1, 1));
        assertThat(stale.status()).isEqualTo(HttpStatus.CONFLICT);

        // 新修订下 alice 可重新从第一步签
        sign(pkgId, "r-a2", "alice", 2, v2);
        PackageStatusResponse after = service.getStatus(pkgId);
        assertThat(after.steps().get(0).signers())
                .anyMatch(s -> s.signer().equals("alice") && s.signed())
                .anyMatch(s -> s.signer().equals("bob") && !s.signed());
    }

    @Test
    void revisionHistoryIsPreservedThroughMultipleRevisions() {
        String v1 = docHash("multi-v1");
        String pkgId = createSimpleTwoStepPackage(v1);
        sign(pkgId, "m-a1", "alice", 1, v1);

        service.revise(pkgId, new RevisionRequest(docHash("multi-v2")));
        sign(pkgId, "m-a2", "alice", 2, docHash("multi-v2"));
        service.revise(pkgId, new RevisionRequest(docHash("multi-v3")));

        // 三次修订的签名记录全部保留：rev1、rev2 失效，rev3 无签名
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM signature_record WHERE package_id = ?", Long.class, pkgId);
        assertThat(total).isEqualTo(2L);
        Long invalidated = jdbc.queryForObject(
                "SELECT COUNT(*) FROM signature_record WHERE package_id = ? AND status = 'INVALIDATED'",
                Long.class, pkgId);
        assertThat(invalidated).isEqualTo(2L);

        // 修订事件链：CREATED、SIGNED、REVISION、SIGNED、REVISION
        var types = jdbc.queryForList(
                "SELECT event_type FROM sign_event WHERE package_id = ? ORDER BY seq",
                String.class, pkgId);
        assertThat(types).containsExactly(
                "PACKAGE_CREATED", "SIGNED", "REVISION_CREATED", "SIGNED", "REVISION_CREATED");
    }

    @Test
    void revisionRejectedAfterCompletion() {
        String v1 = docHash("final-v1");
        String pkgId = createSimpleTwoStepPackage(v1);
        sign(pkgId, "f-a", "alice", 1, v1);
        sign(pkgId, "f-b", "bob", 1, v1);
        sign(pkgId, "f-c", "carol", 1, v1);

        assertThat(service.getStatus(pkgId).status()).isEqualTo("COMPLETED");

        assertThatThrownBy(() -> service.revise(pkgId, new RevisionRequest(docHash("final-v2"))))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getCode())
                        .isEqualTo("REVISION_AFTER_COMPLETION"));
    }

    @Test
    void revisionWithUnchangedHashIsRejected() {
        String v1 = docHash("same-hash");
        String pkgId = createSimpleTwoStepPackage(v1);

        assertThatThrownBy(() -> service.revise(pkgId, new RevisionRequest(v1.toUpperCase())))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));
    }
}
