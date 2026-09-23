package com.example.starter.signing.support;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.BeforeEach;

import com.example.starter.signing.service.Hashes;
import com.example.starter.signing.service.SyntheticSignatureVerifier;
import com.example.starter.signing.service.SigningService;
import com.example.starter.signing.web.ActionResponse;
import com.example.starter.signing.web.CreatePackageRequest;
import com.example.starter.signing.web.SignatureSubmitRequest;

/**
 * 签署流程测试基类：使用独立 H2 内存库、可控时钟，每用例前清空全部业务表。
 */
@SpringBootTest
@Import(TestClockConfiguration.class)
public abstract class AbstractSigningTest {

    /** 测试基准时间（UTC）。 */
    protected static final Instant BASE_TIME = TestClockConfiguration.BASE_TIME;
    /** 默认到期时间（基准 + 1 小时）。 */
    protected static final Instant EXPIRES = BASE_TIME.plus(1, ChronoUnit.HOURS);

    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected SigningService service;
    @Autowired
    protected SyntheticSignatureVerifier verifier;
    @Autowired
    protected MutableClock clock;

    @BeforeEach
    protected void cleanTables() {
        jdbc.update("DELETE FROM sign_request");
        jdbc.update("DELETE FROM evidence_package");
        jdbc.update("DELETE FROM sign_event");
        jdbc.update("DELETE FROM signature_record");
        jdbc.update("DELETE FROM signing_step");
        jdbc.update("DELETE FROM signing_package");
    }

    protected void resetClock() {
        clock.setInstant(BASE_TIME);
    }

    protected String createPackage(String documentHash, Instant expiresAt,
                                   List<CreatePackageRequest.StepSpec> steps) {
        return service.createPackage(
                new CreatePackageRequest(documentHash, expiresAt, steps)).packageId();
    }

    protected String createSimpleTwoStepPackage(String documentHash) {
        return createPackage(documentHash, EXPIRES, List.of(
                new CreatePackageRequest.StepSpec(1, List.of("alice", "bob")),
                new CreatePackageRequest.StepSpec(2, List.of("carol"))));
    }

    protected SignatureSubmitRequest signRequest(String requestId, String signer,
                                                 int revision, String documentHash,
                                                 int expectedVersion) {
        String signature = verifier.compose(signer, revision, documentHash);
        return new SignatureSubmitRequest(requestId, signer, revision, documentHash,
                expectedVersion, signature);
    }

    protected ActionResponse sign(String packageId, String requestId, String signer,
                                  int revision, String documentHash) {
        SigningService.SubmitOutcome outcome = service.submitSignature(
                packageId, signRequest(requestId, signer, revision, documentHash, revision));
        if (outcome.body() instanceof ActionResponse action) {
            return action;
        }
        throw new IllegalStateException("签名被拒绝: " + outcome.body());
    }

    protected static String docHash(String content) {
        return Hashes.sha256(content);
    }
}
