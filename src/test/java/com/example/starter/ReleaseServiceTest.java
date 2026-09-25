package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;

import com.example.starter.domain.TestResultValue;
import com.example.starter.domain.TestType;
import com.example.starter.domain.UnitStatus;
import com.example.starter.error.ConflictException;
import com.example.starter.error.NotFoundException;
import com.example.starter.repository.IssueRecordRepository;
import com.example.starter.service.BloodUnitService;
import com.example.starter.service.ReleaseService;
import com.example.starter.web.dto.InboundRequest;
import com.example.starter.web.dto.ReleaseRequest;
import com.example.starter.web.dto.ReleaseResponse;
import com.example.starter.web.dto.ResultCallbackRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 放行服务测试：效期边界、幂等重放、放行与发血原子性、过期拒绝（H2 真实库）。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class ReleaseServiceTest {

    private static final LocalDate EXPIRY = LocalDate.of(2026, 9, 25);
    private static final Instant WINDOW_START = Instant.parse("2026-09-25T08:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-09-25T20:00:00Z");

    @Autowired
    private BloodUnitService unitService;

    @Autowired
    private ReleaseService releaseService;

    @Autowired
    private IssueRecordRepository issues;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM issue_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM result_event");
        jdbc.update("DELETE FROM blood_unit");
        clock.setInstant(Instant.parse("2026-09-25T10:00:00Z"));
    }

    private void releasable(String barcode) {
        unitService.inbound(new InboundRequest(barcode, EXPIRY));
        unitService.recordResult(barcode, new ResultCallbackRequest(TestType.INITIAL, TestResultValue.PASS));
        unitService.recordResult(barcode, new ResultCallbackRequest(TestType.RECHECK, TestResultValue.PASS));
    }

    private ReleaseRequest request(String requestId, String barcode) {
        return new ReleaseRequest(requestId, barcode, WINDOW_START, WINDOW_END);
    }

    @Test
    void 可放行单位放行成功并原子生成发血记录() {
        releasable("RL-001");

        ReleaseResponse response = releaseService.release(request("REQ-001", "RL-001"));

        assertThat(response.outcome()).isEqualTo("RELEASED");
        assertThat(response.currentStatus()).isEqualTo(UnitStatus.RELEASED);
        assertThat(unitService.getByBarcode("RL-001").status()).isEqualTo(UnitStatus.RELEASED);
        assertThat(issues.countByBarcode("RL-001")).isEqualTo(1);
    }

    @Test
    void 恰在效期边界时刻仍可放行() {
        releasable("RL-002");
        // 效期当日 23:59:59 UTC：边界时刻仍可放行
        clock.setInstant(Instant.parse("2026-09-25T23:59:59Z"));

        ReleaseResponse response = releaseService.release(request("REQ-002", "RL-002"));

        assertThat(response.outcome()).isEqualTo("RELEASED");
        assertThat(issues.countByBarcode("RL-002")).isEqualTo(1);
    }

    @Test
    void 超过效期边界拒绝放行并标记过期报废() {
        releasable("RL-003");
        // 次日 00:00:00 UTC：已过效期当日 23:59:59
        clock.setInstant(Instant.parse("2026-09-26T00:00:00Z"));

        assertThatThrownBy(() -> releaseService.release(request("REQ-003", "RL-003")))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> {
                    ConflictException ce = (ConflictException) e;
                    assertThat(ce.getCode()).isEqualTo("UNIT_EXPIRED");
                    assertThat(ce.getCurrentStatus()).isEqualTo(UnitStatus.EXPIRED_DISCARDED);
                });

        // 标记过期报废，与人工报废口径一致；且无任何发血记录
        assertThat(unitService.getByBarcode("RL-003").status()).isEqualTo(UnitStatus.EXPIRED_DISCARDED);
        assertThat(issues.countByBarcode("RL-003")).isZero();
    }

    @Test
    void 过期报废为终态后续合格回传不复活() {
        unitService.inbound(new InboundRequest("RL-004", EXPIRY));
        unitService.recordResult("RL-004", new ResultCallbackRequest(TestType.INITIAL, TestResultValue.PASS));
        clock.setInstant(Instant.parse("2026-09-26T01:00:00Z"));
        assertThatThrownBy(() -> releaseService.release(request("REQ-004", "RL-004")))
                .isInstanceOf(ConflictException.class);
        assertThat(unitService.getByBarcode("RL-004").status()).isEqualTo(UnitStatus.EXPIRED_DISCARDED);

        // 迟到的复检合格回传不得复活
        var late = unitService.recordResult("RL-004",
                new ResultCallbackRequest(TestType.RECHECK, TestResultValue.PASS));

        assertThat(late.status()).isEqualTo(UnitStatus.EXPIRED_DISCARDED);
        assertThat(late.recheckResult()).isNull();
    }

    @Test
    void 隔离状态单位放行被拒且不产生发血记录() {
        unitService.inbound(new InboundRequest("RL-005", EXPIRY));
        unitService.recordResult("RL-005", new ResultCallbackRequest(TestType.INITIAL, TestResultValue.PASS));

        assertThatThrownBy(() -> releaseService.release(request("REQ-005", "RL-005")))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).getCurrentStatus())
                        .isEqualTo(UnitStatus.QUARANTINED));

        assertThat(unitService.getByBarcode("RL-005").status()).isEqualTo(UnitStatus.QUARANTINED);
        assertThat(issues.countByBarcode("RL-005")).isZero();
    }

    @Test
    void 报废终态单位放行被拒() {
        unitService.inbound(new InboundRequest("RL-006", EXPIRY));
        unitService.recordResult("RL-006", new ResultCallbackRequest(TestType.INITIAL, TestResultValue.FAIL));

        assertThatThrownBy(() -> releaseService.release(request("REQ-006", "RL-006")))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).getCurrentStatus())
                        .isEqualTo(UnitStatus.DISCARDED));
        assertThat(issues.countByBarcode("RL-006")).isZero();
    }

    @Test
    void 未知条码放行返回不存在() {
        assertThatThrownBy(() -> releaseService.release(request("REQ-404", "RL-404")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void 同键同参重放返回首次结果且不重复发血() {
        releasable("RL-007");
        ReleaseResponse first = releaseService.release(request("REQ-007", "RL-007"));

        ReleaseResponse replay = releaseService.release(request("REQ-007", "RL-007"));

        assertThat(replay.outcome()).isEqualTo(first.outcome());
        assertThat(replay.currentStatus()).isEqualTo(UnitStatus.RELEASED);
        assertThat(issues.countByBarcode("RL-007")).isEqualTo(1);
    }

    @Test
    void 同键异参返回冲突且不改变库存状态() {
        releasable("RL-008");
        releaseService.release(request("REQ-008", "RL-008"));

        ReleaseRequest tampered = new ReleaseRequest("REQ-008", "RL-008",
                WINDOW_START, Instant.parse("2026-09-25T21:00:00Z"));
        assertThatThrownBy(() -> releaseService.release(tampered))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).getCode())
                        .isEqualTo("IDEMPOTENCY_CONFLICT"));

        assertThat(unitService.getByBarcode("RL-008").status()).isEqualTo(UnitStatus.RELEASED);
        assertThat(issues.countByBarcode("RL-008")).isEqualTo(1);
    }

    @Test
    void 首次拒绝的放行单重放仍返回首次拒绝结果() {
        unitService.inbound(new InboundRequest("RL-009", EXPIRY));
        unitService.recordResult("RL-009", new ResultCallbackRequest(TestType.INITIAL, TestResultValue.PASS));
        // 首次：隔离状态被拒
        assertThatThrownBy(() -> releaseService.release(request("REQ-009", "RL-009")))
                .isInstanceOf(ConflictException.class);

        // 单位随后变为可放行，但同键同参重放仍返回首次的拒绝结果
        unitService.recordResult("RL-009", new ResultCallbackRequest(TestType.RECHECK, TestResultValue.PASS));
        assertThat(unitService.getByBarcode("RL-009").status()).isEqualTo(UnitStatus.RELEASABLE);

        assertThatThrownBy(() -> releaseService.release(request("REQ-009", "RL-009")))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).getCode())
                        .isEqualTo("RELEASE_REJECTED_REPLAY"));
        assertThat(unitService.getByBarcode("RL-009").status()).isEqualTo(UnitStatus.RELEASABLE);
        assertThat(issues.countByBarcode("RL-009")).isZero();
    }

    @Test
    void 重复放行已放行单位返回当前真实状态() {
        releasable("RL-010");
        releaseService.release(request("REQ-010A", "RL-010"));

        assertThatThrownBy(() -> releaseService.release(request("REQ-010B", "RL-010")))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).getCurrentStatus())
                        .isEqualTo(UnitStatus.RELEASED));
        assertThat(issues.countByBarcode("RL-010")).isEqualTo(1);
    }
}
