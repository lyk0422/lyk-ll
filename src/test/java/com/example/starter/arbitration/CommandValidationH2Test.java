package com.example.starter.arbitration;

import com.example.starter.arbitration.api.AcquireLeaseRequest;
import com.example.starter.arbitration.api.CommandRequest;
import com.example.starter.arbitration.api.CommandResponse;
import com.example.starter.arbitration.service.ApiException;
import com.example.starter.arbitration.service.CommandService;
import com.example.starter.arbitration.service.LeaseArbitrationService;
import com.example.starter.arbitration.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指令受理：合法入队、位置越界、超速、超限、时戳单调、时钟回拨、过期/未来指令、幂等与异参重放。
 */
class CommandValidationH2Test extends AbstractIntegrationTest {

    @Autowired
    private LeaseArbitrationService leaseService;
    @Autowired
    private CommandService commandService;

    @BeforeEach
    void setUpResourceAndLease() {
        jdbc.update(
                "INSERT INTO t_resource (resource_name, min_position, max_position, "
                        + "max_velocity, max_acceleration, safety_action, preset_position) "
                        + "VALUES ('joint_a', -3.14, 3.14, 2.0, 5.0, 'HOLD', NULL)");
        leaseService.acquire(
                new AcquireLeaseRequest("ctrl_a", "lease-1", 1, List.of("joint_a"), 10_000L));
    }

    private CommandRequest command(String requestId, long ts, long seq, double pos, double vel, double acc) {
        return new CommandRequest("ctrl_a", requestId, "joint_a", ts, seq, pos, vel, acc);
    }

    @Test
    void validCommandEnqueued() {
        CommandResponse resp = commandService.submit(command("c-1", clock.now() + 10, 1, 0.5, 1.0, 1.0));
        assertTrue(resp.accepted());
        assertEquals("PENDING", resp.status());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_command", Integer.class));
    }

