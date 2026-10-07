package com.example.starter.hazmat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.example.starter.hazmat.api.ApiException;
import com.example.starter.hazmat.service.ServiceResult;

/**
 * 接班员合成资格：起止有序校验、版本从 1 起、expectedVersion 修改（成功加 1、过期 409）、
 * requestId 幂等（同参重放、改参 409、失败不占键）。
 */
class QualificationTest extends HazmatIntegrationTestBase {

    private static final LocalDateTime FROM = LocalDateTime.parse("2026-10-02T14:00:00");
    private static final LocalDateTime TO = LocalDateTime.parse("2026-10-03T03:00:00");

    @Test
    void createQualificationStartsAtVersionOne() {
        ServiceResult created = createQualification("q-create", "ESC-Q1", "FLAMMABLE", FROM, TO);
        assertThat(created.httpStatus()).isEqualTo(200);
        assertThat(created.body().get("qualificationId")).isNotNull();
        assertThat(created.body().get("versionNo")).isEqualTo(1);
        assertThat(created.body().get("revoked")).isEqualTo(false);
        assertThat(created.body().get("validFromUtc")).isEqualTo("2026-10-02T14:00");
        assertThat(created.body().get("validToUtc")).isEqualTo("2026-10-03T03:00");
    }

    @Test
    void createQualificationRejectsUnorderedIntervalAndUnknownCategory() {
        // 起止相等、起点晚于终点均拒绝。
        assertThatThrownBy(() -> createQualification("q-eq", "ESC-Q2", "FLAMMABLE", FROM, FROM))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("QUALIFICATION_INTERVAL_INVALID"));
        assertThatThrownBy(() -> createQualification("q-rev", "ESC-Q2", "FLAMMABLE", TO, FROM))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("QUALIFICATION_INTERVAL_INVALID"));
        // 货类不存在拒绝。
        assertThatThrownBy(() -> createQualification("q-cat", "ESC-Q2", "NOPE", FROM, TO))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("CATEGORY_NOT_FOUND"));
    }

    @Test
    void revokeUsesExpectedVersionAndBumpsVersion() {
        long qid = ((Number) createQualification("q-v", "ESC-Q3", "FLAMMABLE", FROM, TO)
                .body().get("qualificationId")).longValue();
        // 过期版本 409。
        assertThatThrownBy(() -> revokeQualification(qid, "q-v-stale", 2))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("VERSION_CONFLICT"));
        // 正确版本撤销成功，版本加 1。
        ServiceResult revoked = revokeQualification(qid, "q-v-ok", 1);
        assertThat(revoked.body().get("revoked")).isEqualTo(true);
        assertThat(revoked.body().get("versionNo")).isEqualTo(2);
        // 重复撤销拒绝。
        assertThatThrownBy(() -> revokeQualification(qid, "q-v-again", 2))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("QUALIFICATION_ALREADY_REVOKED"));
        // 资格版本证据可查。
        ServiceResult view = service.getQualification(qid);
        assertThat(view.body().get("revoked")).isEqualTo(true);
        assertThat(view.body().get("versionNo")).isEqualTo(2);
    }

    @Test
    void qualificationEndpointsAreIdempotentAndFailureReleasesKey() {
        // 同键同参：重放首次成功响应（同一资格ID，不产生新记录）。
        ServiceResult first = createQualification("q-idem", "ESC-Q4", "FLAMMABLE", FROM, TO);
        ServiceResult replay = createQualification("q-idem", "ESC-Q4", "FLAMMABLE", FROM, TO);
        assertThat(String.valueOf(replay.body().get("qualificationId")))
                .isEqualTo(String.valueOf(first.body().get("qualificationId")));
        // 同键异参：409。
        assertThatThrownBy(() -> createQualification("q-idem", "ESC-Q4", "TOXIC", FROM, TO))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("IDEMPOTENT_FINGERPRINT_MISMATCH"));
        // 失败不占键：非法区间的请求失败后，同键修正参数可成功。
        assertThatThrownBy(() -> createQualification("q-fail", "ESC-Q5", "FLAMMABLE", TO, FROM))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("QUALIFICATION_INTERVAL_INVALID"));
        ServiceResult retried = createQualification("q-fail", "ESC-Q5", "FLAMMABLE", FROM, TO);
        assertThat(retried.httpStatus()).isEqualTo(200);
        // 撤销幂等：同键同参重放首次响应；同键异参 409。
        long qid = ((Number) createQualification("q-rv-q", "ESC-Q6", "FLAMMABLE", FROM, TO)
                .body().get("qualificationId")).longValue();
        ServiceResult revokeFirst = revokeQualification(qid, "q-rv", 1);
        ServiceResult revokeReplay = revokeQualification(qid, "q-rv", 1);
        assertThat(revokeReplay.body().get("versionNo")).isEqualTo(revokeFirst.body().get("versionNo"));
        assertThatThrownBy(() -> revokeQualification(qid, "q-rv", 2))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("IDEMPOTENT_FINGERPRINT_MISMATCH"));
        // 撤销失败（版本过期）不占键：修正版本后同键可成功……版本过期属业务失败，键已释放。
        long qid2 = ((Number) createQualification("q-rv2-q", "ESC-Q7", "FLAMMABLE", FROM, TO)
                .body().get("qualificationId")).longValue();
        assertThatThrownBy(() -> revokeQualification(qid2, "q-rv2", 9))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("VERSION_CONFLICT"));
        assertThat(revokeQualification(qid2, "q-rv2", 1).httpStatus()).isEqualTo(200);
    }

    @Test
    void getQualificationNotFound() {
        assertThatThrownBy(() -> service.getQualification(999999))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("QUALIFICATION_NOT_FOUND"));
    }
}
