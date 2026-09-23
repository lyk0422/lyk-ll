package com.example.starter.signing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.signing.Contracts.CancelPackageRequest;
import com.example.starter.signing.Contracts.CreatePackageRequest;
import com.example.starter.signing.Contracts.EvidencePackage;
import com.example.starter.signing.Contracts.PackageState;
import com.example.starter.signing.Contracts.RevisePackageRequest;
import com.example.starter.signing.Contracts.SignatureResult;
import com.example.starter.signing.Contracts.Step;
import com.example.starter.signing.Contracts.SubmitSignatureRequest;
import com.example.starter.signing.SigningRepository.EventRow;

/**
 * 签署流程业务测试：覆盖并行步骤门槛、摘要校验、请求指纹幂等、修订失效、
 * 最终冻结、事件链篡改、到期与取消后的迟到签名等场景（H2 内存库真实验证）。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class SigningServiceTest {

    private static final String DOC_V1 = Hashing.sha256Hex("doc-v1");
    private static final String DOC_V2 = Hashing.sha256Hex("doc-v2");

    @Autowired
    private SigningService service;

    @Autowired
    private SigningRepository repository;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    private final AtomicInteger requestSeq = new AtomicInteger();

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM request_log");
        jdbc.update("DELETE FROM signing_event");
        jdbc.update("DELETE FROM signature");
        jdbc.update("DELETE FROM package_step");
        jdbc.update("DELETE FROM signing_package");
        clock.set(Instant.parse("2026-09-23T00:00:00Z"));
    }

    private String newPackageId() {
        return UUID.randomUUID().toString();
    }

    private String newRequestId() {
        return "req-" + requestSeq.incrementAndGet() + "-" + UUID.randomUUID();
    }

    /** 两步流程：第一步 alice/bob 并行，第二步 carol。 */
    private PackageState createTwoStepPackage(String packageId) {
        return service.createPackage(new CreatePackageRequest(
                packageId, DOC_V1, Instant.parse("2026-09-24T00:00:00Z"),
                List.of(new Step(List.of("alice", "bob")), new Step(List.of("carol")))));
    }

    private SubmitSignatureRequest signRequest(PackageState state, String signer) {
        return signRequest(state, signer, newRequestId());
    }

    private SubmitSignatureRequest signRequest(PackageState state, String signer, String requestId) {
        return new SubmitSignatureRequest(requestId, signer, state.currentRevision(),
                state.docSha256(), state.version(),
                SyntheticSignatures.sign(state.packageId(), state.currentRevision(), signer, state.docSha256()));
    }

    @Test
    void parallelStepRequiresAllSignersBeforeNextStepOpens() {
        PackageState state = createTwoStepPackage(newPackageId());

        // 第一步未集齐前，第二步签署人被拒绝（步骤未开放）。
        assertThatThrownBy(() -> service.submitSignature(state.packageId(), signRequest(state, "carol")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo("STEP_NOT_OPEN");
                });

        SignatureResult alice = service.submitSignature(state.packageId(), signRequest(state, "alice"));
        assertThat(alice.packageState().currentStep()).isEqualTo(0);
        assertThat(alice.packageState().status()).isEqualTo("ACTIVE");

        // bob 完成后第一步关闭，流程推进到第二步。
        SignatureResult bob = service.submitSignature(state.packageId(),
                signRequest(alice.packageState(), "bob"));
        assertThat(bob.packageState().currentStep()).isEqualTo(1);

        SignatureResult carol = service.submitSignature(state.packageId(),
                signRequest(bob.packageState(), "carol"));
        assertThat(carol.packageState().status()).isEqualTo("COMPLETED");
        assertThat(carol.packageState().evidenceFrozen()).isTrue();
        assertThat(carol.packageState().evidenceHash()).hasSize(64);
    }

    @Test
    void digestMismatchIsRejected() {
        PackageState state = createTwoStepPackage(newPackageId());
        SubmitSignatureRequest bad = new SubmitSignatureRequest(newRequestId(), "alice",
                state.currentRevision(), Hashing.sha256Hex("other-doc"), state.version(),
                SyntheticSignatures.sign(state.packageId(), state.currentRevision(), "alice",
                        Hashing.sha256Hex("other-doc")));
        assertThatThrownBy(() -> service.submitSignature(state.packageId(), bad))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.code()).isEqualTo("DIGEST_MISMATCH");
                });
    }

    @Test
    void invalidSyntheticSignatureIsRejected() {
        PackageState state = createTwoStepPackage(newPackageId());
        SubmitSignatureRequest bad = new SubmitSignatureRequest(newRequestId(), "alice",
                state.currentRevision(), state.docSha256(), state.version(),
                Hashing.sha256Hex("forged-signature"));
        assertThatThrownBy(() -> service.submitSignature(state.packageId(), bad))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo("SIGNATURE_INVALID"));
    }

    @Test
    void unknownSignerAndDuplicateSignerAreRejected() {
        PackageState state = createTwoStepPackage(newPackageId());
        assertThatThrownBy(() -> service.submitSignature(state.packageId(), signRequest(state, "mallory")))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo("UNKNOWN_SIGNER"));

        SignatureResult first = service.submitSignature(state.packageId(), signRequest(state, "alice"));
        assertThatThrownBy(() -> service.submitSignature(state.packageId(),
                signRequest(first.packageState(), "alice")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo("DUPLICATE_SIGNER");
                });
    }

    @Test
    void sameRequestKeySameParamsReturnsFirstResultWithoutNewEvents() {
        PackageState state = createTwoStepPackage(newPackageId());
        String requestId = newRequestId();
        SubmitSignatureRequest request = signRequest(state, "alice", requestId);

        SignatureResult first = service.submitSignature(state.packageId(), request);
        SignatureResult replayed = service.submitSignature(state.packageId(), request);

        assertThat(replayed.packageState()).isEqualTo(first.packageState());
        int eventCount = countEvents(state.packageId());
        service.submitSignature(state.packageId(), request);
        assertThat(countEvents(state.packageId())).isEqualTo(eventCount);
        assertThat(countSignatures(state.packageId())).isEqualTo(1);
    }

    @Test
    void sameRequestKeyDifferentParamsReturns409WithoutNewEvents() {
        PackageState state = createTwoStepPackage(newPackageId());
        String requestId = newRequestId();
        service.submitSignature(state.packageId(), signRequest(state, "alice", requestId));
        int eventsBefore = countEvents(state.packageId());

        PackageState current = service.getState(state.packageId());
        SubmitSignatureRequest different = signRequest(current, "bob", requestId);
        assertThatThrownBy(() -> service.submitSignature(state.packageId(), different))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo("REQUEST_FINGERPRINT_MISMATCH");
                });
        assertThat(countEvents(state.packageId())).isEqualTo(eventsBefore);
        assertThat(countSignatures(state.packageId())).isEqualTo(1);
    }

    @Test
    void revisionInvalidatesOldSignaturesAndResetsToFirstStep() {
        PackageState state = createTwoStepPackage(newPackageId());
        SignatureResult alice = service.submitSignature(state.packageId(), signRequest(state, "alice"));
        SignatureResult bob = service.submitSignature(state.packageId(),
                signRequest(alice.packageState(), "bob"));
        assertThat(bob.packageState().currentStep()).isEqualTo(1);

        PackageState revised = service.revise(state.packageId(),
                new RevisePackageRequest(DOC_V2, bob.packageState().version()));
        assertThat(revised.currentRevision()).isEqualTo(2);
        assertThat(revised.docSha256()).isEqualTo(DOC_V2);
        assertThat(revised.currentStep()).isEqualTo(0);
        assertThat(revised.steps()).allSatisfy(step ->
                step.signers().forEach(signer -> assertThat(signer.signed()).isFalse()));

        // 旧修订签名保留为失效历史。
        List<SigningRepository.SignatureRow> signatures = repository.findSignatures(state.packageId());
        assertThat(signatures).hasSize(2);
        assertThat(signatures).allSatisfy(s -> assertThat(s.status()).isEqualTo("INVALIDATED"));

        // 旧修订号的迟到签名被拒绝。
        SubmitSignatureRequest stale = new SubmitSignatureRequest(newRequestId(), "carol", 1,
                DOC_V1, revised.version(), SyntheticSignatures.sign(state.packageId(), 1, "carol", DOC_V1));
        assertThatThrownBy(() -> service.submitSignature(state.packageId(), stale))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo("REVISION_MISMATCH"));

        // 新修订下可重新签署并完成。
        PackageState s1 = service.submitSignature(state.packageId(), signRequest(revised, "alice")).packageState();
        PackageState s2 = service.submitSignature(state.packageId(), signRequest(s1, "bob")).packageState();
        PackageState s3 = service.submitSignature(state.packageId(), signRequest(s2, "carol")).packageState();
        assertThat(s3.status()).isEqualTo("COMPLETED");

        EvidencePackage evidence = service.getEvidence(state.packageId());
        assertThat(evidence.finalRevision()).isEqualTo(2);
        assertThat(evidence.validSignatures()).hasSize(3);
        assertThat(evidence.invalidatedSignatures()).hasSize(2);
    }

    @Test
    void completedPackageFreezesEvidenceWithStableHashAndForbidsRevision() {
        PackageState state = createTwoStepPackage(newPackageId());
        PackageState s1 = service.submitSignature(state.packageId(), signRequest(state, "alice")).packageState();
        PackageState s2 = service.submitSignature(state.packageId(), signRequest(s1, "bob")).packageState();
        PackageState done = service.submitSignature(state.packageId(), signRequest(s2, "carol")).packageState();
        assertThat(done.status()).isEqualTo("COMPLETED");

        EvidencePackage first = service.getEvidence(state.packageId());
        EvidencePackage second = service.getEvidence(state.packageId());
        assertThat(second).isEqualTo(first);
        assertThat(first.evidenceHash()).hasSize(64);
        assertThat(first.events()).isNotEmpty();
        assertThat(EventChain.verify(state.packageId(), repository.findEvents(state.packageId()))).isTrue();

        // 最终完成后禁止修订与再签名。
        assertThatThrownBy(() -> service.revise(state.packageId(),
                new RevisePackageRequest(DOC_V2, done.version())))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo("PACKAGE_NOT_ACTIVE"));
        assertThatThrownBy(() -> service.submitSignature(state.packageId(), signRequest(done, "alice")))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo("PACKAGE_NOT_ACTIVE"));
    }

    @Test
    void eventChainDetectsTampering() {
        PackageState state = createTwoStepPackage(newPackageId());
        service.submitSignature(state.packageId(), signRequest(state, "alice"));
        List<EventRow> events = repository.findEvents(state.packageId());
        assertThat(EventChain.verify(state.packageId(), events)).isTrue();

        // 篡改任一事件负载后哈希链校验失败。
        jdbc.update("UPDATE signing_event SET payload = ? WHERE package_id = ? AND seq = ?",
                "{\"signerId\":\"mallory\"}", state.packageId(), events.get(0).seq());
        assertThat(EventChain.verify(state.packageId(), repository.findEvents(state.packageId()))).isFalse();
    }

    @Test
    void expiredPackageRejectsLateSignature() {
        PackageState state = createTwoStepPackage(newPackageId());
        clock.advance(Duration.ofDays(2));

        assertThatThrownBy(() -> service.submitSignature(state.packageId(), signRequest(state, "alice")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo("PACKAGE_NOT_ACTIVE");
                });
        assertThat(service.getState(state.packageId()).status()).isEqualTo("EXPIRED");
        assertThat(countSignatures(state.packageId())).isZero();
    }

    @Test
    void cancelledPackageRejectsLateSignatureAndHasNoEvidence() {
        PackageState state = createTwoStepPackage(newPackageId());
        PackageState cancelled = service.cancel(state.packageId(),
                new CancelPackageRequest(state.version()));
        assertThat(cancelled.status()).isEqualTo("CANCELLED");

        assertThatThrownBy(() -> service.submitSignature(state.packageId(), signRequest(cancelled, "alice")))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo("PACKAGE_NOT_ACTIVE"));
        assertThatThrownBy(() -> service.revise(state.packageId(),
                new RevisePackageRequest(DOC_V2, cancelled.version())))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo("PACKAGE_NOT_ACTIVE"));
        assertThatThrownBy(() -> service.getEvidence(state.packageId()))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo("EVIDENCE_NOT_FROZEN"));
    }

    @Test
    void staleExpectedVersionIsRejected() {
        PackageState state = createTwoStepPackage(newPackageId());
        SubmitSignatureRequest staleVersion = new SubmitSignatureRequest(newRequestId(), "alice",
                state.currentRevision(), state.docSha256(), state.version() + 99,
                SyntheticSignatures.sign(state.packageId(), state.currentRevision(), "alice", state.docSha256()));
        assertThatThrownBy(() -> service.submitSignature(state.packageId(), staleVersion))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo("VERSION_CONFLICT");
                });
    }

    @Test
    void pastExpiryAndUnknownPackageAreRejected() {
        assertThatThrownBy(() -> service.createPackage(new CreatePackageRequest(
                newPackageId(), DOC_V1, Instant.parse("2020-01-01T00:00:00Z"),
                List.of(new Step(List.of("alice"))))))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.code()).isEqualTo("EXPIRES_AT_NOT_FUTURE"));
        assertThatThrownBy(() -> service.getState(newPackageId()))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    private int countEvents(String packageId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM signing_event WHERE package_id = ?", Integer.class, packageId);
        return count == null ? 0 : count;
    }

    private int countSignatures(String packageId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM signature WHERE package_id = ?", Integer.class, packageId);
        return count == null ? 0 : count;
    }
}