    @Test
    void expiredCommandRejectedAndNotEnqueued() {
        long staleTs = clock.now() - 5_000;
        ApiException ex = assertThrows(ApiException.class,
                () -> commandService.submit(command("c-old", staleTs, 1, 0.5, 1.0, 1.0)));
        assertEquals(400, ex.getStatus().value());
        assertTrue(ex.getReasons().get(0).startsWith("TIMESTAMP_EXPIRED"), ex.getReasons().toString());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_command", Integer.class),
                "过期指令不得进入执行队列");
    }

    @Test
    void tooFarFutureCommandRejected() {
        long futureTs = clock.now() + 60_000;
        ApiException ex = assertThrows(ApiException.class,
                () -> commandService.submit(command("c-future", futureTs, 1, 0.5, 1.0, 1.0)));
        assertTrue(ex.getReasons().get(0).startsWith("FUTURE_TIMESTAMP"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_command", Integer.class));
    }

    @Test
    void positionOutOfBoundsRejected() {
        ApiException ex = assertThrows(ApiException.class,
                () -> commandService.submit(command("c-bnd", clock.now() + 10, 1, 4.0, 0.0, 0.0)));
        assertTrue(ex.getReasons().get(0).startsWith("POSITION_OUT_OF_BOUNDS"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_command", Integer.class));
    }

    @Test
    void velocityLimitExceededRejected() {
        ApiException ex = assertThrows(ApiException.class,
                () -> commandService.submit(command("c-vel", clock.now() + 10, 1, 0.5, 3.0, 0.0)));
        assertTrue(ex.getReasons().get(0).startsWith("VELOCITY_LIMIT_EXCEEDED"));
    }

    @Test
    void accelerationLimitExceededRejected() {
        ApiException ex = assertThrows(ApiException.class,
                () -> commandService.submit(command("c-acc", clock.now() + 10, 1, 0.5, 0.0, 9.0)));
        assertTrue(ex.getReasons().get(0).startsWith("ACCELERATION_LIMIT_EXCEEDED"));
    }

    @Test
    void clockRollbackIsDistinguishableFromExpiry() {
        commandService.submit(command("c-1", clock.now() + 100, 1, 0.1, 0.5, 0.5));
        // 时间仍在合法窗口内，但时戳比上一条小：必须归类为 CLOCK_ROLLBACK 而非过期
        ApiException ex = assertThrows(ApiException.class,
                () -> commandService.submit(command("c-2", clock.now() + 50, 2, 0.1, 0.5, 0.5)));
        assertTrue(ex.getReasons().stream().anyMatch(r -> r.startsWith("CLOCK_ROLLBACK")),
                "时钟回拨原因必须可区分: " + ex.getReasons());
        assertFalse(ex.getReasons().stream().anyMatch(r -> r.startsWith("TIMESTAMP_EXPIRED")));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_command", Integer.class),
                "回拨指令不得入队");
    }

    @Test
    void sequenceMustBeStrictlyMonotonic() {
        commandService.submit(command("c-1", clock.now() + 100, 10, 0.1, 0.0, 0.0));
        ApiException sameSeq = assertThrows(ApiException.class,
                () -> commandService.submit(command("c-2", clock.now() + 200, 10, 0.1, 0.0, 0.0)));
        assertTrue(sameSeq.getReasons().stream().anyMatch(r -> r.startsWith("SEQUENCE_NOT_MONOTONIC")));

        ApiException lowerSeq = assertThrows(ApiException.class,
                () -> commandService.submit(command("c-3", clock.now() + 300, 9, 0.1, 0.0, 0.0)));
        assertTrue(lowerSeq.getReasons().stream().anyMatch(r -> r.startsWith("SEQUENCE_NOT_MONOTONIC")));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_command", Integer.class));
    }

    @Test
    void impliedVelocityFromDisplacementIsChecked() {
        // 上一条位置 0.0，100ms 内移动到 1.0 -> 10 rad/s，超过限速 2.0
        commandService.submit(command("c-1", clock.now() + 10, 1, 0.0, 1.0, 0.0));
        ApiException ex = assertThrows(ApiException.class,
                () -> commandService.submit(command("c-2", clock.now() + 110, 2, 1.0, 1.0, 0.0)));
        assertTrue(ex.getReasons().stream().anyMatch(r -> r.startsWith("IMPLIED_VELOCITY_EXCEEDED")),
                ex.getReasons().toString());
    }

    @Test
    void sameRequestReplayIsIdempotentDifferentParamsReplayIs409() {
        CommandRequest first = command("c-1", clock.now() + 10, 1, 0.5, 1.0, 1.0);
        CommandResponse r1 = commandService.submit(first);
        CommandResponse replay = commandService.submit(first);
        assertTrue(replay.idempotentReplay());
        assertEquals(r1.commandId(), replay.commandId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_command", Integer.class));

        ApiException ex = assertThrows(ApiException.class,
                () -> commandService.submit(command("c-1", clock.now() + 20, 2, 0.6, 1.0, 1.0)));
        assertEquals("REQUEST_ID_PARAM_MISMATCH", ex.getCode());
        assertEquals(409, ex.getStatus().value());
    }

    @Test
    void nonHolderCannotSubmitCommands() {
        ApiException ex = assertThrows(ApiException.class,
                () -> commandService.submit(new CommandRequest("intruder", "x", "joint_a",
                        clock.now() + 10, 1, 0.5, 0.0, 0.0)));
        assertEquals("NOT_LEASE_HOLDER", ex.getCode());
        assertEquals(409, ex.getStatus().value());
    }

    @Test
    void concurrentSameRequestSubmitIsIdempotentForBothCallers() throws Exception {
        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            CommandRequest shared = command("dup-cmd", clock.now() + 10, 1, 0.5, 1.0, 1.0);
            java.util.concurrent.Future<CommandResponse> f1 = pool.submit(() -> {
                barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                return commandService.submit(shared);
            });
            java.util.concurrent.Future<CommandResponse> f2 = pool.submit(() -> {
                barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                return commandService.submit(shared);
            });
            CommandResponse r1 = f1.get(10, java.util.concurrent.TimeUnit.SECONDS);
            CommandResponse r2 = f2.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(r1.commandId(), r2.commandId());
            assertTrue(r1.accepted() && r2.accepted());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_command", Integer.class),
                "同参并发指令只能入队一次");
    }
}
