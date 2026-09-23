package com.example.starter.spectrum;

import com.example.starter.spectrum.api.ConflictResponse;
import com.example.starter.spectrum.api.LeaseCancelRequest;
import com.example.starter.spectrum.api.LeaseResponse;
import com.example.starter.spectrum.api.LeaseUpsertRequest;
import com.example.starter.spectrum.api.OccupancyItem;
import com.example.starter.spectrum.domain.LeaseMode;
import com.example.starter.spectrum.domain.LeaseStatus;
import com.example.starter.spectrum.repo.LeaseRepository;
import com.example.starter.spectrum.service.IdempotencyConflictException;
import com.example.starter.spectrum.service.InvalidLeaseRequestException;
import com.example.starter.spectrum.service.LeaseConflictException;
import com.example.starter.spectrum.service.LeaseNotFoundException;
import com.example.starter.spectrum.service.LeaseService;
import com.example.starter.spectrum.service.LeaseServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 租约协调主流程集成测试（H2 真实库）：创建/查询/快照、独占冲突、
 * 多区域事务回滚、终态取消、请求指纹幂等、版本冲突、占用查询与冲突解释。
 */
class LeaseServiceIntegrationTest extends AbstractSpectrumIntegrationTest {

    @Autowired
    private LeaseService leaseService;

    @Autowired
    private LeaseRepository repository;

    private static LeaseUpsertRequest upsert(String requestId, Integer expectedVersion,
                                             long start, long end, double low, double high,
                                             LeaseMode mode, double eirp, String... cells) {
        return new LeaseUpsertRequest(requestId, expectedVersion, start, end, low, high,
                mode, eirp, new TreeSet<>(List.of(cells)));
    }

    @Test
    void createGetAndActiveSnapshot() {
        LeaseResponse created = leaseService.create(
                upsert("req-create-1", null, T0, T0 + 1000, 100.0, 200.0,
                        LeaseMode.SHARED, 500, "CELL-A"));
        assertEquals(1, created.version());
        assertEquals(LeaseStatus.ACTIVE, created.status());
        assertFalse(created.idempotentReplay());
        assertNull(created.cancelTime());

        LeaseResponse fetched = leaseService.getLease(created.leaseId());
        assertEquals(created.leaseId(), fetched.leaseId());
        assertEquals(new TreeSet<>(List.of("CELL-A")), fetched.cells());

        // 区间内时刻可见；端点（半开）不可见
        assertEquals(1, leaseService.activeSnapshot(T0 + 500).size());
        assertEquals(1, leaseService.activeSnapshot(T0).size());
        assertTrue(leaseService.activeSnapshot(T0 + 1000).isEmpty());
        assertTrue(leaseService.activeSnapshot(T0 - 1).isEmpty());
    }

    @Test
    void exclusiveConflictRejectedWithReport() {
        leaseService.create(upsert("req-ex-1", null, T0, T0 + 1000, 100.0, 200.0,
                LeaseMode.EXCLUSIVE, 10, "CELL-A"));

        LeaseConflictException ex = assertThrows(LeaseConflictException.class,
                () -> leaseService.create(upsert("req-ex-2", null, T0 + 500, T0 + 1500,
                        150.0, 250.0, LeaseMode.SHARED, 10, "CELL-A")));
        assertFalse(ex.getReport().compatible());
        assertEquals("EXCLUSIVE_CONFLICT", ex.getReport().conflicts().get(0).type());

        // 冲突请求未产生任何租约
        assertEquals(1, leaseService.activeSnapshot(T0 + 500).size());
    }

