package com.example.starter.rights;

import com.example.starter.rights.dto.CompletePlaybackRequest;
import com.example.starter.rights.dto.CorrectLicenseRequest;
import com.example.starter.rights.dto.CreateLicenseRequest;
import com.example.starter.rights.dto.RevokeLicenseRequest;
import com.example.starter.rights.dto.ScheduleSegment;
import com.example.starter.rights.dto.SubmitScheduleRequest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 版权窗口业务 H2 集成测试：半开端点、独占冲突、整段覆盖、整笔回滚、
 * 版本更正、撤销竞态、完成证据冻结、请求指纹幂等。
 */
class RightsServiceIntegrationTest extends AbstractRightsIntegrationTest {

    private static final String ASSET = "asset-synth-1";
    private static final String REGION = "CN";
    private static final String PLATFORM = "web";

    private static final Instant T02 = Instant.parse("2026-10-01T02:00:00Z");
    private static final Instant T03 = Instant.parse("2026-10-01T03:00:00Z");
    private static final Instant T04 = Instant.parse("2026-10-01T04:00:00Z");
    private static final Instant T05 = Instant.parse("2026-10-01T05:00:00Z");
    private static final Instant T06 = Instant.parse("2026-10-01T06:00:00Z");

    private String newRequestId() {
        return "req-" + UUID.randomUUID();
    }

    private String createLicense(Instant start, Instant end, LicenseMode mode) throws Exception {
        return createLicense(newRequestId(), start, end, mode);
    }

    private String createLicense(String requestId, Instant start, Instant end, LicenseMode mode) throws Exception {
        var req = new CreateLicenseRequest(requestId, ASSET, REGION, PLATFORM, start, end, mode);
        var result = postJson("/api/rights/licenses", req);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return json(result).get("licenseId").asText();
    }

    private JsonNode createLicenseJson(Instant start, Instant end, LicenseMode mode) throws Exception {
        var result = postJson("/api/rights/licenses",
                new CreateLicenseRequest(newRequestId(), ASSET, REGION, PLATFORM, start, end, mode));
        return json(result);
    }

    // ---- 半开端点与独占冲突 ----

    @Test
    void halfOpenEndpointsTouchingIsAllowed() throws Exception {
        // 两份独占授权在 04:00 端点相接：半开区间无正长度交集，合法。
        assertThat(createLicense(T02, T04, LicenseMode.EXCLUSIVE)).isNotBlank();
        assertThat(createLicense(T04, T06, LicenseMode.EXCLUSIVE)).isNotBlank();
    }

