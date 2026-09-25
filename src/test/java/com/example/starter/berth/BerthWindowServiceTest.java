package com.example.starter.berth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 靠泊窗口核心业务测试（H2 内存库，MODE=MySQL）。
 * 覆盖：半开区间冲突、端点相接、引航员复用、限深拒绝、允许时段、
 * 同键重放、同键异参、终态幂等与防复活、到点生效、改期重校验。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class BerthWindowServiceTest {

    /** 允许时段：每日 UTC 06:00-12:00、13:00-22:00 */
    private static final String D = "2026-10-10T";

    @Autowired
    private BerthWindowService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM window_pilot");
        jdbc.update("DELETE FROM berth_window");
        clock.setInstant(TestClockConfig.BASE);
    }

    private ApplyWindowRequest req(String requestId, String berthId,
                                   String start, String end, String draft, int pilots) {
        return new ApplyWindowRequest(requestId, "MV-" + requestId, berthId,
                Instant.parse(start), Instant.parse(end), new BigDecimal(draft), pilots, null);
    }

    private int windowCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM berth_window", Integer.class);
        return count == null ? 0 : count;
    }

    private int assignmentCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM window_pilot", Integer.class);
        return count == null ? 0 : count;
    }

    @Test
    void applyCreatesPendingWindow() {
        WindowResponse response = service.apply(
                req("R-1", "BERTH-1", D + "06:00:00Z", D + "10:00:00Z", "10.5", 2));

        assertThat(response.status()).isEqualTo(WindowStatus.PENDING);
        assertThat(response.requestId()).isEqualTo("R-1");
        assertThat(response.berthId()).isEqualTo("BERTH-1");
        assertThat(response.assignedPilots()).isEmpty();
        assertThat(windowCount()).isEqualTo(1);
    }

    @Test
    void draftExceedingBerthLimitRejectedWithoutRecord() {
        // BERTH-2 限深 8 米，吃水 8.01 米直接拒绝
        assertThatThrownBy(() -> service.apply(
                req("R-DRAFT", "BERTH-2", D + "06:00:00Z", D + "10:00:00Z", "8.01", 1)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.code()).isEqualTo("DRAFT_EXCEEDS_LIMIT");
                });
        // 不产生记录
        assertThat(windowCount()).isZero();
        // 同键重放仍被拒绝（无记录可幂等）
        assertThatThrownBy(() -> service.apply(
                req("R-DRAFT", "BERTH-2", D + "06:00:00Z", D + "10:00:00Z", "8.01", 1)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo("DRAFT_EXCEEDS_LIMIT"));
        // 吃水恰等于限深合法
        WindowResponse ok = service.apply(
                req("R-DRAFT-EQ", "BERTH-2", D + "06:00:00Z", D + "10:00:00Z", "8.00", 1));
        assertThat(ok.status()).isEqualTo(WindowStatus.PENDING);
    }

    @Test
    void unknownBerthRejected() {
        assertThatThrownBy(() -> service.apply(
                req("R-NOBERTH", "BERTH-X", D + "06:00:00Z", D + "10:00:00Z", "5", 0)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo("BERTH_NOT_FOUND"));
        assertThat(windowCount()).isZero();
    }

    @Test
    void outsideAllowedWindowRejectedButBoundaryExactAccepted() {
        // 04:00-07:00 未完整落入 06:00-12:00
        assertThatThrownBy(() -> service.apply(
                req("R-OUT", "BERTH-1", D + "04:00:00Z", D + "07:00:00Z", "5", 0)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo("OUTSIDE_ALLOWED_WINDOW"));
        // 跨两段允许时段（12:00-13:00 为间隔）不合法
        assertThatThrownBy(() -> service.apply(
                req("R-OUT2", "BERTH-1", D + "11:00:00Z", D + "14:00:00Z", "5", 0)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo("OUTSIDE_ALLOWED_WINDOW"));
        // 端点恰等于允许时段边界合法
        WindowResponse ok = service.apply(
                req("R-EDGE", "BERTH-1", D + "06:00:00Z", D + "12:00:00Z", "5", 0));
        assertThat(ok.status()).isEqualTo(WindowStatus.PENDING);
    }

    @Test
    void endNotAfterStartRejected() {
        assertThatThrownBy(() -> service.apply(
                req("R-BADTIME", "BERTH-1", D + "10:00:00Z", D + "10:00:00Z", "5", 0)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo("INVALID_TIME_RANGE"));
        assertThat(windowCount()).isZero();
    }

    @Test
    void halfOpenInternalOverlapConflictsAtApproval() {
        service.apply(req("A", "BERTH-1", D + "06:00:00Z", D + "10:00:00Z", "5", 0));
        service.approve("A");
        service.apply(req("B", "BERTH-1", D + "08:00:00Z", D + "12:00:00Z", "5", 0));

        assertThatThrownBy(() -> service.approve("B"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.code()).isEqualTo("BERTH_WINDOW_CONFLICT");
                });
        // 冲突审批失败不产生副作用：B 仍为待审批
        assertThat(service.get("B").status()).isEqualTo(WindowStatus.PENDING);
    }

    @Test
    void touchingEndpointsDoNotConflict() {
        service.apply(req("A", "BERTH-1", D + "06:00:00Z", D + "10:00:00Z", "5", 0));
        service.approve("A");
        // 端点恰好相接 [10:00, 12:00) 不算冲突
        service.apply(req("B", "BERTH-1", D + "10:00:00Z", D + "12:00:00Z", "5", 0));
        WindowResponse approved = service.approve("B");

        assertThat(approved.status()).isEqualTo(WindowStatus.APPROVED);
    }

    @Test
    void pilotCannotServeTwoVesselsAtSamePeriod() {
        // 共 3 名引航员；A 占用 2 名（06:00-10:00）
        service.apply(req("A", "BERTH-1", D + "06:00:00Z", D + "10:00:00Z", "5", 2));
        WindowResponse a = service.approve("A");
        assertThat(a.assignedPilots()).containsExactly("PILOT-1", "PILOT-2");

        // B 与 A 时段重叠且需 2 名：仅剩 1 名可用，审批失败
        service.apply(req("B", "BERTH-2", D + "08:00:00Z", D + "12:00:00Z", "5", 2));
        assertThatThrownBy(() -> service.approve("B"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo("PILOT_SHORTAGE"));
        assertThat(service.get("B").status()).isEqualTo(WindowStatus.PENDING);
        assertThat(assignmentCount()).isEqualTo(2);

        // C 与 A 时段重叠但仅需 1 名（不同泊位）：复用剩余引航员，审批通过
        service.apply(req("C", "BERTH-3", D + "08:00:00Z", D + "12:00:00Z", "5", 1));
        WindowResponse c = service.approve("C");
        assertThat(c.status()).isEqualTo(WindowStatus.APPROVED);
        assertThat(c.assignedPilots()).containsExactly("PILOT-3");

        // A 完成后引航员释放，B 可获批
        clock.setInstant(Instant.parse(D + "10:30:00Z"));
        service.activateDueWindows();
        service.depart("A");
        WindowResponse b = service.approve("B");
        assertThat(b.status()).isEqualTo(WindowStatus.APPROVED);
    }

    @Test
    void sameKeySameParamsReturnsFirstResult() {
        ApplyWindowRequest request = req("R-IDEM", "BERTH-1", D + "06:00:00Z", D + "10:00:00Z", "5", 1);
        WindowResponse first = service.apply(request);
        WindowResponse replay = service.apply(request);

        assertThat(replay).isEqualTo(first);
        assertThat(windowCount()).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentParamsReturns409WithoutNewRecord() {
        service.apply(req("R-IDEM2", "BERTH-1", D + "06:00:00Z", D + "10:00:00Z", "5", 1));

        ApplyWindowRequest mutated = new ApplyWindowRequest("R-IDEM2", "MV-OTHER", "BERTH-1",
                Instant.parse(D + "06:00:00Z"), Instant.parse(D + "10:00:00Z"),
                new BigDecimal("5"), 1, null);
        assertThatThrownBy(() -> service.apply(mutated))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.code()).isEqualTo("REQUEST_ID_CONFLICT");
                });
        assertThat(windowCount()).isEqualTo(1);
    }

    @Test
    void fingerprintIsAmbiguityFree() {
        // 字段拼接歧义用例：["ab","c"] 与 ["a","bc"] 必须产生不同指纹
        ApplyWindowRequest x = new ApplyWindowRequest("R-F1", "ab", "c",
                Instant.parse(D + "06:00:00Z"), Instant.parse(D + "10:00:00Z"),
                new BigDecimal("5"), 1, null);
        ApplyWindowRequest y = new ApplyWindowRequest("R-F1", "a", "bc",
                Instant.parse(D + "06:00:00Z"), Instant.parse(D + "10:00:00Z"),
                new BigDecimal("5"), 1, null);
        assertThat(Fingerprints.of(x)).isNotEqualTo(Fingerprints.of(y));
        // null 与空串区分
        ApplyWindowRequest z = new ApplyWindowRequest("R-F1", "ab", "c",
                Instant.parse(D + "06:00:00Z"), Instant.parse(D + "10:00:00Z"),
                new BigDecimal("5"), 1, "");
        assertThat(Fingerprints.of(x)).isNotEqualTo(Fingerprints.of(z));
        // 吃水数值规范化：10.0 与 10.00 指纹一致
        ApplyWindowRequest d1 = req("R-F2", "BERTH-1", D + "06:00:00Z", D + "10:00:00Z", "10.0", 1);
        ApplyWindowRequest d2 = req("R-F2", "BERTH-1", D + "06:00:00Z", D + "10:00:00Z", "10.00", 1);
        assertThat(Fingerprints.of(d1)).isEqualTo(Fingerprints.of(d2));
    }

    @Test
    void terminalStatesAreIdempotentAndCannotBeResurrected() {
        // 驳回为终态：重复驳回幂等成功，迟到审批不得复活
        service.apply(req("R-REJ", "BERTH-1", D + "06:00:00Z", D + "08:00:00Z", "5", 0));
        service.reject("R-REJ");
        WindowResponse rejectedAgain = service.reject("R-REJ");
        assertThat(rejectedAgain.status()).isEqualTo(WindowStatus.REJECTED);
        assertThatThrownBy(() -> service.approve("R-REJ"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.code()).isEqualTo("INVALID_STATE");
                });

        // 取消为终态：重复取消幂等成功，迟到审批不得复活
        service.apply(req("R-CXL", "BERTH-1", D + "06:00:00Z", D + "08:00:00Z", "5", 0));
        service.approve("R-CXL");
        service.cancel("R-CXL");
        WindowResponse cancelledAgain = service.cancel("R-CXL");
        assertThat(cancelledAgain.status()).isEqualTo(WindowStatus.CANCELLED);
        assertThatThrownBy(() -> service.approve("R-CXL"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo("INVALID_STATE"));

        // 完成为终态：重复登记离泊幂等成功，迟到取消不得复活
        service.apply(req("R-DONE", "BERTH-2", D + "06:00:00Z", D + "08:00:00Z", "5", 0));
        service.approve("R-DONE");
        clock.setInstant(Instant.parse(D + "08:30:00Z"));
        service.activateDueWindows();
        service.depart("R-DONE");
        WindowResponse completedAgain = service.depart("R-DONE");
        assertThat(completedAgain.status()).isEqualTo(WindowStatus.COMPLETED);
        assertThatThrownBy(() -> service.cancel("R-DONE"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo("INVALID_STATE"));
    }

    @Test
    void approvedWindowActivatesAtStartTime() {
        service.apply(req("R-ACT", "BERTH-1", D + "06:00:00Z", D + "10:00:00Z", "5", 0));
        service.approve("R-ACT");

        // 未到起始时刻不生效
        assertThat(service.activateDueWindows()).isZero();
        assertThat(service.get("R-ACT").status()).isEqualTo(WindowStatus.APPROVED);

        // 到达起始时刻自动生效
        clock.setInstant(Instant.parse(D + "06:00:00Z"));
        assertThat(service.activateDueWindows()).isEqualTo(1);
        assertThat(service.get("R-ACT").status()).isEqualTo(WindowStatus.ACTIVE);

        // 生效后才能登记离泊
        WindowResponse done = service.depart("R-ACT");
        assertThat(done.status()).isEqualTo(WindowStatus.COMPLETED);
    }

    @Test
    void departureRequiresActiveWindow() {
        service.apply(req("R-DEP", "BERTH-1", D + "06:00:00Z", D + "10:00:00Z", "5", 0));
        assertThatThrownBy(() -> service.depart("R-DEP"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo("INVALID_STATE"));
        service.approve("R-DEP");
        assertThatThrownBy(() -> service.depart("R-DEP"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo("INVALID_STATE"));
    }

    @Test
    void rescheduleIsNewApplicationWithFullRevalidationAndSelfExclusion() {
        // 原窗口 A 已批准 [06:00,10:00)
        service.apply(req("A", "BERTH-1", D + "06:00:00Z", D + "10:00:00Z", "5", 0));
        service.approve("A");
        // 另一窗口 C 已批准 [13:00,17:00)
        service.apply(req("C", "BERTH-1", D + "13:00:00Z", D + "17:00:00Z", "5", 0));
        service.approve("C");

        // 改期 A2 [08:00,12:00)：与原窗口 A 自身重叠不算冲突，可获批
        ApplyWindowRequest reschedule = new ApplyWindowRequest("A2", "MV-A", "BERTH-1",
                Instant.parse(D + "08:00:00Z"), Instant.parse(D + "12:00:00Z"),
                new BigDecimal("5"), 0, "A");
        WindowResponse a2 = service.apply(reschedule);
        assertThat(a2.status()).isEqualTo(WindowStatus.PENDING);
        assertThat(a2.replacesRequestId()).isEqualTo("A");
        assertThat(service.approve("A2").status()).isEqualTo(WindowStatus.APPROVED);

        // 改期 A3 [15:00,19:00)：与其他窗口 C 冲突，重校验后拒绝
        ApplyWindowRequest conflicting = new ApplyWindowRequest("A3", "MV-A", "BERTH-1",
                Instant.parse(D + "15:00:00Z"), Instant.parse(D + "19:00:00Z"),
                new BigDecimal("5"), 0, "A");
        service.apply(conflicting);
        assertThatThrownBy(() -> service.approve("A3"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo("BERTH_WINDOW_CONFLICT"));

        // 改期仍须通过限深校验（BERTH-2 限深 8 米）
        ApplyWindowRequest tooDeep = new ApplyWindowRequest("A4", "MV-A", "BERTH-2",
                Instant.parse(D + "13:00:00Z"), Instant.parse(D + "15:00:00Z"),
                new BigDecimal("9"), 0, "A");
        assertThatThrownBy(() -> service.apply(tooDeep))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo("DRAFT_EXCEEDS_LIMIT"));

        // 改期来源不存在
        ApplyWindowRequest missingSource = new ApplyWindowRequest("A5", "MV-A", "BERTH-1",
                Instant.parse(D + "13:00:00Z"), Instant.parse(D + "15:00:00Z"),
                new BigDecimal("5"), 0, "NO-SUCH");
        assertThatThrownBy(() -> service.apply(missingSource))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo("REPLACES_NOT_FOUND"));
    }

    @Test
    void getUnknownWindowReturns404() {
        assertThatThrownBy(() -> service.get("NO-SUCH"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.code()).isEqualTo("WINDOW_NOT_FOUND");
                });
    }

    @Test
    void assignedPilotsListedInResponse() {
        service.apply(req("R-P", "BERTH-3", D + "06:00:00Z", D + "10:00:00Z", "12", 3));
        WindowResponse approved = service.approve("R-P");
        assertThat(approved.assignedPilots())
                .containsExactlyElementsOf(List.of("PILOT-1", "PILOT-2", "PILOT-3"));
    }
}
