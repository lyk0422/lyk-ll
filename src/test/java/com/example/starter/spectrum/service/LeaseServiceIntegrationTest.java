package com.example.starter.spectrum.service;

import com.example.starter.spectrum.AbstractSpectrumIntegrationTest;
import com.example.starter.spectrum.LeaseMode;
import com.example.starter.spectrum.api.CancelLeaseRequest;
import com.example.starter.spectrum.api.CreateLeaseRequest;
import com.example.starter.spectrum.api.LeaseResponse;
import com.example.starter.spectrum.api.ModifyLeaseRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 租约协调服务 H2 集成测试：主流程、失败分支、多区域事务回滚、终态取消、版本与指纹。
 */
@Import(AbstractSpectrumIntegrationTest.FixedClockConfiguration.class)
class LeaseServiceIntegrationTest extends AbstractSpectrumIntegrationTest {

    private static final Instant S = Instant.parse("2030-01-01T10:00:00Z");
    private static final Instant E = Instant.parse("2030-01-01T12:00:00Z");

    @Autowired
    private LeaseService leaseService;

    private CreateLeaseRequest create(String reqId, Set<String> regions, Instant s, Instant e,
                                     String fl, String fh, LeaseMode mode, String eirp) {
        return new CreateLeaseRequest(reqId, regions, s, e, new BigDecimal(fl), new BigDecimal(fh),
                mode, new BigDecimal(eirp));
    }

    private ModifyLeaseRequest modify(String reqId, String leaseId, int expectedVersion,
                                     Set<String> regions, Instant s, Instant e,
                                     String fl, String fh, LeaseMode mode, String eirp) {
        return new ModifyLeaseRequest(reqId, leaseId, expectedVersion, regions, s, e,
                new BigDecimal(fl), new BigDecimal(fh), mode, new BigDecimal(eirp));
    }

    @Test
    void createSharedLeaseReturns201AndPersistsRegionsAndEvent() {
        LeaseService.ServiceResult result = leaseService.create(create(
                "req-1", Set.of("CELL-A", "CELL-B"), S, E, "100", "200",
                LeaseMode.SHARED, "500"));

        assertThat(result.httpStatus()).isEqualTo(201);
        LeaseResponse response = (LeaseResponse) result.body();
        assertThat(response.leaseId()).startsWith("L-");
        assertThat(response.version()).isEqualTo(1);
        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.regions()).containsExactly("CELL-A", "CELL-B");
        assertThat(response.events()).extracting(LeaseResponse.EventView::eventType)
                .containsExactly("CREATED");