    @Test
    void overlappingExclusiveLicensesConflict() throws Exception {
        createLicense(T02, T04, LicenseMode.EXCLUSIVE);
        var result = postJson("/api/rights/licenses",
                new CreateLicenseRequest(newRequestId(), ASSET, REGION, PLATFORM,
                        T03, T05, LicenseMode.EXCLUSIVE));
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(result).get("code").asText()).isEqualTo("EXCLUSIVE_CONFLICT");
        assertThat(rowCount("license_version")).isEqualTo(1);
    }

    @Test
    void nonExclusiveLicensesCoexistButExclusivityConflicts() throws Exception {
        createLicense(T02, T05, LicenseMode.NON_EXCLUSIVE);
        createLicense(T03, T06, LicenseMode.NON_EXCLUSIVE);
        // 任一独占即冲突：独占窗口与上述非独占窗口正长度相交。
        var result = postJson("/api/rights/licenses",
                new CreateLicenseRequest(newRequestId(), ASSET, REGION, PLATFORM,
                        T04, T05, LicenseMode.EXCLUSIVE));
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(result).get("code").asText()).isEqualTo("EXCLUSIVE_CONFLICT");
    }

    @Test
    void zeroLengthWindowRejected() throws Exception {
        var result = postJson("/api/rights/licenses",
                new CreateLicenseRequest(newRequestId(), ASSET, REGION, PLATFORM,
                        T04, T04, LicenseMode.NON_EXCLUSIVE));
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(result).get("code").asText()).isEqualTo("INVALID_WINDOW");
    }

    // ---- 排期整段覆盖与整笔回滚 ----

    @Test
    void segmentEndpointsAlignedWithWindowAreCovered() throws Exception {
        createLicense(T02, T05, LicenseMode.NON_EXCLUSIVE);
        // 右端点对齐 05:00 合法（end 不含）；提交成功。
        var result = postJson("/api/rights/schedules", new SubmitScheduleRequest(
                newRequestId(), List.of(new ScheduleSegment(ASSET, REGION, PLATFORM, T04, T05))));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(json(result).get("items").get(0).get("licenseVersion").asInt()).isEqualTo(1);
    }

    @Test
    void segmentExtendingBeyondWindowRejected() throws Exception {
        createLicense(T02, T05, LicenseMode.EXCLUSIVE);
        var result = postJson("/api/rights/schedules", new SubmitScheduleRequest(
                newRequestId(), List.of(new ScheduleSegment(ASSET, REGION, PLATFORM, T04, T06))));
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(result).get("code").asText()).isEqualTo("SEGMENT_NOT_COVERED");
    }

    @Test
    void scheduleSubmissionIsAtomicWhenAnySegmentUncovered() throws Exception {
        createLicense(T02, T05, LicenseMode.EXCLUSIVE);
        long ledgerBefore = rowCount("request_ledger");
        var result = postJson("/api/rights/schedules", new SubmitScheduleRequest(
                newRequestId(), List.of(
                new ScheduleSegment(ASSET, REGION, PLATFORM, T02, T03),
                new ScheduleSegment(ASSET, REGION, PLATFORM, T05, T06))));
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        // 整笔零写入：提交批次、时段、幂等占位均随事务回滚（创建授权的台账行仍在，按差值断言）。
        assertThat(rowCount("schedule_submission")).isZero();
        assertThat(rowCount("schedule_item")).isZero();
        assertThat(rowCount("request_ledger")).isEqualTo(ledgerBefore);
    }

    // ---- 版本更正 ----

    @Test
    void correctionCreatesNewVersionAndKeepsHistory() throws Exception {
        String licenseId = createLicense(T02, T04, LicenseMode.EXCLUSIVE);
        var correct = postJson("/api/rights/licenses/" + licenseId + "/corrections",
                new CorrectLicenseRequest(newRequestId(), licenseId, 1, T02, T05, LicenseMode.EXCLUSIVE));
        assertThat(correct.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json(correct);
        assertThat(body.get("version").asInt()).isEqualTo(2);
        assertThat(body.get("status").asText()).isEqualTo("ACTIVE");

        var history = mockMvc.perform(get("/api/rights/licenses/" + licenseId + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode versions = json(history);
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).get("version").asInt()).isEqualTo(1);
        assertThat(versions.get(0).get("status").asText()).isEqualTo("SUPERSEDED");
        assertThat(versions.get(0).get("end").asText()).isEqualTo(T04.toString());
        assertThat(versions.get(1).get("version").asInt()).isEqualTo(2);
        assertThat(versions.get(1).get("end").asText()).isEqualTo(T05.toString());
    }

    @Test
    void correctionWithWrongExpectedVersionConflicts() throws Exception {
        String licenseId = createLicense(T02, T04, LicenseMode.EXCLUSIVE);
        var result = postJson("/api/rights/licenses/" + licenseId + "/corrections",
                new CorrectLicenseRequest(newRequestId(), licenseId, 9, T02, T05, LicenseMode.EXCLUSIVE));
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(result).get("code").asText()).isEqualTo("VERSION_MISMATCH");
    }

    // ---- 撤销：顺序确定的两种情形 ----

    @Test
    void revokeFirstRejectsWholeScheduleSubmission() throws Exception {
        String licenseId = createLicense(T02, T05, LicenseMode.EXCLUSIVE);
        // 撤销先提交（now=BASE=00:00，时段 02:00 尚未开始）。
        var revoke = postJson("/api/rights/licenses/" + licenseId + "/revocation",
                new RevokeLicenseRequest(newRequestId(), licenseId, 1));
        assertThat(revoke.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(revoke).get("status").asText()).isEqualTo("REVOKED");

        var submit = postJson("/api/rights/schedules", new SubmitScheduleRequest(
                newRequestId(), List.of(new ScheduleSegment(ASSET, REGION, PLATFORM, T02, T03))));
        assertThat(submit.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(submit).get("code").asText()).isEqualTo("SEGMENT_NOT_COVERED");
        assertThat(rowCount("schedule_item")).isZero();
    }

    @Test
    void revokeAfterScheduleBlocksNotStartedButKeepsStarted() throws Exception {
        // 授权窗口从前一天 23:00 起，撤销时刻 BASE=00:00 时：02:00 时段未开始，23:00 时段已开始。
        Instant windowStart = Instant.parse("2026-09-30T23:00:00Z");
        String licenseId = createLicense(windowStart, T05, LicenseMode.EXCLUSIVE);
        Instant started0 = Instant.parse("2026-09-30T23:00:00Z");
        Instant started1 = Instant.parse("2026-10-01T01:00:00Z");
        var submit = postJson("/api/rights/schedules", new SubmitScheduleRequest(
                newRequestId(), List.of(
                new ScheduleSegment(ASSET, REGION, PLATFORM, T02, T03),
                new ScheduleSegment(ASSET, REGION, PLATFORM, started0, started1))));
        assertThat(submit.getResponse().getStatus()).isEqualTo(201);
        String submissionId = json(submit).get("submissionId").asText();

        var revoke = postJson("/api/rights/licenses/" + licenseId + "/revocation",
                new RevokeLicenseRequest(newRequestId(), licenseId, 1));
        assertThat(revoke.getResponse().getStatus()).isEqualTo(200);

        JsonNode items = json(mockMvc.perform(get("/api/rights/schedules/" + submissionId))
                .andExpect(status().isOk()).andReturn()).get("items");
        assertThat(items.get(0).get("status").asText()).isEqualTo("BLOCKED");
        assertThat(items.get(0).get("blockedAt").asText()).isEqualTo(BASE.toString());
        assertThat(items.get(1).get("status").asText()).isEqualTo("BOUND");
        assertThat(items.get(1).get("blockedAt").isNull()).isTrue();

        JsonNode blocked = json(mockMvc.perform(get(
                "/api/rights/schedules/" + submissionId + "/items/0/playability"))
                .andExpect(status().isOk()).andReturn());
        assertThat(blocked.get("playable").asBoolean()).isFalse();
        assertThat(blocked.get("reason").asText()).isEqualTo("ITEM_BLOCKED");

        JsonNode started = json(mockMvc.perform(get(
                "/api/rights/schedules/" + submissionId + "/items/1/playability"))
                .andExpect(status().isOk()).andReturn());
        assertThat(started.get("playable").asBoolean()).isTrue();
        assertThat(started.get("licenseVersion").asInt()).isEqualTo(1);
    }

    @Test
    void blockedItemCannotCompletePlayback() throws Exception {
        String licenseId = createLicense(T02, T05, LicenseMode.EXCLUSIVE);
        var submit = postJson("/api/rights/schedules", new SubmitScheduleRequest(
                newRequestId(), List.of(new ScheduleSegment(ASSET, REGION, PLATFORM, T02, T03))));
        String submissionId = json(submit).get("submissionId").asText();
        postJson("/api/rights/licenses/" + licenseId + "/revocation",
                new RevokeLicenseRequest(newRequestId(), licenseId, 1));
        var playback = postJson("/api/rights/schedules/" + submissionId + "/items/0/playback",
                new CompletePlaybackRequest(newRequestId(), null, null));
        assertThat(playback.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(playback).get("code").asText()).isEqualTo("ITEM_BLOCKED");
        assertThat(rowCount("playback_record")).isZero();
    }

    // ---- 撤销 vs 排期提交真实并发：结果必须落入两种一致顺序之一 ----

    @Test
    void concurrentRevokeAndScheduleProducesConsistentOrdering() throws Exception {
        // 多轮并发，每轮独立授权与 requestId；每轮结果必须落入两种一致顺序之一。
        for (int round = 0; round < 8; round++) {
            String licenseId = createLicense(T02, T05, LicenseMode.EXCLUSIVE);
            String scheduleRequestId = newRequestId();
            String revokeRequestId = newRequestId();

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            AtomicReference<Integer> submitStatus = new AtomicReference<>();
            AtomicReference<Integer> revokeStatus = new AtomicReference<>();
            try {
                pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    submitStatus.set(postJson("/api/rights/schedules", new SubmitScheduleRequest(
                            scheduleRequestId, List.of(new ScheduleSegment(ASSET, REGION, PLATFORM, T02, T03))))
                            .getResponse().getStatus());
                    return null;
                });
                pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    revokeStatus.set(postJson("/api/rights/licenses/" + licenseId + "/revocation",
                            new RevokeLicenseRequest(revokeRequestId, licenseId, 1))
                            .getResponse().getStatus());
                    return null;
                });
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                pool.shutdown();
                assertThat(pool.awaitTermination(40, TimeUnit.SECONDS))
                        .as("第 %d 轮并发线程应在超时前结束", round).isTrue();
            } finally {
                pool.shutdownNow();
            }

            assertThat(revokeStatus.get()).isEqualTo(200);
            int itemCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM schedule_item WHERE submission_id IN "
                            + "(SELECT submission_id FROM schedule_submission WHERE request_id = ?)",
                    Integer.class, scheduleRequestId);
            if (submitStatus.get() == 422) {
                // 撤销先提交：排期整笔拒绝、零写入。
                assertThat(itemCount).as("第 %d 轮：撤销先赢应为零写入", round).isZero();
            } else {
                // 排期先提交：绑定有效，撤销随后把未开始项置 BLOCKED。
                assertThat(submitStatus.get()).as("第 %d 轮提交状态", round).isEqualTo(201);
                assertThat(itemCount).as("第 %d 轮：排期先赢应有一时段", round).isEqualTo(1);
                String status = jdbc.queryForObject(
                        "SELECT status FROM schedule_item WHERE submission_id IN "
                                + "(SELECT submission_id FROM schedule_submission WHERE request_id = ?)",
                        String.class, scheduleRequestId);
                assertThat(status).as("第 %d 轮：未开始项应转 BLOCKED", round).isEqualTo("BLOCKED");
            }
        }
    }

    @Test
    void concurrentOverlappingExclusiveCreatesNeverCoexist() throws Exception {
        // 两个不同 requestId 的并发独占创建在同一作用域上必须串行：恰好一个成功。
        for (int round = 0; round < 8; round++) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<AtomicReference<Integer>> statuses = new ArrayList<>();
            statuses.add(new AtomicReference<>());
            statuses.add(new AtomicReference<>());
            try {
                for (int t = 0; t < 2; t++) {
                    final int idx = t;
                    pool.submit(() -> {
                        ready.countDown();
                        try {
                            start.await();
                            statuses.get(idx).set(postJson("/api/rights/licenses",
                                    new CreateLicenseRequest(newRequestId(), ASSET, REGION, PLATFORM,
                                            T02, T04, LicenseMode.EXCLUSIVE)).getResponse().getStatus());
                        } catch (Exception ignored) {
                            statuses.get(idx).set(-1);
                        }
                        return null;
                    });
                }
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                pool.shutdown();
                assertThat(pool.awaitTermination(40, TimeUnit.SECONDS)).isTrue();
            } finally {
                pool.shutdownNow();
            }
            List<Integer> codes = statuses.stream().map(AtomicReference::get).toList();
            long created = codes.stream().filter(s -> s == 201).count();
            long conflicted = codes.stream().filter(s -> s == 409).count();
            assertThat(created).as("第 %d 轮：仅一个独占授权可创建，实际=%s", round, codes).isEqualTo(1);
            assertThat(conflicted).as("第 %d 轮：另一个必须冲突，实际=%s", round, codes).isEqualTo(1);
            // 清出作用域以便下一轮复用同一合成标识。
            jdbc.update("DELETE FROM license_version");
            jdbc.update("DELETE FROM request_ledger");
        }
    }

    @Test
    void duplicateRevokeConflicts() throws Exception {
        String licenseId = createLicense(T02, T05, LicenseMode.EXCLUSIVE);
        var first = postJson("/api/rights/licenses/" + licenseId + "/revocation",
                new RevokeLicenseRequest(newRequestId(), licenseId, 1));
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        var second = postJson("/api/rights/licenses/" + licenseId + "/revocation",
                new RevokeLicenseRequest(newRequestId(), licenseId, 1));
        assertThat(second.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(second).get("code").asText()).isEqualTo("LICENSE_NOT_ACTIVE");
    }

    @Test
    void revokeAfterPlaybackCompletedKeepsEvidenceFrozen() throws Exception {
        String licenseId = createLicense(T02, T05, LicenseMode.EXCLUSIVE);
        var submit = postJson("/api/rights/schedules", new SubmitScheduleRequest(
                newRequestId(), List.of(new ScheduleSegment(ASSET, REGION, PLATFORM, T02, T03))));
        String submissionId = json(submit).get("submissionId").asText();

        setClock(Instant.parse("2026-10-01T03:30:00Z"));
        var playback = postJson("/api/rights/schedules/" + submissionId + "/items/0/playback",
                new CompletePlaybackRequest(newRequestId(), null, null));
        assertThat(playback.getResponse().getStatus()).isEqualTo(200);

        // 已完成播出后撤销：证据保持冻结，可播查询对已完成项不再是 BLOCKED 语义（证据可查）。
        var revoke = postJson("/api/rights/licenses/" + licenseId + "/revocation",
                new RevokeLicenseRequest(newRequestId(), licenseId, 1));
        assertThat(revoke.getResponse().getStatus()).isEqualTo(200);

        JsonNode evidence = json(mockMvc.perform(get(
                "/api/rights/schedules/" + submissionId + "/evidence"))
                .andExpect(status().isOk()).andReturn());
        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0).get("licenseVersion").asInt()).isEqualTo(1);
        assertThat(evidence.get(0).get("windowEnd").asText()).isEqualTo(T05.toString());
    }

    // ---- 完成回执与证据冻结 ----

    @Test
    void completedPlaybackEvidenceStaysFrozenAfterRevokeAndCorrection() throws Exception {
        String licenseId = createLicense(T02, T05, LicenseMode.EXCLUSIVE);
        var submit = postJson("/api/rights/schedules", new SubmitScheduleRequest(
                newRequestId(), List.of(new ScheduleSegment(ASSET, REGION, PLATFORM, T02, T03))));
        String submissionId = json(submit).get("submissionId").asText();

        // 先更正生成 v2（窗口右沿延到 06:00），完成回执仍冻结在绑定时的 v1。
        var correct = postJson("/api/rights/licenses/" + licenseId + "/corrections",
                new CorrectLicenseRequest(newRequestId(), licenseId, 1, T02, T06, LicenseMode.EXCLUSIVE));
        assertThat(correct.getResponse().getStatus()).isEqualTo(200);
        // 时钟拨到 03:30：时段已播完，完成回执携带播出时间与绑定版本。
        setClock(Instant.parse("2026-10-01T03:30:00Z"));
        String playbackRequestId = newRequestId();
        var playback = postJson("/api/rights/schedules/" + submissionId + "/items/0/playback",
                new CompletePlaybackRequest(playbackRequestId, null, null));
        assertThat(playback.getResponse().getStatus()).isEqualTo(200);
        JsonNode receipt = json(playback);
        assertThat(receipt.get("airStart").asText()).isEqualTo(T02.toString());
        assertThat(receipt.get("airEnd").asText()).isEqualTo(T03.toString());
        assertThat(receipt.get("licenseId").asText()).isEqualTo(licenseId);
        assertThat(receipt.get("licenseVersion").asInt()).isEqualTo(1);

        // 之后撤销 v2 不得追溯改写已冻结的 v1 证据。
        var revoke = postJson("/api/rights/licenses/" + licenseId + "/revocation",
                new RevokeLicenseRequest(newRequestId(), licenseId, 2));
        assertThat(revoke.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(revoke).get("status").asText()).isEqualTo("REVOKED");

        JsonNode evidence = json(mockMvc.perform(get(
                "/api/rights/schedules/" + submissionId + "/evidence"))
                .andExpect(status().isOk()).andReturn());
        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0).get("licenseVersion").asInt()).isEqualTo(1);
        assertThat(evidence.get(0).get("windowStart").asText()).isEqualTo(T02.toString());
        assertThat(evidence.get(0).get("windowEnd").asText()).isEqualTo(T05.toString());
        assertThat(evidence.get(0).get("requestId").asText()).isEqualTo(playbackRequestId);

        JsonNode byLicense = json(mockMvc.perform(get("/api/rights/evidence")
                        .param("licenseId", licenseId))
                .andExpect(status().isOk()).andReturn());
        assertThat(byLicense).hasSize(1);
        assertThat(byLicense.get(0).get("submissionId").asText()).isEqualTo(submissionId);
    }

    @Test
    void playbackBeforeSegmentFinishedRejected() throws Exception {
        createLicense(T02, T05, LicenseMode.EXCLUSIVE);
        var submit = postJson("/api/rights/schedules", new SubmitScheduleRequest(
                newRequestId(), List.of(new ScheduleSegment(ASSET, REGION, PLATFORM, T02, T03))));
        String submissionId = json(submit).get("submissionId").asText();
        setClock(Instant.parse("2026-10-01T02:30:00Z"));
        var playback = postJson("/api/rights/schedules/" + submissionId + "/items/0/playback",
                new CompletePlaybackRequest(newRequestId(), null, null));
        assertThat(playback.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(playback).get("code").asText()).isEqualTo("PLAYBACK_NOT_FINISHED");
    }

    // ---- 请求指纹幂等 ----

    @Test
    void sameRequestIdSameParamsReturnsFirstResult() throws Exception {
        String requestId = newRequestId();
        var body = new CreateLicenseRequest(requestId, ASSET, REGION, PLATFORM,
                T02, T05, LicenseMode.EXCLUSIVE);
        var first = postJson("/api/rights/licenses", body);
        var second = postJson("/api/rights/licenses", body);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        assertThat(json(first).get("licenseId").asText())
                .isEqualTo(json(second).get("licenseId").asText());
        assertThat(rowCount("license_version")).isEqualTo(1);
    }

    @Test
    void sameRequestIdDifferentParamsConflicts() throws Exception {
        String requestId = newRequestId();
        var first = postJson("/api/rights/licenses",
                new CreateLicenseRequest(requestId, ASSET, REGION, PLATFORM,
                        T02, T05, LicenseMode.EXCLUSIVE));
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        var second = postJson("/api/rights/licenses",
                new CreateLicenseRequest(requestId, ASSET, REGION, PLATFORM,
                        T02, T06, LicenseMode.EXCLUSIVE));
        assertThat(second.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(second).get("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(rowCount("license_version")).isEqualTo(1);
    }

    @Test
    void failedRequestDoesNotConsumeRequestIdAndCanRetrySuccessfully() throws Exception {
        // 首次参数非法（零长度窗口）回滚，不占用 requestId；同 requestId 改正参数后成功。
        String requestId = newRequestId();
        var failed = postJson("/api/rights/licenses",
                new CreateLicenseRequest(requestId, ASSET, REGION, PLATFORM,
                        T04, T04, LicenseMode.EXCLUSIVE));
        assertThat(failed.getResponse().getStatus()).isEqualTo(422);
        var retried = postJson("/api/rights/licenses",
                new CreateLicenseRequest(requestId, ASSET, REGION, PLATFORM,
                        T02, T04, LicenseMode.EXCLUSIVE));
        assertThat(retried.getResponse().getStatus()).isEqualTo(201);
        assertThat(rowCount("license_version")).isEqualTo(1);
    }

    @Test
    void scheduleRetrySameRequestIdReplaysSubmission() throws Exception {
        createLicense(T02, T05, LicenseMode.NON_EXCLUSIVE);
        var body = new SubmitScheduleRequest(newRequestId(),
                List.of(new ScheduleSegment(ASSET, REGION, PLATFORM, T02, T03)));
        var first = postJson("/api/rights/schedules", body);
        var second = postJson("/api/rights/schedules", body);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        assertThat(json(first).get("submissionId").asText())
                .isEqualTo(json(second).get("submissionId").asText());
        assertThat(rowCount("schedule_item")).isEqualTo(1);
        assertThat(rowCount("schedule_submission")).isEqualTo(1);
    }

    @Test
    void playbackRetrySameRequestIdReplaysReceiptOnce() throws Exception {
        String licenseId = createLicense(T02, T05, LicenseMode.EXCLUSIVE);
        var submit = postJson("/api/rights/schedules", new SubmitScheduleRequest(
                newRequestId(), List.of(new ScheduleSegment(ASSET, REGION, PLATFORM, T02, T03))));
        String submissionId = json(submit).get("submissionId").asText();
        setClock(Instant.parse("2026-10-01T03:30:00Z"));
        var body = new CompletePlaybackRequest(newRequestId(), null, null);
        var first = postJson("/api/rights/schedules/" + submissionId + "/items/0/playback", body);
        var second = postJson("/api/rights/schedules/" + submissionId + "/items/0/playback", body);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(first).get("licenseVersion").asInt())
                .isEqualTo(json(second).get("licenseVersion").asInt());
        assertThat(rowCount("playback_record")).isEqualTo(1);
    }
}
