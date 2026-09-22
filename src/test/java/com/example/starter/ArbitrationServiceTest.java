package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.starter.arbitration.api.ApiException;
import com.example.starter.arbitration.dto.ClaimRequest;
import com.example.starter.arbitration.dto.ClaimResponse;
import com.example.starter.arbitration.dto.CommandRequest;
import com.example.starter.arbitration.dto.ReleaseRequest;
import com.example.starter.arbitration.dto.RenewRequest;
import com.example.starter.arbitration.dto.ResourceView;
import com.example.starter.arbitration.dto.SafeStateEventView;

/**
 * 仲裁核心流程测试：租约申请/抢占/续期/释放幂等、requestId 重放语义、
 * 指令校验（边界/限速/限加速度/过期/时钟回拨/序列号）、紧急停止与恢复、安全降级一次性。
 */
class ArbitrationServiceTest extends AbstractArbitrationTest {

    private static ApiException errorOf(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        return catchThrowableOfType(ApiException.class, call);
    }

    @Test
    void claimGrantsWhenIdleAndSameParamReplayIsIdempotent() {
        ClaimRequest req = new ClaimRequest("ctrl-a", "req-1", "joint1", 10, 1000L);
        ClaimResponse first = service.claim(req);
        assertThat(first.state()).isEqualTo("ACTIVE");
        assertThat(first.leaseExpiresAt()).isEqualTo(TestClockConfig.T0 + 1000L);
        assertThat(first.replayed()).isFalse();

        ClaimResponse replay = service.claim(req);
        assertThat(replay.claimId()).isEqualTo(first.claimId());
        assertThat(replay.replayed()).isTrue();

        ResourceView view = service.resourceView("joint1");
        assertThat(view.holderControllerId()).isEqualTo("ctrl-a");
        assertThat(view.holderPriority()).isEqualTo(10);
    }

    @Test
    void claimReplayWithDifferentParamsReturns409() {
        service.claim(new ClaimRequest("ctrl-a", "req-1", "joint1", 10, 1000L));

        ApiException ex = errorOf(() ->
                service.claim(new ClaimRequest("ctrl-a", "req-1", "joint1", 20, 1000L)));
        assertThat(ex.getCode()).isEqualTo("REPLAY_CONFLICT");
        assertThat(ex.getStatus().value()).isEqualTo(409);

        ApiException ex2 = errorOf(() ->
                service.claim(new ClaimRequest("ctrl-b", "req-1", "joint1", 10, 1000L)));
        assertThat(ex2.getCode()).isEqualTo("REPLAY_CONFLICT");
    }

    @Test
    void higherPriorityPreemptsAndEqualOrLowerIsRejected() {
        service.claim(new ClaimRequest("ctrl-a", "req-1", "joint1", 10, 60_000L));

        ApiException busy = errorOf(() ->
                service.claim(new ClaimRequest("ctrl-b", "req-2", "joint1", 10, 1000L)));
        assertThat(busy.getCode()).isEqualTo("RESOURCE_BUSY");

        ClaimResponse preempt = service.claim(new ClaimRequest("ctrl-c", "req-3", "joint1", 11, 1000L));
        assertThat(preempt.state()).isEqualTo("ACTIVE");

        String oldState = jdbc.queryForObject(
                "SELECT state FROM claim WHERE request_id = 'req-1'", String.class);
        assertThat(oldState).isEqualTo("PREEMPTED");
        assertThat(service.resourceView("joint1").holderControllerId()).isEqualTo("ctrl-c");
    }

    @Test
    void renewExtendsLeaseAndShorterRenewIsIdempotentNoOp() {
        ClaimResponse granted = service.claim(new ClaimRequest("ctrl-a", "req-1", "joint1", 10, 1000L));

        clock.advance(400);
        ClaimResponse renewed = service.renew(new RenewRequest("ctrl-a", "joint1", 2000L));
        assertThat(renewed.leaseExpiresAt()).isEqualTo(TestClockConfig.T0 + 400 + 2000);
        assertThat(renewed.replayed()).isFalse();

        // 更短的续期不缩短租约，幂等无操作
        ClaimResponse shorter = service.renew(new RenewRequest("ctrl-a", "joint1", 100L));
        assertThat(shorter.leaseExpiresAt()).isEqualTo(renewed.leaseExpiresAt());
        assertThat(shorter.replayed()).isTrue();

        ApiException notHolder = errorOf(() ->
                service.renew(new RenewRequest("ctrl-b", "joint1", 5000L)));
        assertThat(notHolder.getCode()).isEqualTo("NOT_HOLDER");
        assertThat(granted.claimId()).isEqualTo(renewed.claimId());
    }

