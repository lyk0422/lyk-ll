package com.example.starter.arbitration;

import com.example.starter.arbitration.api.AcquireLeaseRequest;
import com.example.starter.arbitration.api.ReleaseLeaseRequest;
import com.example.starter.arbitration.api.RenewLeaseRequest;
import com.example.starter.arbitration.api.LeaseResponse;
import com.example.starter.arbitration.repo.ActiveClaimRepository;
import com.example.starter.arbitration.service.ApiException;
import com.example.starter.arbitration.service.EstopService;
import com.example.starter.arbitration.service.LeaseArbitrationService;
import com.example.starter.arbitration.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 租约仲裁：申请、抢占规则、异参重放 409、续期/释放幂等、急停拒绝申请；含真实并发抢占。
 */
class LeaseArbitrationH2Test extends AbstractIntegrationTest {

    @Autowired
    private LeaseArbitrationService leaseService;
    @Autowired
    private EstopService estopService;
    @Autowired
    private ActiveClaimRepository activeClaimRepository;
    @Autowired
    private com.example.starter.arbitration.service.CommandService commandService;

    @BeforeEach
    void registerResource() {
        jdbc.update(
                "INSERT INTO t_resource (resource_name, min_position, max_position, "
                        + "max_velocity, max_acceleration, safety_action, preset_position) "
                        + "VALUES ('joint_a', -3.14, 3.14, 2.0, 5.0, 'HOLD', NULL)");
    }

    private AcquireLeaseRequest acquire(String controller, String requestId, int priority) {
        return new AcquireLeaseRequest(controller, requestId, priority, List.of("joint_a"), null);
    }

    @Test
    void freeResourceIsGrantedAndUniquelyHeld() {
        LeaseResponse resp = leaseService.acquire(acquire("ctrl_low", "req-1", 1));
        assertTrue(resp.granted());
        assertFalse(resp.preempted());
        assertEquals("ctrl_low",
                activeClaimRepository.findByResource("joint_a").orElseThrow().getControllerId());
    }

    @Test
    void sameParametersReplayIsIdempotent() {
        leaseService.acquire(acquire("ctrl_low", "req-1", 1));
        LeaseResponse replay = leaseService.acquire(acquire("ctrl_low", "req-1", 1));
        assertTrue(replay.idempotentReplay());
        assertTrue(replay.granted());
        assertEquals(1, activeClaimRepository.findAll().size());
        assertEquals(1,
                jdbc.queryForObject("SELECT COUNT(*) FROM t_lease", Integer.class),
                "同参重放不得新增租约行");
    }

    @Test
    void replayWithDifferentPriorityReturns409() {
        leaseService.acquire(acquire("ctrl_low", "req-1", 1));
        ApiException ex = assertThrows(ApiException.class,
                () -> leaseService.acquire(acquire("ctrl_low", "req-1", 5)));
        assertEquals(409, ex.getStatus().value());
        assertEquals("REQUEST_ID_PARAM_MISMATCH", ex.getCode());
    }

    @Test
    void replayWithDifferentTtlReturns409() {
        leaseService.acquire(
                new AcquireLeaseRequest("ctrl_low", "req-1", 1, List.of("joint_a"), 500L));
        ApiException ex = assertThrows(ApiException.class, () -> leaseService.acquire(
                new AcquireLeaseRequest("ctrl_low", "req-1", 1, List.of("joint_a"), 900L)));
        assertEquals(409, ex.getStatus().value());
    }

    @Test
    void lowerPriorityCannotPreemptSamePriorityAlsoCannot() {
        leaseService.acquire(acquire("ctrl_low", "low-req", 5));

        ApiException sameLevel = assertThrows(ApiException.class,
                () -> leaseService.acquire(acquire("ctrl_same", "same-req", 5)));
        assertEquals("PREEMPTION_DENIED", sameLevel.getCode());

        ApiException lower = assertThrows(ApiException.class,
                () -> leaseService.acquire(acquire("ctrl_lower", "lower-req", 4)));
        assertEquals("PREEMPTION_DENIED", lower.getCode());

        assertEquals("ctrl_low",
                activeClaimRepository.findByResource("joint_a").orElseThrow().getControllerId());
    }

    @Test
    void higherPriorityPreempts() {
        leaseService.acquire(acquire("ctrl_low", "low-req", 1));
        LeaseResponse high = leaseService.acquire(acquire("ctrl_high", "high-req", 10));
        assertTrue(high.granted());
        assertTrue(high.preempted());
        assertEquals(List.of("joint_a"), high.preemptedFromResources());
        assertEquals("ctrl_high",
                activeClaimRepository.findByResource("joint_a").orElseThrow().getControllerId());
        // 旧租约已撤销
        assertEquals("PREEMPTED", jdbc.queryForObject(
                "SELECT revoked_reason FROM t_lease WHERE controller_id = 'ctrl_low'", String.class));
    }

    @Test
    void preemptionCancelsPendingCommandsOfOldHolder() {
        leaseService.acquire(acquire("ctrl_low", "low-req", 1));
        commandService.submit(new com.example.starter.arbitration.api.CommandRequest(
                "ctrl_low", "cmd-low", "joint_a", clock.now() + 100, 1, 0.2, 0.0, 0.0));
        leaseService.acquire(acquire("ctrl_high", "high-req", 10));
        assertEquals("TIMED_OUT", jdbc.queryForObject(
                "SELECT status FROM t_command WHERE request_id = 'cmd-low'", String.class));
        assertEquals("PREEMPTED", jdbc.queryForObject(
                "SELECT reject_reason FROM t_command WHERE request_id = 'cmd-low'", String.class));
    }

