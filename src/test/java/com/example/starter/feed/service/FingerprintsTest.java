package com.example.starter.feed.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.feed.api.SubmitFormulaRequest;

/**
 * 指纹稳定性单测：字段声明顺序无关、同参一致、任一业务字段变化即不同。
 */
class FingerprintsTest {

    private SubmitFormulaRequest request(String requestId, String code, String species, String ratio) {
        return new SubmitFormulaRequest(requestId, code, species,
                List.of(new SubmitFormulaRequest.Item("AMOXICILLIN", new BigDecimal(ratio))));
    }

    @Test
    void samePayloadProducesSameFingerprint() {
        String f1 = Fingerprints.of("SUBMIT", "R1",
                request("R1", "F-A", "POULTRY", "0.001"));
        String f2 = Fingerprints.of("SUBMIT", "R1",
                request("R1", "F-A", "POULTRY", "0.0010"));
        assertThat(f1).isEqualTo(f2).hasSize(64);
    }

    @Test
    void anyBusinessFieldChangeProducesDifferentFingerprint() {
        String base = Fingerprints.of("SUBMIT", "R1",
                request("R1", "F-A", "POULTRY", "0.001"));
        assertThat(Fingerprints.of("SUBMIT", "R1",
                request("R1", "F-A", "POULTRY", "0.002"))).isNotEqualTo(base);
        assertThat(Fingerprints.of("SUBMIT", "R1",
                request("R1", "F-B", "POULTRY", "0.001"))).isNotEqualTo(base);
        assertThat(Fingerprints.of("SUBMIT", "R1",
                request("R1", "F-A", "SWINE", "0.001"))).isNotEqualTo(base);
        // requestId 是入参的一部分
        assertThat(Fingerprints.of("SUBMIT", "R2",
                request("R2", "F-A", "POULTRY", "0.001"))).isNotEqualTo(base);
        // 操作类型也参与摘要，防跨操作串用
        assertThat(Fingerprints.of("PRODUCTION", "R1",
                request("R1", "F-A", "POULTRY", "0.001"))).isNotEqualTo(base);
    }

    @Test
    void instantsAreSerializedStably() {
        String f1 = Fingerprints.of("SUBMIT", "R1",
                new com.example.starter.feed.api.ProductionRequest("R1", "B1", "F1", 1,
                        Instant.parse("2026-09-01T10:00:00Z")));
        String f2 = Fingerprints.of("SUBMIT", "R1",
                new com.example.starter.feed.api.ProductionRequest("R1", "B1", "F1", 1,
                        Instant.parse("2026-09-01T10:00:00Z")));
        assertThat(f1).isEqualTo(f2);
    }
}
