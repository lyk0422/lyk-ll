package com.example.starter.hazmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.starter.hazmat.api.ApiException;
import com.example.starter.hazmat.service.ServiceResult;

/**
 * 接班员合成资格：起止有序、版本从 1 起、expectedVersion 撤销（成功加 1、过期 409）、
 * requestId 幂等（同参重放、改参 409、失败不占键）。
 */
class QualificationTest extends HazmatIntegrationTestBase {

    private static final LocalDateTime VALID_FROM = LocalDateTime.parse("2026-10-02T00:00:00");
    private static final LocalDateTime VALID_TO = LocalDateTime.parse("2026-10-03T00:00:00");

    @Test
    void createQualificationStartsAtVersionOne() {
        ServiceResult created = createQualification("q-create", "ESC-T1", "FLAMMABLE", VALID_FROM, VALID_TO);
        assertThat(created.httpStatus()).isEqualTo(200);
        assertThat(created.body().get("versionNo")).isEqualTo(1);
        assertThat(created.body().get("revoked")).isEqualTo(false);
        assertThat(created.body().get("validFrom")).isEqualTo("2026-10-02T00:00");
        assertThat(created.body().get("validTo")).isEqualTo("2026-10-03T00:00");

        long id = ((Number) created.body().get("qualificationId")).longValue();
        Map<String, Object> evidence = getQualification(id).body();
        assertThat(evidence.get("escortId")).isEqualTo("ESC-T1");
        assertThat(evidence.get("goodsCategory")).isEqualTo("FLAMMABLE");
        assertThat(evidence.get("versionNo")).isEqualTo(1);
    }

    @Test
    void qualificationWindowMustBeOrdered() {
        // 终点恰等于起点：起止无序，拒绝。
        assertThatThrownBy(() -> createQualification("q-eq", "ESC-T2", "FLAMMABLE", VALID_FROM, VALID_FROM))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("QUALIFICATION_WINDOW_INVALID"));
        // 终点早于起点：拒绝。
        assertThatThrownBy(() -> createQualification("q-rev", "ESC-T2", "FLAMMABLE",
                VALID_TO, VALID_FROM))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("QUALIFICATION_WINDOW_INVALID"));
        // 未知货类：拒绝。
        assertThatThrownBy(() -> createQualification("q-cat", "ESC-T2", "NOPE", VALID_FROM, VALID_TO))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("CATEGORY_NOT_FOUND"));
    }

    @Test
    void revokeUsesExpectedVersionAndIncrements() {
        long id = ((Number) createQualification("q-rv", "ESC-T3", "TOXIC", VALID_FROM, VALID_TO)
                .body().get("qualificationId")).longValue();
        // 过期版本：409。
        assertThatThrownBy(() -> revokeQualification(id, "q-rv-stale", 2))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("VERSION_CONFLICT"));
        // 正确版本：撤销成功，版本加 1。
        ServiceResult revoked = revokeQualification(id, "q-rv-ok", 1);
        assertThat(revoked.httpStatus()).isEqualTo(200);
        assertThat(revoked.body().get("revoked")).isEqualTo(true);
        assertThat(revoked.body().get("versionNo")).isEqualTo(2);
        // 已撤销再撤销：409。
        assertThatThrownBy(() -> revokeQualification(id, "q-rv-again", 2))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("QUALIFICATION_ALREADY_REVOKED"));
        // 证据查询：撤销状态与版本可见。
        Map<String, Object> evidence = getQualification(id).body();
        assertThat(evidence.get("revoked")).isEqualTo(true);
        assertThat(evidence.get("versionNo")).isEqualTo(2);
        assertThat(evidence.get("revokedAt")).isNotNull();
    }

    @Test
    void qualificationIdempotencyReplaysSuccessAndRejectsParamChange() {
        ServiceResult first = createQualification("q-idem", "ESC-T4", "FLAMMABLE", VALID_FROM, VALID_TO);
        ServiceResult replay = createQualification("q-idem", "ESC-T4", "FLAMMABLE", VALID_FROM, VALID_TO);
        // 同参成功：重放原响应（同一资格ID，不产生新记录）；重放经 JSON 存储，数值按字符串比对。
        assertThat(String.valueOf(replay.body().get("qualificationId")))
                .isEqualTo(String.valueOf(first.body().get("qualificationId")));
        // 改参：409。
        assertThatThrownBy(() -> createQualification("q-idem", "ESC-T4", "TOXIC", VALID_FROM, VALID_TO))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("IDEMPOTENT_FINGERPRINT_MISMATCH"));
    }

    @Test
    void failedRequestDoesNotHoldIdempotencyKey() {
        // 首次失败（区间无序）：不占键。
        assertThatThrownBy(() -> createQualification("q-fail", "ESC-T5", "FLAMMABLE",
                VALID_TO, VALID_FROM))
                .isInstanceOf(ApiException.class);
        // 同键改参重试：不触发指纹冲突，正常成功。
        ServiceResult retried = createQualification("q-fail", "ESC-T5", "FLAMMABLE", VALID_FROM, VALID_TO);
        assertThat(retried.httpStatus()).isEqualTo(200);
        assertThat(retried.body().get("escortId")).isEqualTo("ESC-T5");
        // 撤销失败（版本过期）同样不占键：同键改参可重试。
        long id = ((Number) retried.body().get("qualificationId")).longValue();
        assertThatThrownBy(() -> revokeQualification(id, "q-fail-rv", 9))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("VERSION_CONFLICT"));
        ServiceResult revoked = revokeQualification(id, "q-fail-rv", 1);
        assertThat(revoked.httpStatus()).isEqualTo(200);
        assertThat(revoked.body().get("revoked")).isEqualTo(true);
    }
}