        Integer regionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lease_region WHERE lease_id = ? AND version = 1",
                Integer.class, response.leaseId());
        assertThat(regionRows).isEqualTo(2);
    }

    @Test
    void exclusiveConflictIsRejectedAndNothingPersisted() {
        leaseService.create(create("req-a", Set.of("CELL-A"), S, E,
                "100", "200", LeaseMode.SHARED, "500"));

        LeaseService.ServiceResult rejected = leaseService.create(create(
                "req-b", Set.of("CELL-A"), S, E, "100", "200",
                LeaseMode.EXCLUSIVE, "10"));

        assertThat(rejected.httpStatus()).isEqualTo(409);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) rejected.body();
        assertThat(body.get("code")).isEqualTo("LEASE_CONFLICT");
        assertThat((List<?>) body.get("details")).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM lease", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lease_request WHERE http_status = 409", Integer.class)).isEqualTo(1);
    }

    @Test
    void touchingHalfOpenBoundariesDoNotConflict() {
        leaseService.create(create("req-a", Set.of("CELL-A"), S, E,
                "100", "200", LeaseMode.EXCLUSIVE, "10"));
        // 时间在 E 点相接、频率在 200 点相接：均为半开端点，不相遇
        LeaseService.ServiceResult ok = leaseService.create(create(
                "req-b", Set.of("CELL-A"), E, Instant.parse("2030-01-01T14:00:00Z"),
                "200", "300", LeaseMode.EXCLUSIVE, "10"));
        assertThat(ok.httpStatus()).isEqualTo(201);
    }

    @Test
    void multiSharedFragmentExceedanceIsRejected() {
        // o1 时间覆盖前段、频率低段；o2 时间覆盖后段、频率高段：
        // 两两之和均为 800，仅在 11:00-11:30 × 150-200MHz 的内部片段三者同时生效 => 1200
        leaseService.create(create("o1", Set.of("CELL-A"), S,
                Instant.parse("2030-01-01T11:30:00Z"), "100", "200",
                LeaseMode.SHARED, "400"));
        leaseService.create(create("o2", Set.of("CELL-A"),
                Instant.parse("2030-01-01T11:00:00Z"), E, "150", "300",
                LeaseMode.SHARED, "400"));

        LeaseService.ServiceResult rejected = leaseService.create(create(
                "new", Set.of("CELL-A"), S, E, "100", "300",
                LeaseMode.SHARED, "400"));
        assertThat(rejected.httpStatus()).isEqualTo(409);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) rejected.body();
        List<?> details = (List<?>) body.get("details");
        assertThat(details).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM lease", Integer.class)).isEqualTo(2);
    }

    @Test
    void multiRegionCreateIsAtomicOnConflict() {
        // 已存在独占仅占 CELL-C；候选同时申请 CELL-A/B/C，多区域必须整体回滚
        leaseService.create(create("base", Set.of("CELL-C"), S, E,
                "100", "200", LeaseMode.EXCLUSIVE, "10"));

        LeaseService.ServiceResult rejected = leaseService.create(create(
                "multi", Set.of("CELL-A", "CELL-B", "CELL-C"), S, E,
                "100", "200", LeaseMode.SHARED, "10"));
        assertThat(rejected.httpStatus()).isEqualTo(409);

        // 多区域租约任何区域行、版本行都不得残留
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lease_region WHERE region_cell IN ('CELL-A','CELL-B')",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM lease", Integer.class)).isEqualTo(1);
    }

    @Test
    void modifyAppendsVersionAndChecksExpectedVersion() {
        LeaseResponse first = (LeaseResponse) leaseService.create(create(
                "m-1", Set.of("CELL-A"), S, E, "100", "200",
                LeaseMode.SHARED, "100")).body();

        LeaseService.ServiceResult stale = leaseService.modify(modify(
                "m-2", first.leaseId(), 99, Set.of("CELL-A"), S, E,
                "100", "200", LeaseMode.SHARED, "100"));
        assertThat(stale.httpStatus()).isEqualTo(409);
        assertThat(((Map<?, ?>) stale.body()).get("code")).isEqualTo("VERSION_MISMATCH");

        LeaseService.ServiceResult ok = leaseService.modify(modify(
                "m-3", first.leaseId(), 1, Set.of("CELL-A", "CELL-D"), S, E,
                "100", "210", LeaseMode.SHARED, "200"));
        assertThat(ok.httpStatus()).isEqualTo(200);
        LeaseResponse updated = (LeaseResponse) ok.body();
        assertThat(updated.version()).isEqualTo(2);
        assertThat(updated.regions()).containsExactly("CELL-A", "CELL-D");
        assertThat(updated.events()).extracting(LeaseResponse.EventView::eventType)
                .containsExactly("CREATED", "MODIFIED");
        // 历史版本保留
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lease_version WHERE lease_id = ?",
                Integer.class, first.leaseId())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lease_region WHERE lease_id = ? AND version = 1",
                Integer.class, first.leaseId())).isEqualTo(1);
    }

    @Test
    void cancelIsTerminalAndIdempotentWithoutExtraEvent() {
        LeaseResponse first = (LeaseResponse) leaseService.create(create(
                "c-1", Set.of("CELL-A"), S, E, "100", "200",
                LeaseMode.SHARED, "100")).body();

        LeaseService.ServiceResult cancelled = leaseService.cancel(
                new CancelLeaseRequest("c-2", first.leaseId(), 1));
        assertThat(cancelled.httpStatus()).isEqualTo(200);
        assertThat(((LeaseResponse) cancelled.body()).status()).isEqualTo("CANCELLED");

        // 同参再次取消：无副作用成功，不新增事件
        LeaseService.ServiceResult again = leaseService.cancel(
                new CancelLeaseRequest("c-3", first.leaseId(), 1));
        assertThat(again.httpStatus()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lease_event WHERE lease_id = ? AND event_type = 'CANCELLED'",
                Integer.class, first.leaseId())).isEqualTo(1);

        // 已取消租约不能再修改
        LeaseService.ServiceResult modifyAfterCancel = leaseService.modify(modify(
                "c-4", first.leaseId(), 1, Set.of("CELL-A"), S, E,
                "100", "200", LeaseMode.SHARED, "100"));
        assertThat(modifyAfterCancel.httpStatus()).isEqualTo(409);
        assertThat(((Map<?, ?>) modifyAfterCancel.body()).get("code")).isEqualTo("LEASE_TERMINATED");
    }

    @Test
    void requestFingerprintSameKeySameParamsReplaysFirstResult() {
        LeaseService.ServiceResult first = leaseService.create(create(
                "idem", Set.of("CELL-A"), S, E, "100", "200",
                LeaseMode.SHARED, "100"));
        LeaseService.ServiceResult replay = leaseService.create(create(
                "idem", Set.of("CELL-A"), S, E, "100", "200",
                LeaseMode.SHARED, "100"));

        assertThat(replay.httpStatus()).isEqualTo(201);
        LeaseResponse r1 = (LeaseResponse) first.body();
        LeaseResponse r2 = (LeaseResponse) replay.body();
        assertThat(r2.leaseId()).isEqualTo(r1.leaseId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lease_request WHERE request_id = 'idem'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM lease", Integer.class)).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentParamsReturns409() {
        leaseService.create(create("idem2", Set.of("CELL-A"), S, E,
                "100", "200", LeaseMode.SHARED, "100"));
        LeaseService.ServiceResult clash = leaseService.create(create(
                "idem2", Set.of("CELL-A"), S, E, "100", "201",
                LeaseMode.SHARED, "100"));
        assertThat(clash.httpStatus()).isEqualTo(409);
        assertThat(((Map<?, ?>) clash.body()).get("code")).isEqualTo("IDEMPOTENCY_MISMATCH");
    }

    @Test
    void activeSnapshotRespectsWindowAndCancellation() {
        LeaseResponse lease = (LeaseResponse) leaseService.create(create(
                "snap", Set.of("CELL-A"), S, E, "100", "200",
                LeaseMode.SHARED, "100")).body();

        Instant within = Instant.parse("2030-01-01T11:00:00Z");
        List<LeaseResponse> active = leaseService.activeSnapshot(within);
        assertThat(active).extracting(LeaseResponse::leaseId).containsExactly(lease.leaseId());

        // 端点：E 为半开右端点，E 时刻不在快照中
        assertThat(leaseService.activeSnapshot(E)).isEmpty();
        // 开始端点 S 时刻在快照中
        assertThat(leaseService.activeSnapshot(S)).hasSize(1);

        leaseService.cancel(new CancelLeaseRequest("snap-cancel", lease.leaseId(), 1));
        // 取消在 NOW(=2030-01-01T00:00Z) 已生效，未来窗口内的快照不再包含该租约
        assertThat(leaseService.activeSnapshot(within)).isEmpty();
    }

    @Test
    void occupancyFiltersByRegionAndFrequency() {
        LeaseResponse a = (LeaseResponse) leaseService.create(create(
                "occ-a", Set.of("CELL-A"), S, E, "100", "200",
                LeaseMode.SHARED, "100")).body();
        leaseService.create(create("occ-b", Set.of("CELL-B"), S, E,
                "300", "400", LeaseMode.SHARED, "100"));

        Instant at = Instant.parse("2030-01-01T11:00:00Z");
        List<LeaseResponse> hit = leaseService.occupancy(at, List.of("CELL-A"),
                new BigDecimal("150"), new BigDecimal("160"));
        assertThat(hit).extracting(LeaseResponse::leaseId).containsExactly(a.leaseId());

        // 频段端点相接（查询 [200,300)）：半开不重叠
        assertThat(leaseService.occupancy(at, List.of("CELL-A"),
                new BigDecimal("200"), new BigDecimal("300"))).isEmpty();
        // 区域不匹配
        assertThat(leaseService.occupancy(at, List.of("CELL-Z"),
                new BigDecimal("100"), new BigDecimal("200"))).isEmpty();
    }

    @Test
    void explainLogicIsReadOnly() {
        leaseService.create(create("exp-base", Set.of("CELL-A"), S, E,
                "100", "200", LeaseMode.EXCLUSIVE, "10"));
        var details = leaseService.explain(Set.of("CELL-A"), S, E,
                new BigDecimal("100"), new BigDecimal("200"),
                LeaseMode.SHARED, new BigDecimal("10"));
        assertThat(details).hasSize(1);
        assertThat(details.get(0).reason()).isEqualTo("EXCLUSIVE_OVERLAP");
        // 只读：租约数不变，预检不新增幂等记录（基线创建本身有 1 条）
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM lease", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM lease_request", Integer.class)).isEqualTo(1);
    }

    @Test
    void invalidWindowReturns400() {
        LeaseService.ServiceResult badTime = leaseService.create(create(
                "bad-t", Set.of("CELL-A"), E, S, "100", "200",
                LeaseMode.SHARED, "100"));
        assertThat(badTime.httpStatus()).isEqualTo(400);
        assertThat(((Map<?, ?>) badTime.body()).get("code")).isEqualTo("INVALID_TIME_WINDOW");

        LeaseService.ServiceResult badFreq = leaseService.create(create(
                "bad-f", Set.of("CELL-A"), S, E, "200", "100",
                LeaseMode.SHARED, "100"));
        assertThat(badFreq.httpStatus()).isEqualTo(400);
        assertThat(((Map<?, ?>) badFreq.body()).get("code")).isEqualTo("INVALID_FREQ_WINDOW");
    }
}
