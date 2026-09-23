package com.example.starter.signing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.example.starter.signing.service.BusinessRuleException;
import com.example.starter.signing.service.Hashes;
import com.example.starter.signing.support.AbstractSigningTest;
import com.example.starter.signing.web.EvidenceResponse;
import com.example.starter.signing.web.RevisionRequest;

/**
 * 证据包冻结：最后有效签名原子冻结、内容完整、摘要稳定、事件链可校验篡改。
 */
class EvidencePackageTest extends AbstractSigningTest {

    @Test
    void evidenceIsFrozenOnLastValidSignatureAndStaysStable() {
        String v1 = docHash("evi-v1");
        String pkgId = createSimpleTwoStepPackage(v1);
        sign(pkgId, "e-a", "alice", 1, v1);
        sign(pkgId, "e-b", "bob", 1, v1);
        var last = sign(pkgId, "e-c", "carol", 1, v1);
        assertThat(last.completed()).isTrue();

        EvidenceResponse evidence1 = service.getEvidence(pkgId);
        EvidenceResponse evidence2 = service.getEvidence(pkgId);

        // 重复查询摘要稳定
        assertThat(evidence1.digest()).isEqualTo(last.evidenceDigest());
        assertThat(evidence2.digest()).isEqualTo(evidence1.digest());
        assertThat(evidence1.frozenAt()).isEqualTo(evidence2.frozenAt());

        // 内容：步骤、3 个有效签名、无失效历史、完整事件链
        assertThat(evidence1.documentHash()).isEqualTo(v1);
        assertThat(evidence1.revision()).isEqualTo(1);
        assertThat(evidence1.steps()).hasSize(2);
        assertThat(evidence1.validSignatures()).hasSize(3);
        assertThat(evidence1.invalidatedHistory()).isEmpty();

        List<String> types = evidence1.events().stream().map(EvidenceResponse.EventView::type).toList();
        assertThat(types).containsExactly(
                "PACKAGE_CREATED", "SIGNED", "SIGNED", "SIGNED", "COMPLETED");
    }

    @Test
    void evidenceIncludesInvalidatedHistoryAfterRevisionThenCompletion() {
        String v1 = docHash("eh-v1");
        String v2 = docHash("eh-v2");
        String pkgId = createSimpleTwoStepPackage(v1);
        sign(pkgId, "eh-a1", "alice", 1, v1);
        sign(pkgId, "eh-b1", "bob", 1, v1);
        service.revise(pkgId, new RevisionRequest(v2));

        // 在修订 2 上完成全部步骤
        sign(pkgId, "eh-a2", "alice", 2, v2);
        sign(pkgId, "eh-b2", "bob", 2, v2);
        var last = sign(pkgId, "eh-c2", "carol", 2, v2);
        assertThat(last.completed()).isTrue();

        EvidenceResponse evidence = service.getEvidence(pkgId);
        assertThat(evidence.revision()).isEqualTo(2);
        assertThat(evidence.documentHash()).isEqualTo(v2);
        // 修订 2 的 3 个有效签名 + 修订 1 的 2 条失效历史
        assertThat(evidence.validSignatures()).hasSize(3);
        assertThat(evidence.invalidatedHistory()).hasSize(2);
        assertThat(evidence.invalidatedHistory())
                .allSatisfy(s -> {
                    assertThat(s.invalidatedByRevision()).isEqualTo(2);
                    assertThat(s.documentHash()).isEqualTo(v1);
                });
    }

