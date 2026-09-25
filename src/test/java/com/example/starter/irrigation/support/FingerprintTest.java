package com.example.starter.irrigation.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class FingerprintTest {

    private List<Fingerprint.PlanLine> linesA() {
        return List.of(
                new Fingerprint.PlanLine("D001", 600, 60, 0),
                new Fingerprint.PlanLine("D002", 300, 30, 0),
                new Fingerprint.PlanLine("D003", 100, 10, 0));
    }

    @Test
    void sameKeySameParametersProduceSameFingerprintRegardlessOfLineOrder() {
        String fp1 = Fingerprint.ofPlan("req-1", 10L, "G1", linesA());
        String fp2 = Fingerprint.ofPlan("req-1", 10L, "G1", List.of(
                new Fingerprint.PlanLine("D003", 100, 10, 0),
                new Fingerprint.PlanLine("D001", 600, 60, 0),
                new Fingerprint.PlanLine("D002", 300, 30, 0)));
        assertThat(fp1).isEqualTo(fp2).hasSize(64);
    }

    @Test
    void differentRequestIdProducesDifferentFingerprint() {
        assertThat(Fingerprint.ofPlan("req-1", 10L, "G1", linesA()))
                .isNotEqualTo(Fingerprint.ofPlan("req-2", 10L, "G1", linesA()));
    }

    @Test
    void differentTurnProducesDifferentFingerprint() {
        assertThat(Fingerprint.ofPlan("req-1", 10L, "G1", linesA()))
                .isNotEqualTo(Fingerprint.ofPlan("req-1", 11L, "G1", linesA()));
    }

    @Test
    void differentPlanDetailProducesDifferentFingerprint() {
        String original = Fingerprint.ofPlan("req-1", 10L, "G1", linesA());
        String changedPlan = Fingerprint.ofPlan("req-1", 10L, "G1", List.of(
                new Fingerprint.PlanLine("D001", 600, 61, 0),
                new Fingerprint.PlanLine("D002", 300, 30, 0),
                new Fingerprint.PlanLine("D003", 100, 10, 0)));
        String changedArea = Fingerprint.ofPlan("req-1", 10L, "G1", List.of(
                new Fingerprint.PlanLine("D001", 601, 60, 0),
                new Fingerprint.PlanLine("D002", 300, 30, 0),
                new Fingerprint.PlanLine("D003", 100, 10, 0)));
        String changedCarryover = Fingerprint.ofPlan("req-1", 10L, "G1", List.of(
                new Fingerprint.PlanLine("D001", 600, 60, 5),
                new Fingerprint.PlanLine("D002", 300, 30, 0),
                new Fingerprint.PlanLine("D003", 100, 10, 0)));
        assertThat(changedPlan).isNotEqualTo(original);
        assertThat(changedArea).isNotEqualTo(original);
        assertThat(changedCarryover).isNotEqualTo(original);
    }

    @Test
    void differentGroupProducesDifferentFingerprint() {
        assertThat(Fingerprint.ofPlan("req-1", 10L, "G1", linesA()))
                .isNotEqualTo(Fingerprint.ofPlan("req-1", 10L, "G2", linesA()));
    }
}
