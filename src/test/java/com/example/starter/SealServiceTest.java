package com.example.starter;

import com.example.starter.domain.AdjustmentTask;
import com.example.starter.domain.UnsealOrder;
import com.example.starter.domain.ValveSeal;
import com.example.starter.service.AdjustmentService;
import com.example.starter.service.ConflictException;
import com.example.starter.service.NotFoundException;
import com.example.starter.service.SealService;
import com.example.starter.service.UnprocessableException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 铅封管理：登记、临时解封、受控调节、撤销、复封的主流程与失败分支。
 */
class SealServiceTest extends IntegrationTestBase {

    @Autowired
    private SealService sealService;
    @Autowired
    private AdjustmentService adjustmentService;

    private void imbalancedReadings() {
        // U1 欠流（低于均值），U2 过流（高于均值）
        submitReadings(40.00, 42.50, 41.00, 41.00);
    }

    private ValveSeal registerSeal(long valveId, String requestId, String sealNo) {
        return sealService.registerSeal(valveId, requestId, sealNo, 0);
    }

    private UnsealOrder approve(long sealId, String requestId, Instant start, Instant end) {
        return sealService.approveUnseal(sealId, requestId, 1, 0, "张三", "李四", start, end);
    }

    /**
     * 稳定满 4 小时后评估并归档，释放站内执行中占位。
     */
    private void settle(long taskId) {
        clock.advance(Duration.ofHours(4));
        adjustmentService.assess(taskId, "req-asm-" + taskId);
        adjustmentService.archive(taskId);
    }

    @Test
    void 完整铅封拒绝普通调节令且无台账阀门仍按原规则工作() {
        imbalancedReadings();
        registerSeal(1L, "req-seal-1", "SEAL-001");

        assertThatThrownBy(() -> adjustmentService.issueAdjustment(
                STATION_ID, "req-adj-sealed", 1L, new BigDecimal("5")))
                .isInstanceOf(UnprocessableException.class);
        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(countOf("adjustment_task")).isZero();

        // 无台账的旧阀门（单元 2，过流）仍按原规则调节：50 → 45
        AdjustmentTask task = adjustmentService.issueAdjustment(
                STATION_ID, "req-adj-free", 2L, new BigDecimal("5"));
        assertThat(task.status()).isEqualTo(AdjustmentTask.TaskStatus.EXECUTING);
        assertThat(openingOf(2L)).isEqualByComparingTo(new BigDecimal("45.00"));
    }

    @Test
    void 铅封登记幂等与冲突分支() {
        ValveSeal seal = registerSeal(1L, "req-seal-r", "SEAL-100");
        assertThat(seal.status()).isEqualTo(ValveSeal.SealStatus.ACTIVE);
        assertThat(seal.version()).isEqualTo(1);
        assertThat(seal.valveVersion()).isEqualTo(0);
        assertThat(seal.sealedAt()).isEqualTo(T0);

        // 同键同参重放返回首次冻结结果
        ValveSeal replay = registerSeal(1L, "req-seal-r", "SEAL-100");
        assertThat(replay.id()).isEqualTo(seal.id());
        assertThat(countOf("valve_seal")).isEqualTo(1);

        // 同键异参 409
        assertThatThrownBy(() -> registerSeal(1L, "req-seal-r", "SEAL-101"))
                .isInstanceOf(ConflictException.class);
        // 已存在完整铅封，重复登记 422
        assertThatThrownBy(() -> registerSeal(1L, "req-seal-r2", "SEAL-102"))
                .isInstanceOf(UnprocessableException.class);
        // 铅封号全局唯一：其他阀门登记同号 409
        assertThatThrownBy(() -> registerSeal(2L, "req-seal-r3", "SEAL-100"))
                .isInstanceOf(ConflictException.class);
        // 登记的开度版本与当前版本不一致 422
        assertThatThrownBy(() -> sealService.registerSeal(2L, "req-seal-r4", "SEAL-104", 5))
                .isInstanceOf(UnprocessableException.class);
        // 阀门不存在 404
        assertThatThrownBy(() -> registerSeal(999L, "req-seal-r5", "SEAL-105"))
                .isInstanceOf(NotFoundException.class);
        // 失败不占键：版本修正后同键成功
        ValveSeal retried = sealService.registerSeal(2L, "req-seal-r4", "SEAL-104", 0);
        assertThat(retried.sealNo()).isEqualTo("SEAL-104");
    }

