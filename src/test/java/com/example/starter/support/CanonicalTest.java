package com.example.starter.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.starter.dto.PlanOrderReceiveRequest;
import com.example.starter.dto.PlanPublishRequest;

/**
 * 结构化指纹单测：合法文本含分隔符也可区分；集合顺序无关；任一参数变化必产生不同指纹。
 */
class CanonicalTest {

    private static PlanPublishRequest.SegmentInput seg(int idx, String code, int kw) {
        return new PlanPublishRequest.SegmentInput(
                idx, List.of(new PlanPublishRequest.EntryInput(code, kw)));
    }

    @Test
    @DisplayName("计划指纹：段/机组顺序不同但内容相同 → 同指纹")
    void orderIndependentForEquivalentPlan() {
        List<PlanPublishRequest.SegmentInput> a = new ArrayList<>(List.of(
                seg(0, "T-01", 100), seg(1, "T-01", 200)));
        List<PlanPublishRequest.SegmentInput> b = new ArrayList<>(List.of(
                seg(1, "T-01", 200), seg(0, "T-01", 100)));
        PlanPublishRequest ra = new PlanPublishRequest(
                "k", "F", "2026-09-27", 0, Instant.parse("2026-09-26T00:00:00Z"), a);
        PlanPublishRequest rb = new PlanPublishRequest(
                "k", "F", "2026-09-27", 0, Instant.parse("2026-09-26T00:00:00Z"), b);

        assertThat(Fingerprints.sha256(Canonical.planPublish(ra)))
                .isEqualTo(Fingerprints.sha256(Canonical.planPublish(rb)));
    }

    @Test
    @DisplayName("计划指纹：requestId 内含分隔符与字段拼接歧义 → 仍与异参请求区分")
    void delimiterInsideTextCannotCollide() {
        // 一个 requestId 含 '|' 与 ":s:"，可能在朴素拼接里伪造后续字段。
        PlanPublishRequest injected = new PlanPublishRequest(
                "k|farm:s:99:OTHER", "F", "2026-09-27", 0,
                Instant.parse("2026-09-26T00:00:00Z"),
                List.of(seg(0, "T-01", 100)));
        PlanPublishRequest normal = new PlanPublishRequest(
                "k", "F", "2026-09-27", 0,
                Instant.parse("2026-09-26T00:00:00Z"),
                List.of(seg(0, "T-01", 100)));

        assertThat(Fingerprints.sha256(Canonical.planPublish(injected)))
                .isNotEqualTo(Fingerprints.sha256(Canonical.planPublish(normal)));
    }

    @Test
    @DisplayName("计划指纹：仅计划值不同 → 不同指纹")
    void differentPlannedPowerDifferentFingerprint() {
        PlanPublishRequest a = new PlanPublishRequest(
                "k", "F", "2026-09-27", 0, Instant.parse("2026-09-26T00:00:00Z"),
                List.of(seg(0, "T-01", 100)));
        PlanPublishRequest b = new PlanPublishRequest(
                "k", "F", "2026-09-27", 0, Instant.parse("2026-09-26T00:00:00Z"),
                List.of(seg(0, "T-01", 101)));

        assertThat(Fingerprints.sha256(Canonical.planPublish(a)))
                .isNotEqualTo(Fingerprints.sha256(Canonical.planPublish(b)));
    }

    @Test
    @DisplayName("绑定指令指纹：精确时刻或期望版本变化 → 不同指纹")
    void planOrderFingerprintSensitiveToOpTimeAndVersion() {
        PlanOrderReceiveRequest base = new PlanOrderReceiveRequest(
                "k", "F", 4000, "2026-09-27", 10, 1,
                Instant.parse("2026-09-26T00:00:00Z"));
        PlanOrderReceiveRequest laterOp = new PlanOrderReceiveRequest(
                "k", "F", 4000, "2026-09-27", 10, 1,
                Instant.parse("2026-09-26T00:00:01Z"));
        PlanOrderReceiveRequest otherVersion = new PlanOrderReceiveRequest(
                "k", "F", 4000, "2026-09-27", 10, 2,
                Instant.parse("2026-09-26T00:00:00Z"));

        String baseFp = Fingerprints.sha256(Canonical.planOrder(base));
        assertThat(Fingerprints.sha256(Canonical.planOrder(laterOp))).isNotEqualTo(baseFp);
        assertThat(Fingerprints.sha256(Canonical.planOrder(otherVersion))).isNotEqualTo(baseFp);
    }
}
