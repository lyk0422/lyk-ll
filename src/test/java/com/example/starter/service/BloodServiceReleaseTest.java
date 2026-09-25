package com.example.starter.service;

import com.example.starter.AbstractBloodIntegrationTest;
import com.example.starter.domain.BloodUnit;
import com.example.starter.domain.ComponentType;
import com.example.starter.domain.TestResultCode;
import com.example.starter.domain.TestType;
import com.example.starter.domain.UnitStatus;
import com.example.starter.web.ConflictException;
import com.example.starter.web.dto.IntakeRequest;
import com.example.starter.web.dto.ReleaseRequest;
import com.example.starter.web.dto.ReleaseResponse;
import com.example.starter.web.dto.TestResultRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 放行：效期边界、时间窗、幂等重放、同键异参 409、拒绝分支、原子扣减与并发唯一放行。
 */
class BloodServiceReleaseTest extends AbstractBloodIntegrationTest {

    private static final Instant EXPIRES_AT = Instant.parse("2026-09-30T15:59:59Z");
    private static final Instant WINDOW_START = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-10-10T00:00:00Z");

    @Autowired
    private BloodService bloodService;

    private void makeReleasable(String barcode) {
        bloodService.intake(new IntakeRequest(barcode, ComponentType.PLASMA, EXPIRES_AT));
        bloodService.reportTestResult(new TestResultRequest(barcode, TestType.INITIAL, TestResultCode.PASS, null));
        bloodService.reportTestResult(new TestResultRequest(barcode, TestType.REPEAT, TestResultCode.PASS, null));
    }

    private ReleaseRequest releaseRequest(String requestId, String barcode, Instant requestTime) {
        return new ReleaseRequest(requestId, barcode, WINDOW_START, WINDOW_END, requestTime);
    }

