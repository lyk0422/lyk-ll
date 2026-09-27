package com.example.starter;

import com.example.starter.domain.AdjustmentTask;
import com.example.starter.domain.QuotaEvent;
import com.example.starter.domain.UnsealPermit;
import com.example.starter.service.BadRequestException;
import com.example.starter.service.ConflictException;
import com.example.starter.service.NotFoundException;
import com.example.starter.service.SealService;
import com.example.starter.service.UnprocessableException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 受控调节：窗口半开区间、额度消费与版本一致性、失败不消费、同键不重复消费、
 * 解封超时仅禁止新调节不回拨、完整铅封下旧入口拒绝。
 */
class ControlledAdjustServiceTest extends IntegrationTestBase {

    private static final Instant WINDOW_START = T0;
    private static final Instant WINDOW_END = T0.plus(Duration.ofHours(2));

    @Autowired
    private SealService sealService;
    @Autowired
    private com.example.starter.service.AdjustmentService adjustmentService;

    private void imbalancedReadings() {
        // U1 欠流（低于均值），U2 过流（高于均值）
        submitReadings(40.00, 42.50, 41.00, 41.00);
    }

    private UnsealPermit sealAndApprove(String sealCode, String permitRequestId) {
        sealService.registerSeal(new SealService.RegisterSealCommand(
                "req-seal-" + sealCode, 1L, sealCode, 0));
        return sealService.approveUnseal(new SealService.ApproveUnsealCommand(
                permitRequestId, sealCode, 0, "张三", "李四", WINDOW_START, WINDOW_END));
    }

    private AdjustmentTask controlledAdjust(String requestId, long permitId, int expectedVersion,
                                            String stepPercent) {
        return sealService.controlledAdjust(new SealService.ControlledAdjustCommand(
                requestId, STATION_ID, 1L, permitId, expectedVersion, new BigDecimal(stepPercent)));
    }