    @Test
    void 解封批准校验且生效不改变开度() {
        ValveSeal seal = registerSeal(1L, "req-seal-u", "SEAL-200");
        Instant start = T0;
        Instant end = T0.plus(Duration.ofHours(2));

        // 两名授权人相同 422
        assertThatThrownBy(() -> sealService.approveUnseal(
                seal.id(), "req-u-same", 1, 0, "张三", "张三", start, end))
                .isInstanceOf(UnprocessableException.class);
        // 窗口非法（起点不早于终点）422
        assertThatThrownBy(() -> approve(seal.id(), "req-u-badwin", end, end))
                .isInstanceOf(UnprocessableException.class);
        // 铅封版本不一致 409
        assertThatThrownBy(() -> sealService.approveUnseal(
                seal.id(), "req-u-badver", 9, 0, "张三", "李四", start, end))
                .isInstanceOf(ConflictException.class);
        // 引用的阀门版本不一致 422
        assertThatThrownBy(() -> sealService.approveUnseal(
                seal.id(), "req-u-badvv", 1, 3, "张三", "李四", start, end))
                .isInstanceOf(UnprocessableException.class);
        // 铅封不存在 404
        assertThatThrownBy(() -> approve(999L, "req-u-404", start, end))
                .isInstanceOf(NotFoundException.class);

        UnsealOrder order = approve(seal.id(), "req-u-ok", start, end);
        assertThat(order.status()).isEqualTo(UnsealOrder.UnsealStatus.ACTIVE);
        assertThat(order.quotaTotal()).isEqualTo(2);
        assertThat(order.quotaUsed()).isEqualTo(0);
        assertThat(order.version()).isEqualTo(1);
        // 解封生效不改变开度
        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("50.00"));