    @Test
    void release_releasableUnit_succeedsAndCreatesIssueAtomically() {
        makeReleasable("R0001");
        Instant requestTime = Instant.parse("2026-09-25T11:00:00Z");

        ReleaseResponse response = bloodService.release(releaseRequest("REQ-1", "R0001", requestTime));

        assertThat(response.status()).as(response.toString()).isEqualTo("SUCCESS");
        assertThat(response.rejectReason()).isNull();
        assertThat(response.replay()).isFalse();
        assertThat(response.issuedAt()).isEqualTo(requestTime);
        assertThat(response.unitStatus()).isEqualTo("RELEASED");

        BloodUnit unit = bloodService.getUnit("R0001");
        assertThat(unit.getStatus()).isEqualTo(UnitStatus.RELEASED);
        // 发血记录与库存扣减同事务落库，关键字段一致
        var issue = issueRepository.findByBarcode("R0001").orElseThrow();
        assertThat(issue.getRequestId()).isEqualTo("REQ-1");
        assertThat(issue.getIssuedAt()).isEqualTo(requestTime);
        assertThat(issue.getExpiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(issueRepository.count()).isEqualTo(1);
        var order = orderRepository.findByRequestId("REQ-1").orElseThrow();
        assertThat(order.getStatus().name()).isEqualTo("SUCCESS");
        assertThat(order.getIssuedAt()).isEqualTo(requestTime);
    }

    @Test
    void release_atExactExpiryBoundary_isStillAllowed() {
        // 恰在效期边界时刻仍可放行
        makeReleasable("R0002");

        ReleaseResponse response = bloodService.release(releaseRequest("REQ-2", "R0002", EXPIRES_AT));

        assertThat(response.status()).isEqualTo("SUCCESS");
        assertThat(bloodService.getUnit("R0002").getStatus()).isEqualTo(UnitStatus.RELEASED);
    }

    @Test
    void release_oneSecondAfterBoundary_isRejectedAndMarkedExpired() {
        // 到达效期边界之后禁止放行，并标记过期报废，与人工报废口径一致
        makeReleasable("R0003");
        Instant oneSecondLate = EXPIRES_AT.plusSeconds(1);

        ReleaseResponse response = bloodService.release(releaseRequest("REQ-3", "R0003", oneSecondLate));

        assertThat(response.status()).isEqualTo("REJECTED");
        assertThat(response.rejectReason()).isEqualTo("EXPIRED");
        BloodUnit unit = bloodService.getUnit("R0003");
        assertThat(unit.getStatus()).isEqualTo(UnitStatus.EXPIRED);
        assertThat(unit.getScrapReason()).isEqualTo("EXPIRED");
        assertThat(unit.getExpiredAt()).isEqualTo(oneSecondLate);
        assertThat(issueRepository.count()).isZero();
    }

    @Test
    void release_expiredUnitOnLaterRequest_isRejectedWithoutStateChange() {
        makeReleasable("R0004");
        bloodService.release(releaseRequest("REQ-4A", "R0004", EXPIRES_AT.plusSeconds(1)));
        Instant expiredAt = bloodService.getUnit("R0004").getExpiredAt();

        ReleaseResponse again = bloodService.release(releaseRequest("REQ-4B", "R0004", EXPIRES_AT.plusSeconds(10)));

        assertThat(again.status()).isEqualTo("REJECTED");
        assertThat(again.rejectReason()).isEqualTo("EXPIRED");
        assertThat(again.unitStatus()).isEqualTo("EXPIRED");
        assertThat(bloodService.getUnit("R0004").getExpiredAt()).isEqualTo(expiredAt);
    }

    @Test
    void release_outsideWindow_isRejected() {
        makeReleasable("R0005");

        // 时间在效期内但早于放行时间窗起点
        ReleaseResponse response = bloodService.release(
                releaseRequest("REQ-5", "R0005", WINDOW_START.minusSeconds(1)));

        assertThat(response.status()).isEqualTo("REJECTED");
        assertThat(response.rejectReason()).isEqualTo("OUTSIDE_WINDOW");
        assertThat(bloodService.getUnit("R0005").getStatus()).isEqualTo(UnitStatus.RELEASABLE);
    }

    @Test
    void release_quarantinedUnit_isRejectedNotReleasable() {
        bloodService.intake(new IntakeRequest("R0006", ComponentType.RED_CELL, EXPIRES_AT));
        bloodService.reportTestResult(new TestResultRequest("R0006", TestType.INITIAL, TestResultCode.PASS, null));

        ReleaseResponse response = bloodService.release(
                releaseRequest("REQ-6", "R0006", Instant.parse("2026-09-25T11:00:00Z")));

        assertThat(response.status()).isEqualTo("REJECTED");
        assertThat(response.rejectReason()).isEqualTo("NOT_RELEASABLE");
        assertThat(response.unitStatus()).isEqualTo("QUARANTINED");
        assertThat(issueRepository.count()).isZero();
    }

    @Test
    void release_scrappedUnit_isRejected() {
        bloodService.intake(new IntakeRequest("R0007", ComponentType.RED_CELL, EXPIRES_AT));
        bloodService.scrap("R0007");

        ReleaseResponse response = bloodService.release(
                releaseRequest("REQ-7", "R0007", Instant.parse("2026-09-25T11:00:00Z")));

        assertThat(response.status()).isEqualTo("REJECTED");
        assertThat(response.rejectReason()).isEqualTo("SCRAPPED");
    }

    @Test
    void sameRequestAndSameParams_replaysFirstResult_withoutSecondIssue() {
        makeReleasable("R0008");
        Instant requestTime = Instant.parse("2026-09-25T11:00:00Z");
        ReleaseRequest first = releaseRequest("REQ-8", "R0008", requestTime);

        ReleaseResponse firstResponse = bloodService.release(first);
        ReleaseResponse replay = bloodService.release(releaseRequest("REQ-8", "R0008", requestTime));

        assertThat(firstResponse.status()).isEqualTo("SUCCESS");
        assertThat(replay.status()).isEqualTo("SUCCESS");
        assertThat(replay.replay()).isTrue();
        assertThat(replay.issuedAt()).isEqualTo(firstResponse.issuedAt());
        // 重放不重复扣减、不重复生成发血记录
        assertThat(issueRepository.count()).isEqualTo(1);
    }

    @Test
    void sameRequestWithDifferentWindow_returns409_andDoesNotChangeStock() {
        makeReleasable("R0009");
        bloodService.release(releaseRequest("REQ-9", "R0009", Instant.parse("2026-09-25T11:00:00Z")));

        ReleaseRequest conflicting = new ReleaseRequest("REQ-9", "R0009",
                WINDOW_START, WINDOW_END.plusSeconds(60),
                Instant.parse("2026-09-25T11:05:00Z"));

        assertThatThrownBy(() -> bloodService.release(conflicting))
                .isInstanceOf(ConflictException.class);
        // 库存状态不被冲突请求改变
        assertThat(bloodService.getUnit("R0009").getStatus()).isEqualTo(UnitStatus.RELEASED);
        assertThat(issueRepository.count()).isEqualTo(1);
    }

    @Test
    void sameRequestWithDifferentBarcode_returns409() {
        makeReleasable("R0010A");
        makeReleasable("R0010B");
        bloodService.release(releaseRequest("REQ-10", "R0010A", Instant.parse("2026-09-25T11:00:00Z")));

        assertThatThrownBy(() -> bloodService.release(
                releaseRequest("REQ-10", "R0010B", Instant.parse("2026-09-25T11:01:00Z"))))
                .isInstanceOf(ConflictException.class);
        // 第二个单位未被放行
        assertThat(bloodService.getUnit("R0010B").getStatus()).isEqualTo(UnitStatus.RELEASABLE);
        assertThat(issueRepository.count()).isEqualTo(1);
    }

    @Test
    void rejectedRequest_sameKeyReplay_returnsFirstRejection() {
        bloodService.intake(new IntakeRequest("R0011", ComponentType.RED_CELL, EXPIRES_AT));
        Instant requestTime = Instant.parse("2026-09-25T11:00:00Z");

        ReleaseResponse first = bloodService.release(releaseRequest("REQ-11", "R0011", requestTime));
        assertThat(first.status()).isEqualTo("REJECTED");
        assertThat(first.rejectReason()).isEqualTo("NOT_RELEASABLE");

        // 即使之后双检合格变为可放行，同键重放仍返回首次结论
        bloodService.reportTestResult(new TestResultRequest("R0011", TestType.INITIAL, TestResultCode.PASS, null));
        bloodService.reportTestResult(new TestResultRequest("R0011", TestType.REPEAT, TestResultCode.PASS, null));

        ReleaseResponse replay = bloodService.release(releaseRequest("REQ-11", "R0011", requestTime));
        assertThat(replay.status()).isEqualTo("REJECTED");
        assertThat(replay.replay()).isTrue();
        assertThat(issueRepository.count()).isZero();
    }

    @Test
    void release_quarantinedUnitAfterExpiry_isRejectedAndMarkedExpired() {
        // 过期单位在任何放行请求中一律拒绝并标记过期报废（即使双检未齐、处于隔离）
        bloodService.intake(new IntakeRequest("R0012", ComponentType.RED_CELL, EXPIRES_AT));
        bloodService.reportTestResult(new TestResultRequest("R0012", TestType.INITIAL, TestResultCode.PASS, null));

        ReleaseResponse response = bloodService.release(releaseRequest("REQ-12", "R0012", EXPIRES_AT.plusSeconds(1)));

        assertThat(response.status()).isEqualTo("REJECTED");
        assertThat(response.rejectReason()).isEqualTo("EXPIRED");
        assertThat(bloodService.getUnit("R0012").getStatus()).isEqualTo(UnitStatus.EXPIRED);
        assertThat(issueRepository.count()).isZero();
    }

    @Test
    void concurrentReleases_sameBarcode_onlyOneSucceeds() throws Exception {
        makeReleasable("R0012");
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ReleaseResponse>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            String requestId = "REQ-C-" + i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return bloodService.release(releaseRequest(requestId, "R0012",
                        Instant.parse("2026-09-25T11:00:00Z")));
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        List<ReleaseResponse> responses = new ArrayList<>();
        for (Future<ReleaseResponse> f : futures) {
            responses.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        long success = responses.stream().filter(r -> r.status().equals("SUCCESS")).count();
        long rejected = responses.stream().filter(r -> r.status().equals("REJECTED")).count();
        assertThat(success).isEqualTo(1);
        assertThat(rejected).isEqualTo(threads - 1);
        // 失败方拿到的是单位当前真实状态
        assertThat(responses).allSatisfy(r -> assertThat(r.unitStatus()).isEqualTo("RELEASED"));
        assertThat(responses.stream().filter(r -> r.status().equals("REJECTED")))
                .allSatisfy(r -> assertThat(r.rejectReason()).isEqualTo("UNIT_RELEASED"));
        assertThat(issueRepository.count()).isEqualTo(1);
        assertThat(bloodService.getUnit("R0012").getStatus()).isEqualTo(UnitStatus.RELEASED);
    }

    @Test
    void releaseRollsBack_whenIssueInsertConflicts_unitStaysReleasableAndNoOrder() {
        // 人为预置一条发血记录（模拟发血落库但本事务需回滚的场景），
        // 验证发血记录写入失败时库存扣减与放行单同生同灭、整体回滚
        makeReleasable("R0013");
        jdbcTemplate.update("INSERT INTO blood_issue (barcode, request_id, issued_at, expires_at, created_at) "
                        + "VALUES ('R0013', 'REQ-PRE', '2026-09-25 10:30:00', '2026-09-30 15:59:59', '2026-09-25 10:30:00')");

        assertThatThrownBy(() -> bloodService.release(
                releaseRequest("REQ-13", "R0013", Instant.parse("2026-09-25T11:00:00Z"))))
                .isInstanceOf(ConflictException.class);

        assertThat(bloodService.getUnit("R0013").getStatus()).isEqualTo(UnitStatus.RELEASABLE);
        assertThat(orderRepository.findByRequestId("REQ-13")).isEmpty();
        assertThat(issueRepository.count()).isEqualTo(1);
    }
}