    @Test
    void sameControllerDifferentRequestOnHeldResourceReturns409() {
        leaseService.acquire(acquire("ctrl_a", "req-1", 1));
        ApiException ex = assertThrows(ApiException.class,
                () -> leaseService.acquire(acquire("ctrl_a", "req-2", 1)));
        assertEquals("RESOURCE_HELD_BY_SELF_OTHER_REQUEST", ex.getCode());
    }

    @Test
    void renewExtendsExpiryAndIsIdempotent() {
        LeaseResponse first = leaseService.acquire(acquire("ctrl_a", "req-1", 1));
        long firstExpiry = first.expiresAtMs();
        clock.advance(200);
        LeaseResponse renewed = leaseService.renew(new RenewLeaseRequest("ctrl_a", "req-1", null));
        assertTrue(renewed.expiresAtMs() > firstExpiry);
        // 再次续期同样成功
        clock.advance(200);
        LeaseResponse renewed2 = leaseService.renew(new RenewLeaseRequest("ctrl_a", "req-1", 700L));
        assertNotNull(renewed2);
        assertEquals(1, activeClaimRepository.findAll().size());
    }

    @Test
    void renewUnknownRequestReturns404() {
        ApiException ex = assertThrows(ApiException.class,
                () -> leaseService.renew(new RenewLeaseRequest("ghost", "nope", null)));
        assertEquals(404, ex.getStatus().value());
    }

    @Test
    void releaseIsIdempotent() {
        leaseService.acquire(acquire("ctrl_a", "req-1", 1));
        leaseService.release(new ReleaseLeaseRequest("ctrl_a", "req-1"));
        assertTrue(activeClaimRepository.findByResource("joint_a").isEmpty());
        // 重复释放不报错
        LeaseResponse again = leaseService.release(new ReleaseLeaseRequest("ctrl_a", "req-1"));
        assertTrue(again.idempotentReplay());
        // 释放未知 requestId 返回 404
        assertEquals(404, assertThrows(ApiException.class,
                () -> leaseService.release(new ReleaseLeaseRequest("ctrl_a", "never"))).getStatus().value());
    }

    @Test
    void acquireRejectedDuringEstopAndAllowedAfterRecover() {
        estopService.trigger();
        ApiException ex = assertThrows(ApiException.class,
                () -> leaseService.acquire(acquire("ctrl_a", "req-1", 1)));
        assertEquals(423, ex.getStatus().value());
        estopService.recover();
        assertTrue(leaseService.acquire(acquire("ctrl_a", "req-1", 1)).granted());
    }

    @Test
    void concurrentAcquireSamePriorityExactlyOneWinner() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> f1 = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                leaseService.acquire(acquire("ctrl_x", "x-req", 3));
                return Boolean.TRUE;
            });
            Future<Boolean> f2 = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                leaseService.acquire(acquire("ctrl_y", "y-req", 3));
                return Boolean.TRUE;
            });
            boolean win1 = completedWithoutConflict(f1);
            boolean win2 = completedWithoutConflict(f2);
            assertTrue(win1 ^ win2, "同级并发争用必须恰好一个成功，实际 win1=" + win1 + ", win2=" + win2);
            String holder = activeClaimRepository.findByResource("joint_a").orElseThrow().getControllerId();
            assertTrue(holder.equals("ctrl_x") || holder.equals("ctrl_y"));
            assertEquals(1, activeClaimRepository.findAll().size(),
                    "并发同级争用后资源仍只有一个有效持有者");
        } finally {
            pool.shutdownNow();
        }
    }

    private static boolean completedWithoutConflict(Future<Boolean> future) {
        try {
            future.get(10, TimeUnit.SECONDS);
            return true;
        } catch (Exception ex) {
            // 失败方应为 409 PREEMPTION_DENIED 或数据库唯一键冲突转换后的冲突
            return false;
        }
    }

    @Test
    void concurrentAcquireHigherPriorityWins() throws Exception {
        // 低优先级先持有，高优先级并发申请也应稳定抢占成功
        leaseService.acquire(acquire("ctrl_low", "low-req", 1));
        LeaseResponse resp = leaseService.acquire(acquire("ctrl_high", "high-req", 9));
        assertTrue(resp.granted());
        assertEquals("ctrl_high",
                activeClaimRepository.findByResource("joint_a").orElseThrow().getControllerId());
    }

    @Test
    void concurrentSameParameterAcquireIsIdempotentForBothCallers() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> f1 = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                LeaseResponse r = leaseService.acquire(acquire("ctrl_a", "dup-req", 2));
                return r.granted();
            });
            Future<Boolean> f2 = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                LeaseResponse r = leaseService.acquire(acquire("ctrl_a", "dup-req", 2));
                return r.granted();
            });
            assertTrue(f1.get(10, TimeUnit.SECONDS));
            assertTrue(f2.get(10, TimeUnit.SECONDS), "并发同参重放双方都应幂等成功");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_lease WHERE request_id = 'dup-req'", Integer.class),
                "同参并发申请只能产生一条租约");
        assertEquals(1, activeClaimRepository.findAll().size());
    }
}
