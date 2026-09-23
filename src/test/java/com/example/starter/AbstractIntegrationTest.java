package com.example.starter;

import com.example.starter.service.TimeService;
import com.example.starter.web.dto.RegisterEvidenceRequest;
import com.example.starter.service.EvidenceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

/**
 * H2（MODE=MySQL）集成测试基类：固定 UTC 时钟，场景间清空全部业务表并重置保全版本。
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

    protected static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    protected org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Autowired
    protected TimeService timeService;

    @Autowired
    protected EvidenceService evidenceService;

    @org.junit.jupiter.api.BeforeEach
    void resetDatabaseAndClock() {
        timeService.setClock(Clock.fixed(T0, ZoneOffset.UTC));
        jdbc.update("DELETE FROM purge_manifest");
        jdbc.update("DELETE FROM purge_batch_item");
        jdbc.update("DELETE FROM purge_batch");
        jdbc.update("DELETE FROM custody_event");
        jdbc.update("DELETE FROM legal_hold_item");
        jdbc.update("DELETE FROM legal_hold");
        jdbc.update("DELETE FROM evidence");
        jdbc.update("DELETE FROM idempotent_request");
        jdbc.update("UPDATE seq_holder SET hold_version = 0 WHERE id = 0");
    }

    /** 登记一条合成证据。 */
    protected void register(String evidenceId, String content, Instant expiresAt) {
        register(evidenceId, content, T0, expiresAt);
    }

    protected void register(String evidenceId, String content, Instant collectedAt, Instant expiresAt) {
        RegisterEvidenceRequest request = new RegisterEvidenceRequest(
                evidenceId, Base64.getEncoder().encodeToString(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                collectedAt, expiresAt);
        evidenceService.register(request);
    }
}