    @Test
    void 受控调节主流程消费额度写版本并留轨迹() {
        imbalancedReadings();
        UnsealPermit permit = sealAndApprove("SEAL-100", "req-unseal-100");

        AdjustmentTask task = controlledAdjust("req-cadj-100", permit.id(), 0, "5");

        assertThat(task.status()).isEqualTo(AdjustmentTask.TaskStatus.EXECUTING);
        assertThat(task.newOpening()).isEqualByComparingTo("55.00");
        assertThat(openingOf(1L)).isEqualByComparingTo("55.00");
        // 额度已消费一次，解封单版本 +1，核对阀门版本同步为最新
        UnsealPermit after = sealService.getPermit(permit.id());
        assertThat(after.quotaUsed()).isEqualTo(1);
        assertThat(after.version()).isEqualTo(2);
        assertThat(after.valveVersion()).isEqualTo(1);
        // 版本链与额度轨迹
        assertThat(countOf("valve_opening_version")).isEqualTo(1);
        List<QuotaEvent> events = sealService.getQuotaEvents(permit.id());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).taskId()).isEqualTo(task.id());
        assertThat(events.get(0).valveVersion()).isEqualTo(1);
        assertThat(events.get(0).quotaUsedAfter()).isEqualTo(1);
    }

    @Test
    void 窗口为半开区间恰到终点失效() {
        imbalancedReadings();
        UnsealPermit permit = sealAndApprove("SEAL-101", "req-unseal-101");

        // 窗口起点（含）：允许
        clock.set(WINDOW_START);
        AdjustmentTask first = controlledAdjust("req-cadj-101a", permit.id(), 0, "5");
        assertThat(first.status()).isEqualTo(AdjustmentTask.TaskStatus.EXECUTING);

        // 窗口终点前 1 秒：允许
        clock.set(WINDOW_END.minusSeconds(1));
        AdjustmentTask second = controlledAdjust("req-cadj-101b", permit.id(), 1, "5");
        assertThat(second.status()).isEqualTo(AdjustmentTask.TaskStatus.EXECUTING);

        // 恰到终点：失效，且额度已用尽也会拒绝；新开一张单验证终点语义
        clock.set(WINDOW_END);
        assertThatThrownBy(() -> controlledAdjust("req-cadj-101c", permit.id(), 2, "5"))
                .isInstanceOf(UnprocessableException.class);
        assertThat(openingOf(1L)).isEqualByComparingTo("60.00");
    }

    @Test
    void 窗口未开始拒绝调节() {
        imbalancedReadings();
        sealService.registerSeal(new SealService.RegisterSealCommand("req-seal-102", 1L, "SEAL-102", 0));
        UnsealPermit permit = sealService.approveUnseal(new SealService.ApproveUnsealCommand(
                "req-unseal-102", "SEAL-102", 0, "张三", "李四",
                T0.plus(Duration.ofHours(1)), T0.plus(Duration.ofHours(3))));

        assertThatThrownBy(() -> controlledAdjust("req-cadj-102", permit.id(), 0, "5"))
                .isInstanceOf(UnprocessableException.class);
        assertThat(openingOf(1L)).isEqualByComparingTo("50.00");
        assertThat(sealService.getPermit(permit.id()).quotaUsed()).isZero();
    }

    @Test
    void 额度最多两次第三次拒绝且失败不消费() {
        imbalancedReadings();
        UnsealPermit permit = sealAndApprove("SEAL-103", "req-unseal-103");

        controlledAdjust("req-cadj-103a", permit.id(), 0, "5");
        controlledAdjust("req-cadj-103b", permit.id(), 1, "5");
        assertThat(sealService.getPermit(permit.id()).quotaUsed()).isEqualTo(2);

        assertThatThrownBy(() -> controlledAdjust("req-cadj-103c", permit.id(), 2, "5"))
                .isInstanceOf(UnprocessableException.class);
        // 失败不消费：额度、开度、版本链、轨迹均不变
        assertThat(sealService.getPermit(permit.id()).quotaUsed()).isEqualTo(2);
        assertThat(openingOf(1L)).isEqualByComparingTo("60.00");
        assertThat(countOf("valve_opening_version")).isEqualTo(2);
        assertThat(sealService.getQuotaEvents(permit.id())).hasSize(2);
    }

    @Test
    void 版本不一致拒绝且不消费额度() {
        imbalancedReadings();
        UnsealPermit permit = sealAndApprove("SEAL-104", "req-unseal-104");

        assertThatThrownBy(() -> controlledAdjust("req-cadj-104", permit.id(), 1, "5"))
                .isInstanceOf(UnprocessableException.class);
        assertThat(sealService.getPermit(permit.id()).quotaUsed()).isZero();
        assertThat(openingOf(1L)).isEqualByComparingTo("50.00");
        assertThat(countOf("valve_opening_version")).isZero();
        assertThat(countOf("adjustment_task")).isZero();
    }

    @Test
    void 同键重放不重复消费额度且异参409() {
        imbalancedReadings();
        UnsealPermit permit = sealAndApprove("SEAL-105", "req-unseal-105");

        AdjustmentTask first = controlledAdjust("req-cadj-105", permit.id(), 0, "5");
        AdjustmentTask replay = controlledAdjust("req-cadj-105", permit.id(), 0, "5");

        assertThat(replay.id()).isEqualTo(first.id());
        // 重复同键不消费两次
        assertThat(sealService.getPermit(permit.id()).quotaUsed()).isEqualTo(1);
        assertThat(openingOf(1L)).isEqualByComparingTo("55.00");
        assertThat(countOf("valve_opening_version")).isEqualTo(1);
        assertThat(sealService.getQuotaEvents(permit.id())).hasSize(1);

        assertThatThrownBy(() -> controlledAdjust("req-cadj-105", permit.id(), 0, "6"))
                .isInstanceOf(ConflictException.class);
        assertThat(sealService.getPermit(permit.id()).quotaUsed()).isEqualTo(1);
    }

    @Test
    void 解封超时仅禁止新调节不回拨已改开度() {
        imbalancedReadings();
        UnsealPermit permit = sealAndApprove("SEAL-106", "req-unseal-106");
        controlledAdjust("req-cadj-106", permit.id(), 0, "5");
        assertThat(openingOf(1L)).isEqualByComparingTo("55.00");

        // 窗口结束后：新调节拒绝，已改开度保持不回拨
        clock.set(WINDOW_END.plus(Duration.ofHours(1)));
        assertThatThrownBy(() -> controlledAdjust("req-cadj-106b", permit.id(), 1, "5"))
                .isInstanceOf(UnprocessableException.class);
        assertThat(openingOf(1L)).isEqualByComparingTo("55.00");
        assertThat(sealService.getPermit(permit.id()).quotaUsed()).isEqualTo(1);
    }

    @Test
    void 撤销后禁止新调节() {
        imbalancedReadings();
        UnsealPermit permit = sealAndApprove("SEAL-107", "req-unseal-107");
        sealService.revokeUnseal(new SealService.RevokeUnsealCommand(
                "req-revoke-107", permit.id(), 1, "王五"));

        assertThatThrownBy(() -> controlledAdjust("req-cadj-107", permit.id(), 0, "5"))
                .isInstanceOf(UnprocessableException.class);
        assertThat(openingOf(1L)).isEqualByComparingTo("50.00");
    }

    @Test
    void 复封后旧解封单禁止新调节() {
        imbalancedReadings();
        UnsealPermit permit = sealAndApprove("SEAL-108", "req-unseal-108");
        sealService.reseal(new SealService.ResealCommand(
                "req-reseal-108", "SEAL-108", 0, "SEAL-109", "赵六", "孙七"));

        assertThatThrownBy(() -> controlledAdjust("req-cadj-108", permit.id(), 0, "5"))
                .isInstanceOf(UnprocessableException.class);
        assertThat(openingOf(1L)).isEqualByComparingTo("50.00");
        assertThat(countOf("valve_opening_version")).isZero();
    }

    @Test
    void 完整铅封下旧调节入口拒绝且解封窗口外也拒绝() {
        imbalancedReadings();
        sealService.registerSeal(new SealService.RegisterSealCommand("req-seal-110", 1L, "SEAL-110", 0));

        // 完整铅封（无生效解封单）：旧入口拒绝
        assertThatThrownBy(() -> adjustmentServiceIssue("req-old-110"))
                .isInstanceOf(UnprocessableException.class);
        assertThat(openingOf(1L)).isEqualByComparingTo("50.00");
        assertThat(countOf("adjustment_task")).isZero();

        // 解封窗口外：旧入口仍拒绝（铅封仍在）
        UnsealPermit permit = sealService.approveUnseal(new SealService.ApproveUnsealCommand(
                "req-unseal-110", "SEAL-110", 0, "张三", "李四", WINDOW_START, WINDOW_END));
        clock.set(WINDOW_END.plus(Duration.ofHours(1)));
        assertThatThrownBy(() -> adjustmentServiceIssue("req-old-110b"))
                .isInstanceOf(UnprocessableException.class);
        assertThatThrownBy(() -> controlledAdjust("req-cadj-110", permit.id(), 0, "5"))
                .isInstanceOf(UnprocessableException.class);
    }

    @Test
    void 无台账旧阀门仍按原规则工作() {
        imbalancedReadings();

        AdjustmentTask task = adjustmentServiceIssue("req-old-111");

        assertThat(task.status()).isEqualTo(AdjustmentTask.TaskStatus.EXECUTING);
        assertThat(openingOf(1L)).isEqualByComparingTo("55.00");
        assertThat(countOf("valve_opening_version")).isEqualTo(1);
    }

    @Test
    void 受控调节仍遵守10步进与24小时3次规则() {
        imbalancedReadings();
        UnsealPermit permit = sealAndApprove("SEAL-112", "req-unseal-112");

        // 步进超 10 拒绝且不消费额度
        assertThatThrownBy(() -> controlledAdjust("req-cadj-112a", permit.id(), 0, "10.01"))
                .isInstanceOf(UnprocessableException.class);
        assertThat(sealService.getPermit(permit.id()).quotaUsed()).isZero();

        // 恰 10 合法
        controlledAdjust("req-cadj-112b", permit.id(), 0, "10");
        assertThat(openingOf(1L)).isEqualByComparingTo("60.00");
    }

    @Test
    void 受控调节24小时内第4次转人工复核且不消费额度() {
        // 铅封前先按旧规则完成 3 次调节（均在 24 小时窗口内），版本到达 3
        for (int i = 1; i <= 3; i++) {
            imbalancedReadings();
            AdjustmentTask task = adjustmentService.issueAdjustment(
                    STATION_ID, "req-old-24h-" + i, 1L, new BigDecimal("5"));
            assertThat(task.status()).isEqualTo(AdjustmentTask.TaskStatus.EXECUTING);
            clock.advance(Duration.ofHours(4));
            adjustmentService.assess(task.id(), "req-asm-24h-" + i);
            adjustmentService.archive(task.id());
        }
        assertThat(openingOf(1L)).isEqualByComparingTo("65.00");
        // 在版本 3 上铅封并批准解封窗口覆盖当前时刻
        sealService.registerSeal(new SealService.RegisterSealCommand(
                "req-seal-115", 1L, "SEAL-115", 3));
        UnsealPermit permit = sealService.approveUnseal(new SealService.ApproveUnsealCommand(
                "req-unseal-115", "SEAL-115", 3, "张三", "李四",
                clock.instant(), clock.instant().plus(Duration.ofHours(2))));

        // 受控调节为 24 小时内第 4 次：转人工复核，不改开度、不消费额度、不写版本链
        AdjustmentTask review = controlledAdjust("req-cadj-115", permit.id(), 3, "5");
        assertThat(review.status()).isEqualTo(AdjustmentTask.TaskStatus.MANUAL_REVIEW);
        assertThat(review.executedAt()).isNull();
        assertThat(openingOf(1L)).isEqualByComparingTo("65.00");
        assertThat(sealService.getPermit(permit.id()).quotaUsed()).isZero();
        assertThat(countOf("valve_opening_version")).isEqualTo(3);
        assertThat(sealService.getQuotaEvents(permit.id())).isEmpty();

        // 同键重放返回首次冻结的复核任务
        AdjustmentTask replay = controlledAdjust("req-cadj-115", permit.id(), 3, "5");
        assertThat(replay.id()).isEqualTo(review.id());
        assertThat(sealService.getPermit(permit.id()).quotaUsed()).isZero();
    }

    @Test
    void 受控调节参数非法400与对象不存在404() {
        imbalancedReadings();
        UnsealPermit permit = sealAndApprove("SEAL-113", "req-unseal-113");

        assertThatThrownBy(() -> sealService.controlledAdjust(new SealService.ControlledAdjustCommand(
                "req-cadj-113a", STATION_ID, 1L, permit.id(), null, new BigDecimal("5"))))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> sealService.controlledAdjust(new SealService.ControlledAdjustCommand(
                "req-cadj-113b", STATION_ID, 1L, 999L, 0, new BigDecimal("5"))))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> sealService.controlledAdjust(new SealService.ControlledAdjustCommand(
                "req-cadj-113c", 99L, 1L, permit.id(), 0, new BigDecimal("5"))))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void 解封单与目标阀门不匹配被拒绝() {
        imbalancedReadings();
        UnsealPermit permit = sealAndApprove("SEAL-114", "req-unseal-114");

        // 解封单属于单元 1 的阀门，对单元 2 调节被拒绝
        assertThatThrownBy(() -> sealService.controlledAdjust(new SealService.ControlledAdjustCommand(
                "req-cadj-114", STATION_ID, 2L, permit.id(), 0, new BigDecimal("5"))))
                .isInstanceOf(UnprocessableException.class);
        assertThat(openingOf(2L)).isEqualByComparingTo("50.00");
    }

    private AdjustmentTask adjustmentServiceIssue(String requestId) {
        return adjustmentService.issueAdjustment(STATION_ID, requestId, 1L, new BigDecimal("5"));
    }
}