    @Test
    void releaseIsIdempotentAndNonHolderReleaseRejected() {
        service.claim(new ClaimRequest("ctrl-a", "req-1", "joint1", 10, 60_000L));

        ApiException notHolder = errorOf(() ->
                service.release(new ReleaseRequest("ctrl-b", "joint1")));
        assertThat(notHolder.getCode()).isEqualTo("NOT_HOLDER");

        service.release(new ReleaseRequest("ctrl-a", "joint1"));
        // 重复释放幂等成功
        service.release(new ReleaseRequest("ctrl-a", "joint1"));
        assertThat(service.resourceView("joint1").holderControllerId()).isNull();

        String state = jdbc.queryForObject(
                "SELECT state FROM claim WHERE request_id = 'req-1'", String.class);
        assertThat(state).isEqualTo("RELEASED");
    }

    @Test
    void commandValidationRejectsOutOfBoundsVelocityAccelerationAndExpired() {
        service.claim(new ClaimRequest("ctrl-a", "req-1", "joint1", 10, 60_000L));
        long now = TestClockConfig.T0;

        ApiException pos = errorOf(() -> service.submit(
                new CommandRequest("ctrl-a", "joint1", 1L, now, 999.0, 0.0, 0.0)));
        assertThat(pos.getCode()).isEqualTo("POSITION_OUT_OF_BOUNDS");

        ApiException vel = errorOf(() -> service.submit(
                new CommandRequest("ctrl-a", "joint1", 1L, now, 0.0, 91.0, 0.0)));
        assertThat(vel.getCode()).isEqualTo("VELOCITY_LIMIT_EXCEEDED");

        ApiException acc = errorOf(() -> service.submit(
                new CommandRequest("ctrl-a", "joint1", 1L, now, 0.0, 0.0, 361.0)));
        assertThat(acc.getCode()).isEqualTo("ACCELERATION_LIMIT_EXCEEDED");

        ApiException expired = errorOf(() -> service.submit(
                new CommandRequest("ctrl-a", "joint1", 1L, now - 5001L, 0.0, 0.0, 0.0)));
        assertThat(expired.getCode()).isEqualTo("COMMAND_EXPIRED");

        // 非法指令均未入队
        Integer queued = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command WHERE status = 'QUEUED'", Integer.class);
        assertThat(queued).isZero();
    }

    @Test
    void commandTimestampMonotonicAndClockRollbackAreDistinguished() {
        service.claim(new ClaimRequest("ctrl-a", "req-1", "joint1", 10, 60_000L));
        long now = TestClockConfig.T0;

        service.submit(new CommandRequest("ctrl-a", "joint1", 1L, now, 10.0, 1.0, 1.0));
        service.submit(new CommandRequest("ctrl-a", "joint1", 2L, now + 100, 20.0, 1.0, 1.0));

        // 时钟回拨：时戳小于最近已接受时戳，返回可区分错误码
        ApiException rollback = errorOf(() -> service.submit(
                new CommandRequest("ctrl-a", "joint1", 3L, now + 50, 30.0, 1.0, 1.0)));
        assertThat(rollback.getCode()).isEqualTo("CLOCK_ROLLBACK");

        // 序列号不递增
        ApiException seq = errorOf(() -> service.submit(
                new CommandRequest("ctrl-a", "joint1", 2L, now + 200, 30.0, 1.0, 1.0)));
        assertThat(seq.getCode()).isEqualTo("SEQ_NOT_MONOTONIC");

        // 非持有者提交
        ApiException notHolder = errorOf(() -> service.submit(
                new CommandRequest("ctrl-b", "joint1", 1L, now + 300, 30.0, 1.0, 1.0)));
        assertThat(notHolder.getCode()).isEqualTo("NOT_HOLDER");

        Integer queued = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command WHERE status = 'QUEUED'", Integer.class);
        assertThat(queued).isEqualTo(2);
    }

    @Test
    void estopRevokesClaimsDropsCommandsAndRejectsUntilResume() {
        service.claim(new ClaimRequest("ctrl-a", "req-1", "joint1", 10, 60_000L));
        service.claim(new ClaimRequest("ctrl-b", "req-2", "joint2", 10, 60_000L));
        long now = TestClockConfig.T0;
        service.submit(new CommandRequest("ctrl-a", "joint1", 1L, now, 10.0, 1.0, 1.0));

        service.estop();

        assertThat(service.resourceView("joint1").holderControllerId()).isNull();
        assertThat(service.resourceView("joint2").holderControllerId()).isNull();
        Integer dropped = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command WHERE status = 'DROPPED'", Integer.class);
        assertThat(dropped).isEqualTo(1);
        String claimState = jdbc.queryForObject(
                "SELECT state FROM claim WHERE request_id = 'req-1'", String.class);
        assertThat(claimState).isEqualTo("ESTOPPED");

        ApiException claimDenied = errorOf(() ->
                service.claim(new ClaimRequest("ctrl-c", "req-3", "joint1", 100, 1000L)));
        assertThat(claimDenied.getCode()).isEqualTo("ESTOP_ACTIVE");
        assertThat(claimDenied.getStatus().value()).isEqualTo(423);

        ApiException submitDenied = errorOf(() -> service.submit(
                new CommandRequest("ctrl-a", "joint1", 2L, now + 1, 0.0, 0.0, 0.0)));
        assertThat(submitDenied.getCode()).isEqualTo("ESTOP_ACTIVE");

        service.resume();
        ClaimResponse after = service.claim(new ClaimRequest("ctrl-c", "req-4", "joint1", 5, 1000L));
        assertThat(after.state()).isEqualTo("ACTIVE");
    }