    @Test
    void multiCellCreateRollsBackAtomically() {
        leaseService.create(upsert("req-mc-1", null, T0, T0 + 1000, 100.0, 200.0,
                LeaseMode.EXCLUSIVE, 10, "CELL-B"));

        // 候选覆盖 CELL-A（无冲突）与 CELL-B（独占冲突）：整个请求必须失败
        assertThrows(LeaseConflictException.class,
                () -> leaseService.create(upsert("req-mc-2", null, T0, T0 + 1000,
                        100.0, 200.0, LeaseMode.SHARED, 10, "CELL-A", "CELL-B")));

        // 回滚验证：无新租约、无版本、无区域、无事件
        Integer leases = jdbc.queryForObject("SELECT COUNT(*) FROM lease", Integer.class);
        Integer versions = jdbc.queryForObject("SELECT COUNT(*) FROM lease_version", Integer.class);
        Integer cells = jdbc.queryForObject("SELECT COUNT(*) FROM lease_cell", Integer.class);
        Integer events = jdbc.queryForObject("SELECT COUNT(*) FROM lease_event", Integer.class);
        assertEquals(1, leases);
        assertEquals(1, versions);
        assertEquals(1, cells);
        assertEquals(1, events);

        // 仅 CELL-A 的同样请求随后可成功（失败请求不占用空间）
        LeaseResponse ok = leaseService.create(upsert("req-mc-3", null, T0, T0 + 1000,
                100.0, 200.0, LeaseMode.SHARED, 10, "CELL-A"));
        assertNotNull(ok);
    }

    @Test
    void cancelIsTerminalAndIdempotentWithoutNewEvents() {
        LeaseResponse created = leaseService.create(
                upsert("req-cx-1", null, T0 - 1000, T0 + 1000, 100.0, 200.0,
                        LeaseMode.SHARED, 500, "CELL-A"));

        advanceClockTo(T0 + 100);
        LeaseResponse cancelled = leaseService.cancel(created.leaseId(),
                new LeaseCancelRequest("req-cx-2", 1));
        assertEquals(LeaseStatus.CANCELLED, cancelled.status());
        assertEquals(T0 + 100, cancelled.cancelTime());

        // 取消只改变未来有效性：取消前时刻仍活动，取消后不再活动
        assertEquals(1, leaseService.activeSnapshot(T0).size());
        assertTrue(leaseService.activeSnapshot(T0 + 100).isEmpty());
        assertEquals(2, repository.countEvents(created.leaseId())); // CREATED + CANCELLED

        // 同参重复取消：幂等回放，不新增事件
        LeaseResponse again = leaseService.cancel(created.leaseId(),
                new LeaseCancelRequest("req-cx-2", 1));
        assertTrue(again.idempotentReplay());
        assertEquals(LeaseStatus.CANCELLED, again.status());
        assertEquals(2, repository.countEvents(created.leaseId()));

        // 新幂等键对已取消租约再次取消：无副作用成功，不新增事件
        LeaseResponse third = leaseService.cancel(created.leaseId(),
                new LeaseCancelRequest("req-cx-3", 1));
        assertEquals(LeaseStatus.CANCELLED, third.status());
        assertEquals(2, repository.countEvents(created.leaseId()));

        // 已取消租约不可修改
        LeaseServiceException ex = assertThrows(LeaseServiceException.class,
                () -> leaseService.modify(created.leaseId(),
                        upsert("req-cx-4", 1, T0, T0 + 500, 100.0, 200.0,
                                LeaseMode.SHARED, 100, "CELL-A")));
        assertEquals("LEASE_TERMINAL", ex.getCode());
    }