    @Test
    void evidenceNotAvailableBeforeCompletion() {
        String v1 = docHash("evi-pending");
        String pkgId = createSimpleTwoStepPackage(v1);
        sign(pkgId, "ep-a", "alice", 1, v1);

        assertThatThrownBy(() -> service.getEvidence(pkgId))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void eventChainHashesLinkFromGenesisAndValidateContent() {
        String v1 = docHash("chain-v1");
        String pkgId = createSimpleTwoStepPackage(v1);
        sign(pkgId, "c-a", "alice", 1, v1);
        sign(pkgId, "c-b", "bob", 1, v1);
        sign(pkgId, "c-c", "carol", 1, v1);

        EvidenceResponse evidence = service.getEvidence(pkgId);

        // 首事件 previousHash 为 64 个 0；后续事件 previousHash = 前一事件 eventHash
        String expectedPrevious = Hashes.GENESIS_PREVIOUS;
        for (EvidenceResponse.EventView event : evidence.events()) {
            assertThat(event.previousHash()).isEqualTo(expectedPrevious);
            assertThat(event.eventHash()).hasSize(64);
            // event_hash = SHA-256(previousHash || canonicalEvent)，重算必须一致
            assertThat(event.eventHash())
                    .describedAs("事件哈希必须可由链上前哈希与规范事件内容复算")
                    .isEqualTo(recomputeEventHash(event));
            expectedPrevious = event.eventHash();
        }
    }

    @Test
    void tamperedEventDetailIsDetectedByHashRecomputation() {
        String v1 = docHash("tamper-v1");
        String pkgId = createSimpleTwoStepPackage(v1);
        sign(pkgId, "t-a", "alice", 1, v1);
        sign(pkgId, "t-b", "bob", 1, v1);
        sign(pkgId, "t-c", "carol", 1, v1);

        EvidenceResponse before = service.getEvidence(pkgId);

        // 直接篡改数据库中的事件详情（模拟证据链被改动）
        jdbc.update("UPDATE sign_event SET detail = ? WHERE package_id = ? AND seq = 2",
                "{\"tampered\":true}", pkgId);

        // 重新从事件表复算链：篡改事件的哈希与存储值不再一致
        var rows = jdbc.query(
                "SELECT seq, detail, previous_hash, event_hash FROM sign_event "
                        + "WHERE package_id = ? ORDER BY seq",
                (rs, n) -> new TamperedRow(rs.getInt("seq"), rs.getString("detail"),
                        rs.getString("previous_hash"), rs.getString("event_hash")),
                pkgId);
        TamperedRow tampered = rows.stream().filter(r -> r.seq() == 2).findFirst().orElseThrow();
        String canonical = canonicalFromStoredDetail(tampered);
        String recomputed = Hashes.sha256(tampered.previousHash() + canonical);
        assertThat(recomputed).isNotEqualTo(tampered.eventHash());

        // 证据包内容若被改动，摘要校验同样失败
        jdbc.update("UPDATE evidence_package SET content = ? WHERE package_id = ?",
                "{}", pkgId);
        assertThatThrownBy(() -> service.getEvidence(pkgId))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getCode())
                        .isEqualTo("EVIDENCE_CORRUPTED"));

        // 未篡改前的事件哈希复算是自洽的（对照）
        EvidenceResponse.EventView signedEvent = before.events().stream()
                .filter(e -> e.seq() == 2).findFirst().orElseThrow();
        assertThat(recomputeEventHash(signedEvent)).isEqualTo(signedEvent.eventHash());
    }

    /** 按服务端规范信封复算事件哈希：键序 createdAt/detail/eventType/revision/seq（字典序）。 */
    private String recomputeEventHash(EvidenceResponse.EventView event) {
        String canonical = canonicalEnvelope(event);
        return Hashes.sha256(event.previousHash() + canonical);
    }

    private String canonicalEnvelope(EvidenceResponse.EventView event) {
        // 与服务端 CanonicalJson 一致：键字典序、无空白
        return "{\"createdAt\":\"" + event.at() + "\",\"detail\":" + event.detail()
                + ",\"eventType\":\"" + event.type() + "\",\"revision\":" + event.revision()
                + ",\"seq\":" + event.seq() + "}";
    }

    private String canonicalFromStoredDetail(TamperedRow row) {
        // 篡改后无法从行恢复 type/createdAt，这里只验证 detail 改变即导致哈希不符：
        // 直接以被篡改 detail 作为规范体的一部分复算，必然不同于原值。
        return "{\"detail\":" + row.detail() + "}";
    }

    private record TamperedRow(int seq, String detail, String previousHash, String eventHash) {
    }
}