    @Test
    void leaseExpiryTriggersSafeStateExactlyOnceAndRearmAfterNewClaim() {
        service.claim(new ClaimRequest("ctrl-a", "req-1", "joint1", 10, 1000L));
        long now = TestClockConfig.T0;
        service.submit(new CommandRequest("ctrl-a", "joint1", 1L, now, 42.0, 1.0, 1.0));

        clock.advance(1001);
        List<SafeStateEventView> events = service.degradeExpired();
        assertThat(events).hasSize(1);
        SafeStateEventView event = events.get(0);
        assertThat(event.resourceId()).isEqualTo("joint1");
        assertThat(event.triggerReason()).isEqualTo("LEASE_EXPIRED");
        // joint1 配置为 PRESET_POSITION=0.0
        assertThat(event.appliedPosition()).isEqualTo(0.0);
        assertThat(event.appliedVelocity()).isEqualTo(0.0);

        assertThat(service.resourceView("joint1").holderControllerId()).isNull();
        assertThat(service.resourceView("joint1").safeApplied()).isTrue();
        String claimState = jdbc.queryForObject(
                "SELECT state FROM claim WHERE request_id = 'req-1'", String.class);
        assertThat(claimState).isEqualTo("EXPIRED");

        // 重复检测不得重复执行降级动作
        List<SafeStateEventView> again = service.degradeExpired();
        assertThat(again).isEmpty();
        assertThat(service.safeEvents()).hasSize(1);

        // 新持有周期重置降级标志，下一次到期可再次降级
        service.claim(new ClaimRequest("ctrl-b", "req-2", "joint1", 10, 500L));
        assertThat(service.resourceView("joint1").safeApplied()).isFalse();
        clock.advance(501);
        List<SafeStateEventView> second = service.degradeExpired();
        assertThat(second).hasSize(1);
        assertThat(service.safeEvents()).hasSize(2);
    }

    @Test
    void holdAndZeroVelocitySafeModesAppliedPerResourceConfig() {
        // joint3 为 HOLD：保持最近已接受位置
        service.claim(new ClaimRequest("ctrl-a", "req-1", "joint3", 10, 1000L));
        long now = TestClockConfig.T0;
        service.submit(new CommandRequest("ctrl-a", "joint3", 1L, now, 12.5, 1.0, 1.0));
        // joint2 为 ZERO_VELOCITY
        service.claim(new ClaimRequest("ctrl-b", "req-2", "joint2", 10, 1000L));

        clock.advance(1001);
        List<SafeStateEventView> events = service.degradeExpired();
        assertThat(events).hasSize(2);
        SafeStateEventView hold = events.stream()
                .filter(e -> e.resourceId().equals("joint3")).findFirst().orElseThrow();
        assertThat(hold.appliedPosition()).isEqualTo(12.5);
        assertThat(hold.appliedVelocity()).isNull();
        SafeStateEventView zeroVel = events.stream()
                .filter(e -> e.resourceId().equals("joint2")).findFirst().orElseThrow();
        assertThat(zeroVel.appliedVelocity()).isEqualTo(0.0);
        assertThat(zeroVel.appliedPosition()).isNull();
    }

    @Test
    void expiredLeaseRejectsSubmitAndCommandTimeoutDropsQueuedCommands() {
        service.claim(new ClaimRequest("ctrl-a", "req-1", "joint1", 10, 60_000L));
        long now = TestClockConfig.T0;
        service.submit(new CommandRequest("ctrl-a", "joint1", 1L, now, 10.0, 1.0, 1.0));

        // 指令超时（时戳早于 now - ttl）后，降级检测丢弃该待执行指令
        clock.advance(5001);
        service.degradeExpired();
        String status = jdbc.queryForObject(
                "SELECT status FROM command WHERE seq = 1", String.class);
        assertThat(status).isEqualTo("DROPPED");

        // 租约到期后提交指令被拒绝
        service.claim(new ClaimRequest("ctrl-b", "req-2", "joint2", 10, 1000L));
        clock.advance(1001);
        ApiException expired = errorOf(() -> service.submit(
                new CommandRequest("ctrl-b", "joint2", 1L, clock.millis(), 0.0, 0.0, 0.0)));
        assertThat(expired.getCode()).isEqualTo("LEASE_EXPIRED");
    }

    @Test
    void unknownResourceReturns404() {
        ApiException ex = errorOf(() ->
                service.claim(new ClaimRequest("ctrl-a", "req-1", "no-such-joint", 10, 1000L)));
        assertThat(ex.getCode()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(ex.getStatus().value()).isEqualTo(404);
    }
}