    @Test
    void idempotencySameKeySameParamsReplaysFirstResult() {
        LeaseResponse first = leaseService.create(
                upsert("req-idem-1", null, T0, T0 + 1000, 100.0, 200.0,
                        LeaseMode.SHARED, 500, "CELL-A"));
        LeaseResponse replay = leaseService.create(
                upsert("req-idem-1", null, T0, T0 + 1000, 100.0, 200.0,
                        LeaseMode.SHARED, 500, "CELL-A"));
        assertTrue(replay.idempotentReplay());
        assertEquals(first.leaseId(), replay.leaseId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM lease", Integer.class));
    }

    @Test
    void idempotencySameKeyDifferentParamsConflicts() {
        leaseService.create(upsert("req-idem-2", null, T0, T0 + 1000, 100.0, 200.0,
                LeaseMode.SHARED, 500, "CELL-A"));
        // 同键异参（功率不同）→ 409
        IdempotencyConflictException ex = assertThrows(IdempotencyConflictException.class,
                () -> leaseService.create(upsert("req-idem-2", null, T0, T0 + 1000,
                        100.0, 200.0, LeaseMode.SHARED, 600, "CELL-A")));
        assertEquals("IDEMPOTENCY_CONFLICT", ex.getCode());
    }

    @Test
    void idempotencyReplaysFirstFailure() {
        LeaseResponse exclusive = leaseService.create(
                upsert("req-idem-3a", null, T0, T0 + 1000, 100.0, 200.0,
                        LeaseMode.EXCLUSIVE, 10, "CELL-A"));
        // 首次失败（独占冲突）
        assertThrows(LeaseConflictException.class,
                () -> leaseService.create(upsert("req-idem-3b", null, T0, T0 + 1000,
                        100.0, 200.0, LeaseMode.SHARED, 10, "CELL-A")));
        // 取消独占租约后，同键同参重放仍返回首次失败结果
        leaseService.cancel(exclusive.leaseId(), new LeaseCancelRequest("req-idem-3c", 1));
        LeaseConflictException ex = assertThrows(LeaseConflictException.class,
                () -> leaseService.create(upsert("req-idem-3b", null, T0, T0 + 1000,
                        100.0, 200.0, LeaseMode.SHARED, 10, "CELL-A")));
        assertFalse(ex.getReport().compatible());
    }

    @Test
    void modifyCreatesNewVersionAndChecksOptimisticVersion() {
        LeaseResponse created = leaseService.create(
                upsert("req-mv-1", null, T0, T0 + 1000, 100.0, 200.0,
                        LeaseMode.SHARED, 500, "CELL-A"));

        // 版本不匹配 → 409 VERSION_CONFLICT
        LeaseServiceException versionEx = assertThrows(LeaseServiceException.class,
                () -> leaseService.modify(created.leaseId(),
                        upsert("req-mv-2", 7, T0, T0 + 2000, 100.0, 200.0,
                                LeaseMode.SHARED, 500, "CELL-A")));
        assertEquals("VERSION_CONFLICT", versionEx.getCode());

        // 正确版本修改成功，版本递增，历史版本保留
        LeaseResponse modified = leaseService.modify(created.leaseId(),
                upsert("req-mv-3", 1, T0, T0 + 2000, 100.0, 300.0,
                        LeaseMode.SHARED, 600, "CELL-A", "CELL-C"));
        assertEquals(2, modified.version());
        assertEquals(new TreeSet<>(List.of("CELL-A", "CELL-C")), modified.cells());
        assertEquals(List.of(1, 2), repository.findVersionNumbers(created.leaseId()));

        // 修改后的规格生效
        assertEquals(1, leaseService.activeSnapshot(T0 + 1500).size());
    }

    @Test
    void modifyConflictLeavesOldVersionIntact() {
        leaseService.create(upsert("req-mc2-1", null, T0, T0 + 1000, 100.0, 200.0,
                LeaseMode.EXCLUSIVE, 10, "CELL-A"));
        LeaseResponse shared = leaseService.create(
                upsert("req-mc2-2", null, T0, T0 + 1000, 300.0, 400.0,
                        LeaseMode.SHARED, 500, "CELL-A"));

        // 修改共享租约到独占租约的频段 → 冲突，旧版本保持
        assertThrows(LeaseConflictException.class,
                () -> leaseService.modify(shared.leaseId(),
                        upsert("req-mc2-3", 1, T0, T0 + 1000, 150.0, 350.0,
                                LeaseMode.SHARED, 500, "CELL-A")));
        LeaseResponse current = leaseService.getLease(shared.leaseId());
        assertEquals(1, current.version());
        assertEquals(300.0, current.freqLowMhz());
    }

    @Test
    void occupancyQueryFiltersByCellFrequencyAndTime() {
        leaseService.create(upsert("req-occ-1", null, T0, T0 + 1000, 100.0, 200.0,
                LeaseMode.SHARED, 500, "CELL-A"));
        leaseService.create(upsert("req-occ-2", null, T0 + 2000, T0 + 3000, 500.0, 600.0,
                LeaseMode.SHARED, 500, "CELL-B"));

        List<OccupancyItem> all = leaseService.occupancy(null, null, null, null, null);
        assertEquals(2, all.size());

        List<OccupancyItem> byCell = leaseService.occupancy(
                List.of("CELL-A"), null, null, null, null);
        assertEquals(1, byCell.size());

        List<OccupancyItem> byFreq = leaseService.occupancy(
                null, 150.0, 250.0, null, null);
        assertEquals(1, byFreq.size());
        assertEquals(100.0, byFreq.get(0).freqLowMhz());

        // 时间窗端点相接不算相遇（半开）
        List<OccupancyItem> touching = leaseService.occupancy(
                null, null, null, T0 + 1000, T0 + 2000);
        assertTrue(touching.isEmpty());

        List<OccupancyItem> byTime = leaseService.occupancy(
                null, null, null, T0 + 500, T0 + 1500);
        assertEquals(1, byTime.size());
    }

    @Test
    void explainApisDoNotWrite() {
        LeaseResponse exclusive = leaseService.create(
                upsert("req-exp-1", null, T0, T0 + 1000, 100.0, 200.0,
                        LeaseMode.EXCLUSIVE, 10, "CELL-A"));

        ConflictResponse candidate = leaseService.explainCandidate(
                upsert("req-exp-2", null, T0, T0 + 1000, 150.0, 250.0,
                        LeaseMode.SHARED, 10, "CELL-A"));
        assertNull(candidate.leaseId());
        assertFalse(candidate.report().compatible());

        ConflictResponse existing = leaseService.explainLease(exclusive.leaseId());
        assertEquals(exclusive.leaseId(), existing.leaseId());
        assertTrue(existing.report().compatible()); // 与自身不冲突

        // 解释不写入任何数据
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM lease", Integer.class));
    }

    @Test
    void invalidRequestsRejected() {
        assertThrows(InvalidLeaseRequestException.class,
                () -> leaseService.create(upsert("req-bad-1", null, T0 + 1000, T0,
                        100.0, 200.0, LeaseMode.SHARED, 500, "CELL-A")));
        assertThrows(InvalidLeaseRequestException.class,
                () -> leaseService.create(upsert("req-bad-2", null, T0, T0 + 1000,
                        200.0, 100.0, LeaseMode.SHARED, 500, "CELL-A")));
        assertThrows(InvalidLeaseRequestException.class,
                () -> leaseService.create(upsert("req-bad-3", null, T0, T0 + 1000,
                        100.0, 200.0, LeaseMode.SHARED, -1, "CELL-A")));
        assertThrows(InvalidLeaseRequestException.class,
                () -> leaseService.create(upsert("req-bad-4", null, T0, T0 + 1000,
                        100.0, 200.0, LeaseMode.SHARED, 500)));
        assertThrows(LeaseNotFoundException.class, () -> leaseService.getLease(999L));
    }

    @Test
    void sharedFragmentSumExceedRejectedAtServiceLevel() {
        // 上限 1000mW：两条各 400mW 共享租约在不同区域/频段；
        // 第三条 400mW 与二者在 CELL-A 的 [T0+400,T0+600)x[140,160) 片段三者相遇，
        // 片段总和 1200 > 1000 → 拒绝；两两总量 800 不超，验证按片段聚合。
        leaseService.create(upsert("req-sh-1", null, T0, T0 + 1000, 100.0, 200.0,
                LeaseMode.SHARED, 400, "CELL-A"));
        leaseService.create(upsert("req-sh-2", null, T0 + 400, T0 + 600, 140.0, 160.0,
                LeaseMode.SHARED, 400, "CELL-A"));

        LeaseConflictException ex = assertThrows(LeaseConflictException.class,
                () -> leaseService.create(upsert("req-sh-3", null, T0 + 400, T0 + 600,
                        140.0, 160.0, LeaseMode.SHARED, 400, "CELL-A")));
        assertEquals("SHARED_EIRP_EXCEEDED", ex.getReport().conflicts().get(0).type());
        assertEquals(1200.0, ex.getReport().conflicts().get(0).totalEirpMw(), 1e-9);

        // 同样 400mW 但落在不相交的频率片段 → 允许
        LeaseResponse ok = leaseService.create(upsert("req-sh-4", null, T0 + 400, T0 + 600,
                160.0, 200.0, LeaseMode.SHARED, 400, "CELL-A"));
        assertNotNull(ok);
    }
}