        // 同键同参重放；同键异参 409
        UnsealOrder replay = approve(seal.id(), "req-u-ok", start, end);
        assertThat(replay.id()).isEqualTo(order.id());
        assertThatThrownBy(() -> sealService.approveUnseal(
                seal.id(), "req-u-ok", 1, 0, "张三", "王五", start, end))
                .isInstanceOf(ConflictException.class);
        // 同一铅封已存在有效解封单 422
        assertThatThrownBy(() -> approve(seal.id(), "req-u-dup", start, end))
                .isInstanceOf(UnprocessableException.class);
        assertThat(countOf("unseal_order")).isEqualTo(1);
    }

    @Test
    void 指纹结构化无歧义含分隔符文本仍可区分() {
        ValveSeal seal = registerSeal(1L, "req-seal-fp", "SEAL-300");
        Instant start = T0;
        Instant end = T0.plus(Duration.ofHours(2));
        UnsealOrder order = sealService.approveUnseal(
                seal.id(), "req-u-fp", 1, 0, "A|B", "C", start, end);
        assertThat(order.approverA()).isEqualTo("A|B");

        // 朴素分隔符拼接会得到相同指纹（"A|B" + "C" 与 "A" + "B|C"），结构化指纹必须判异参 409
        assertThatThrownBy(() -> sealService.approveUnseal(
                seal.id(), "req-u-fp", 1, 0, "A", "B|C", start, end))
                .isInstanceOf(ConflictException.class);
        assertThat(countOf("unseal_order")).isEqualTo(1);
    }

    @Test
    void 受控调节窗口半开区间恰到终点失效() {
        imbalancedReadings();
        ValveSeal seal = registerSeal(1L, "req-seal-w", "SEAL-400");
        UnsealOrder order = approve(seal.id(), "req-u-w",
                T0.plus(Duration.ofHours(1)), T0.plus(Duration.ofHours(3)));

        // 窗口未开始：拒绝且不消费额度、不占键
        assertThatThrownBy(() -> adjustmentService.issueControlledAdjustment(
                order.id(), "req-ca-early", 1, new BigDecimal("5")))
                .isInstanceOf(UnprocessableException.class);
        assertThat(sealService.getUnseal(order.id()).quotaUsed()).isEqualTo(0);
        assertThat(countOf("adjustment_task")).isZero();

        // 窗口内：成功消费一次额度
        clock.advance(Duration.ofHours(1));
        AdjustmentTask task = adjustmentService.issueControlledAdjustment(
                order.id(), "req-ca-in", 1, new BigDecimal("5"));
        assertThat(task.status()).isEqualTo(AdjustmentTask.TaskStatus.EXECUTING);
        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("55.00"));
        assertThat(sealService.getUnseal(order.id()).quotaUsed()).isEqualTo(1);

        // 恰到终点失效（半开区间）：另一阀门新解封单验证
        ValveSeal seal2 = registerSeal(2L, "req-seal-w2", "SEAL-401");
        UnsealOrder order2 = approve(seal2.id(), "req-u-w2",
                T0.plus(Duration.ofHours(1)), T0.plus(Duration.ofHours(2)));
        clock.set(T0.plus(Duration.ofHours(2)));
        assertThatThrownBy(() -> adjustmentService.issueControlledAdjustment(
                order2.id(), "req-ca-edge", 1, new BigDecimal("5")))
                .isInstanceOf(UnprocessableException.class);
        assertThat(sealService.getUnseal(order2.id()).quotaUsed()).isEqualTo(0);
    }

    @Test
    void 受控调节额度最多两次且版本链完整() {
        imbalancedReadings();
        ValveSeal seal = registerSeal(1L, "req-seal-q", "SEAL-500");
        UnsealOrder order = approve(seal.id(), "req-u-q", T0, T0.plus(Duration.ofHours(72)));

        AdjustmentTask first = adjustmentService.issueControlledAdjustment(
                order.id(), "req-ca-q1", 1, new BigDecimal("5"));
        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("55.00"));
        UnsealOrder afterFirst = sealService.getUnseal(order.id());
        assertThat(afterFirst.quotaUsed()).isEqualTo(1);
        assertThat(afterFirst.version()).isEqualTo(2);
        settle(first.id());

        AdjustmentTask second = adjustmentService.issueControlledAdjustment(
                order.id(), "req-ca-q2", 2, new BigDecimal("5"));
        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("60.00"));
        UnsealOrder afterSecond = sealService.getUnseal(order.id());
        assertThat(afterSecond.quotaUsed()).isEqualTo(2);
        assertThat(afterSecond.version()).isEqualTo(3);
        settle(second.id());

        // 第三次：额度用尽 422，不消费、不改开度
        assertThatThrownBy(() -> adjustmentService.issueControlledAdjustment(
                order.id(), "req-ca-q3", 3, new BigDecimal("5")))
                .isInstanceOf(UnprocessableException.class);
        assertThat(sealService.getUnseal(order.id()).quotaUsed()).isEqualTo(2);
        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("60.00"));

        // 额度轨迹与版本链可复算：两次消费、两个开度版本
        var trail = sealService.getQuotaTrail(order.id());
        assertThat(trail).hasSize(2);
        assertThat(trail.get(0).requestId()).isEqualTo("req-ca-q1");
        assertThat(trail.get(1).requestId()).isEqualTo("req-ca-q2");
        var versions = adjustmentService.getValveVersions(1L);
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).versionNo()).isEqualTo(1);
        assertThat(versions.get(1).versionNo()).isEqualTo(2);
    }

    @Test
    void 受控调节同键重放不重复消费额度且异参409() {
        imbalancedReadings();
        ValveSeal seal = registerSeal(1L, "req-seal-i", "SEAL-600");
        UnsealOrder order = approve(seal.id(), "req-u-i", T0, T0.plus(Duration.ofHours(2)));

        AdjustmentTask first = adjustmentService.issueControlledAdjustment(
                order.id(), "req-ca-i", 1, new BigDecimal("5"));
        AdjustmentTask replay = adjustmentService.issueControlledAdjustment(
                order.id(), "req-ca-i", 1, new BigDecimal("5"));

        assertThat(replay.id()).isEqualTo(first.id());
        // 重复同键不消费两次
        assertThat(sealService.getUnseal(order.id()).quotaUsed()).isEqualTo(1);
        assertThat(sealService.getQuotaTrail(order.id())).hasSize(1);
        assertThat(countOf("adjustment_task")).isEqualTo(1);
        assertThat(countOf("valve_opening_version")).isEqualTo(1);
        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("55.00"));

        // 同键异参 409
        assertThatThrownBy(() -> adjustmentService.issueControlledAdjustment(
                order.id(), "req-ca-i", 1, new BigDecimal("6")))
                .isInstanceOf(ConflictException.class);
        assertThat(sealService.getUnseal(order.id()).quotaUsed()).isEqualTo(1);
    }

    @Test
    void 受控调节失败不消费额度且失败不占键() {
        imbalancedReadings();
        ValveSeal seal = registerSeal(1L, "req-seal-f", "SEAL-700");
        UnsealOrder order = approve(seal.id(), "req-u-f",
                T0.plus(Duration.ofHours(1)), T0.plus(Duration.ofHours(3)));

        // 窗口未开始失败：不消费额度、不产生任务、不占幂等键
        assertThatThrownBy(() -> adjustmentService.issueControlledAdjustment(
                order.id(), "req-ca-f", 1, new BigDecimal("5")))
                .isInstanceOf(UnprocessableException.class);
        assertThat(sealService.getUnseal(order.id()).quotaUsed()).isEqualTo(0);
        assertThat(countOf("adjustment_task")).isZero();

        // 进入窗口后同键同参成功
        clock.advance(Duration.ofHours(1));
        AdjustmentTask task = adjustmentService.issueControlledAdjustment(
                order.id(), "req-ca-f", 1, new BigDecimal("5"));
        assertThat(task.status()).isEqualTo(AdjustmentTask.TaskStatus.EXECUTING);
        assertThat(sealService.getUnseal(order.id()).quotaUsed()).isEqualTo(1);
    }

    @Test
    void 撤销后禁止新调节且不回拨已改开度() {
        imbalancedReadings();
        ValveSeal seal = registerSeal(1L, "req-seal-v", "SEAL-800");
        UnsealOrder order = approve(seal.id(), "req-u-v", T0, T0.plus(Duration.ofHours(48)));

        adjustmentService.issueControlledAdjustment(order.id(), "req-ca-v", 1, new BigDecimal("5"));
        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("55.00"));

        // 消费一次额度后版本为 2，撤销携带 expectedVersion=2
        UnsealOrder revoked = sealService.revokeUnseal(order.id(), "req-revoke-1", 2);
        assertThat(revoked.status()).isEqualTo(UnsealOrder.UnsealStatus.REVOKED);
        assertThat(revoked.version()).isEqualTo(3);
        assertThat(revoked.revokedAt()).isEqualTo(clock.instant());

        // 撤销仅禁止新调节，不回拨已改开度
        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("55.00"));
        assertThatThrownBy(() -> adjustmentService.issueControlledAdjustment(
                order.id(), "req-ca-v2", 3, new BigDecimal("5")))
                .isInstanceOf(UnprocessableException.class);

        // 撤销幂等：同键同参重放，同键异参 409，异键重复撤销 409
        UnsealOrder replay = sealService.revokeUnseal(order.id(), "req-revoke-1", 2);
        assertThat(revoked.id()).isEqualTo(replay.id());
        assertThatThrownBy(() -> sealService.revokeUnseal(order.id(), "req-revoke-1", 9))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> sealService.revokeUnseal(order.id(), "req-revoke-2", 3))
                .isInstanceOf(ConflictException.class);
        // 解封单不存在 404
        assertThatThrownBy(() -> sealService.revokeUnseal(999L, "req-revoke-404", 1))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void 复封原子终结旧解封权限并保留历史封号链() {
        imbalancedReadings();
        ValveSeal seal = registerSeal(1L, "req-seal-rs", "SEAL-900");
        UnsealOrder order = approve(seal.id(), "req-u-rs", T0, T0.plus(Duration.ofHours(48)));
        adjustmentService.issueControlledAdjustment(order.id(), "req-ca-rs", 1, new BigDecimal("5"));
        // 当前开度版本为 1
        assertThat(adjustmentService.getValveVersions(1L)).hasSize(1);

        // 两名核对人相同 422
        assertThatThrownBy(() -> sealService.reseal(
                1L, "req-rs-same", 1, "SEAL-901", "甲", "甲", 1))
                .isInstanceOf(UnprocessableException.class);
        // 核对的开度版本不一致 422
        assertThatThrownBy(() -> sealService.reseal(
                1L, "req-rs-badvv", 1, "SEAL-901", "甲", "乙", 0))
                .isInstanceOf(UnprocessableException.class);
        // 铅封版本不一致 409
        assertThatThrownBy(() -> sealService.reseal(
                1L, "req-rs-badver", 9, "SEAL-901", "甲", "乙", 1))
                .isInstanceOf(ConflictException.class);

        ValveSeal newSeal = sealService.reseal(1L, "req-rs-ok", 1, "SEAL-901", "甲", "乙", 1);
        assertThat(newSeal.status()).isEqualTo(ValveSeal.SealStatus.ACTIVE);
        assertThat(newSeal.version()).isEqualTo(1);
        assertThat(newSeal.valveVersion()).isEqualTo(1);

        // 旧解封权限被原子终结：已复封不得再追加调节版本
        UnsealOrder closed = sealService.getUnseal(order.id());
        assertThat(closed.status()).isEqualTo(UnsealOrder.UnsealStatus.CLOSED);
        assertThatThrownBy(() -> adjustmentService.issueControlledAdjustment(
                order.id(), "req-ca-rs2", 2, new BigDecimal("5")))
                .isInstanceOf(UnprocessableException.class);
        assertThat(adjustmentService.getValveVersions(1L)).hasSize(1);

        // 历史封号链保留：旧铅封 RESEALED，新铅封 ACTIVE
        var chain = sealService.getSealChain(1L);
        assertThat(chain).hasSize(2);
        assertThat(chain.get(0).sealNo()).isEqualTo("SEAL-900");
        assertThat(chain.get(0).status()).isEqualTo(ValveSeal.SealStatus.RESEALED);
        assertThat(chain.get(0).version()).isEqualTo(2);
        assertThat(chain.get(1).sealNo()).isEqualTo("SEAL-901");
        assertThat(chain.get(1).status()).isEqualTo(ValveSeal.SealStatus.ACTIVE);

        // 新完整铅封下普通调节令仍被拒绝
        assertThatThrownBy(() -> adjustmentService.issueAdjustment(
                STATION_ID, "req-adj-after-rs", 1L, new BigDecimal("5")))
                .isInstanceOf(UnprocessableException.class);

        // 复封幂等：同键同参重放，同键异参 409；新铅封号重复 409
        ValveSeal replay = sealService.reseal(1L, "req-rs-ok", 1, "SEAL-901", "甲", "乙", 1);
        assertThat(replay.id()).isEqualTo(newSeal.id());
        assertThatThrownBy(() -> sealService.reseal(1L, "req-rs-ok", 1, "SEAL-902", "甲", "乙", 1))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> sealService.reseal(1L, "req-rs-dup", 1, "SEAL-901", "丙", "丁", 1))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 解封超时仅禁止新调节不回拨已改开度() {
        imbalancedReadings();
        ValveSeal seal = registerSeal(1L, "req-seal-t", "SEAL-910");
        UnsealOrder order = approve(seal.id(), "req-u-t",
                T0, T0.plus(Duration.ofHours(6)));

        AdjustmentTask task = adjustmentService.issueControlledAdjustment(
                order.id(), "req-ca-t", 1, new BigDecimal("5"));
        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("55.00"));
        settle(task.id());

        // 超时后：已改开度不回拨，新调节被拒绝
        clock.set(T0.plus(Duration.ofHours(7)));
        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("55.00"));
        assertThatThrownBy(() -> adjustmentService.issueControlledAdjustment(
                order.id(), "req-ca-t2", 2, new BigDecimal("5")))
                .isInstanceOf(UnprocessableException.class);
        assertThat(sealService.getUnseal(order.id()).quotaUsed()).isEqualTo(1);
    }

    @Test
    void 受控调节仍遵守24小时最多3次规则且转人工复核不消费额度() {
        imbalancedReadings();
        // 直接登记 3 条 24 小时内已执行的历史任务（合成数据）
        for (int i = 1; i <= 3; i++) {
            jdbc.update("INSERT INTO adjustment_task"
                            + " (station_id, unit_id, request_id, fingerprint, classification,"
                            + " step_percent, old_opening, new_opening, status,"
                            + " executed_at, assessed_at, archived_at, created_at)"
                            + " VALUES (1, 1, ?, 'fp', 'UNDERFLOW', 5, 50, 55, 'ARCHIVED',"
                            + " ?, ?, ?, ?)",
                    "req-hist-" + i, java.sql.Timestamp.from(clock.instant()),
                    java.sql.Timestamp.from(clock.instant()),
                    java.sql.Timestamp.from(clock.instant()),
                    java.sql.Timestamp.from(clock.instant()));
        }
        ValveSeal seal = registerSeal(1L, "req-seal-24h", "SEAL-920");
        UnsealOrder order = approve(seal.id(), "req-u-24h", T0, T0.plus(Duration.ofHours(2)));

        AdjustmentTask task = adjustmentService.issueControlledAdjustment(
                order.id(), "req-ca-24h", 1, new BigDecimal("5"));

        assertThat(task.status()).isEqualTo(AdjustmentTask.TaskStatus.MANUAL_REVIEW);
        assertThat(task.executedAt()).isNull();
        // 转人工复核不消费解封额度、不改变开度
        assertThat(sealService.getUnseal(order.id()).quotaUsed()).isEqualTo(0);
        assertThat(sealService.getQuotaTrail(order.id())).isEmpty();
        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("50.00"));
    }

    @Test
    void 受控调节版本不一致409且解封单不存在404() {
        imbalancedReadings();
        ValveSeal seal = registerSeal(1L, "req-seal-ev", "SEAL-930");
        UnsealOrder order = approve(seal.id(), "req-u-ev", T0, T0.plus(Duration.ofHours(2)));

        assertThatThrownBy(() -> adjustmentService.issueControlledAdjustment(
                order.id(), "req-ca-ev", 9, new BigDecimal("5")))
                .isInstanceOf(ConflictException.class);
        assertThat(sealService.getUnseal(order.id()).quotaUsed()).isEqualTo(0);

        assertThatThrownBy(() -> adjustmentService.issueControlledAdjustment(
                999L, "req-ca-404", 1, new BigDecimal("5")))
                .isInstanceOf(NotFoundException.class);
    }
}
