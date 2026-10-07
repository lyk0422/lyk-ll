package com.example.starter;

import com.example.starter.domain.AdjustmentTask;
import com.example.starter.domain.CoupledGroup;
import com.example.starter.domain.UnsealOrder;
import com.example.starter.service.AdjustmentService;
import com.example.starter.service.ConflictException;
import com.example.starter.service.CoupledService;
import com.example.starter.service.NotFoundException;
import com.example.starter.service.SealService;
import com.example.starter.service.UnprocessableException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 耦合联调：建/改/解除联调组与全组目标开度原子提交的主流程、失败分支及幂等边界。
 */
class CoupledServiceTest extends IntegrationTestBase {

    @Autowired
    private CoupledService coupledService;
    @Autowired
    private SealService sealService;
    @Autowired
    private AdjustmentService adjustmentService;

    private void setOpening(long valveId, String opening) {
        jdbc.update("UPDATE valve SET opening_percent = ? WHERE id = ?",
                new BigDecimal(opening), valveId);
    }

    private CoupledService.GroupView createGroup(String requestId, long valve1, long valve2) {
        return coupledService.createGroup(STATION_ID, requestId, List.of(
                new CoupledService.MemberCommand(valve1, 1),
                new CoupledService.MemberCommand(valve2, 1)));
    }

    private CoupledService.TargetCommand target(long valveId, int opening, int version) {
        return new CoupledService.TargetCommand(valveId, opening, version, null);
    }

    private UnsealOrder sealAndUnseal(long valveId, String tag) {
        var seal = sealService.registerSeal(valveId, "req-seal-" + tag, "SEAL-" + tag, 0);
        return sealService.approveUnseal(seal.id(), "req-u-" + tag, 1, 0,
                "张三", "李四", T0, T0.plus(Duration.ofHours(2)));
    }

    private int quotaUsedOf(long unsealId) {
        Integer used = jdbc.queryForObject(
                "SELECT quota_used FROM unseal_order WHERE id = ?", Integer.class, unsealId);
        return used == null ? -1 : used;
    }

    @Test
    void 同权两阀40和60调到50和50一起成功() {
        setOpening(1L, "40.00");
        setOpening(2L, "60.00");
        var group = createGroup("req-cg-4060", 1L, 2L);
        assertThat(group.group().version()).isEqualTo(1);
        assertThat(group.members()).hasSize(2);
        assertThat(group.members()).allMatch(m -> m.weight() == 1 && m.valveVersion() == 0);

        var view = coupledService.submitAdjustment(group.group().id(), "req-ca-4060", T0,
                List.of(target(1L, 50, 0), target(2L, 50, 0)));

        // 一起成功：两阀开度、版本、任务原子生效
        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(openingOf(2L)).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(view.weightedBefore()).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(view.weightedAfter()).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(view.adjustment().version()).isEqualTo(1);
        assertThat(view.adjustment().executedAt()).isEqualTo(T0);
        assertThat(view.items()).hasSize(2);
        assertThat(view.items()).allMatch(i -> i.taskId() != null);
        assertThat(countOf("adjustment_task")).isEqualTo(2);
        assertThat(countOf("valve_opening_version")).isEqualTo(2);
        // 查询可复算净加权开度
        var queried = coupledService.getAdjustment(view.adjustment().id());
        assertThat(queried.weightedBefore()).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(queried.weightedAfter()).isEqualByComparingTo(new BigDecimal("100"));
    }

    @Test
    void 异权组加权守恒精确比较且恰10个百分点允许() {
        setOpening(1L, "40.00");
        setOpening(2L, "60.00");
        var group = coupledService.createGroup(STATION_ID, "req-cg-w", List.of(
                new CoupledService.MemberCommand(1L, 2),
                new CoupledService.MemberCommand(2L, 1)));

        // 加权调前 2*40+1*60=140，目标 2*45+1*50=140：恰等允许，阀2 恰变 10 个百分点
        var view = coupledService.submitAdjustment(group.group().id(), "req-ca-w", T0,
                List.of(target(1L, 45, 0), target(2L, 50, 0)));

        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("45.00"));
        assertThat(openingOf(2L)).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(view.weightedBefore()).isEqualByComparingTo(new BigDecimal("140"));
        assertThat(view.weightedAfter()).isEqualByComparingTo(new BigDecimal("140"));
    }

    @Test
    void 净额不守恒全组零变化且失败不占键() {
        setOpening(1L, "40.00");
        setOpening(2L, "60.00");
        var group = createGroup("req-cg-nc", 1L, 2L);

        // 40+60=100 ≠ 51+50=101：净额不守恒
        assertThatThrownBy(() -> coupledService.submitAdjustment(group.group().id(), "req-ca-nc",
                T0, List.of(target(1L, 51, 0), target(2L, 50, 0))))
                .isInstanceOf(UnprocessableException.class);
        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("40.00"));
        assertThat(openingOf(2L)).isEqualByComparingTo(new BigDecimal("60.00"));
        assertThat(countOf("coupled_adjustment")).isZero();
        assertThat(countOf("adjustment_task")).isZero();
        assertThat(countOf("valve_opening_version")).isZero();

        // 失败不占键：同键修正参数后可重新受理
        var view = coupledService.submitAdjustment(group.group().id(), "req-ca-nc", T0,
                List.of(target(1L, 50, 0), target(2L, 50, 0)));
        assertThat(view.adjustment().requestId()).isEqualTo("req-ca-nc");
        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("50.00"));
    }

    @Test
    void 缺阀多阀重复阀均全组零变化() {
        setOpening(1L, "40.00");
        setOpening(2L, "60.00");
        var group = createGroup("req-cg-miss", 1L, 2L);

        // 缺阀：只提交阀 1
        assertThatThrownBy(() -> coupledService.submitAdjustment(group.group().id(), "req-ca-m1",
                T0, List.of(target(1L, 50, 0))))
                .isInstanceOf(UnprocessableException.class);
        // 多阀：提交组外阀 3
        assertThatThrownBy(() -> coupledService.submitAdjustment(group.group().id(), "req-ca-m2",
                T0, List.of(target(1L, 50, 0), target(2L, 50, 0), target(3L, 50, 0))))
                .isInstanceOf(UnprocessableException.class);
        // 重复阀
        assertThatThrownBy(() -> coupledService.submitAdjustment(group.group().id(), "req-ca-m3",
                T0, List.of(target(1L, 50, 0), target(1L, 50, 0), target(2L, 50, 0))))
                .isInstanceOf(UnprocessableException.class);
        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("40.00"));
        assertThat(openingOf(2L)).isEqualByComparingTo(new BigDecimal("60.00"));
        assertThat(countOf("coupled_adjustment")).isZero();
        assertThat(countOf("adjustment_task")).isZero();
    }

    @Test
    void 旧版本409且全组零变化() {
        setOpening(1L, "40.00");
        setOpening(2L, "60.00");
        var group = createGroup("req-cg-ver", 1L, 2L);

        // 阀 2 提交旧版本 1（实际为 0）
        assertThatThrownBy(() -> coupledService.submitAdjustment(group.group().id(), "req-ca-ver",
                T0, List.of(target(1L, 50, 0), target(2L, 50, 1))))
                .isInstanceOf(ConflictException.class);
        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("40.00"));
        assertThat(openingOf(2L)).isEqualByComparingTo(new BigDecimal("60.00"));
        assertThat(countOf("coupled_adjustment")).isZero();
        assertThat(countOf("valve_opening_version")).isZero();
    }

    @Test
    void 单阀变化超过10个百分点全组零变化() {
        setOpening(1L, "40.00");
        setOpening(2L, "60.00");
        var group = createGroup("req-cg-step", 1L, 2L);

        // 净额守恒（40+60=55+45=100）但阀 1 变化 15 > 10
        assertThatThrownBy(() -> coupledService.submitAdjustment(group.group().id(), "req-ca-step",
                T0, List.of(target(1L, 55, 0), target(2L, 45, 0))))
                .isInstanceOf(UnprocessableException.class);
        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("40.00"));
        assertThat(openingOf(2L)).isEqualByComparingTo(new BigDecimal("60.00"));
        assertThat(countOf("coupled_adjustment")).isZero();
    }

    @Test
    void 任一阀24小时次数超限全组零变化() {
        setOpening(1L, "40.00");
        setOpening(2L, "60.00");
        // 单元 1 在 24 小时内已执行 3 次调节（历史已归档任务）
        for (int i = 1; i <= 3; i++) {
            jdbc.update("INSERT INTO adjustment_task"
                            + " (station_id, unit_id, request_id, fingerprint, status,"
                            + " executed_at, created_at) VALUES (1, 1, ?, 'fp', 'ARCHIVED', ?, ?)",
                    "req-hist-" + i, java.sql.Timestamp.from(T0.minus(Duration.ofHours(1))),
                    java.sql.Timestamp.from(T0.minus(Duration.ofHours(1))));
        }
        var group = createGroup("req-cg-limit", 1L, 2L);

        assertThatThrownBy(() -> coupledService.submitAdjustment(group.group().id(), "req-ca-limit",
                T0, List.of(target(1L, 50, 0), target(2L, 50, 0))))
                .isInstanceOf(UnprocessableException.class);
        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("40.00"));
        assertThat(openingOf(2L)).isEqualByComparingTo(new BigDecimal("60.00"));
        assertThat(countOf("coupled_adjustment")).isZero();
        assertThat(countOf("valve_opening_version")).isZero();
    }

    @Test
    void 不变阀不消费额度和次数() {
        setOpening(2L, "40.00");
        setOpening(3L, "50.00");
        // 阀 1 铅封+解封但目标不变（50→50），阀 2、3 实际变化
        UnsealOrder order = sealAndUnseal(1L, "unchanged");
        var group = coupledService.createGroup(STATION_ID, "req-cg-unc", List.of(
                new CoupledService.MemberCommand(1L, 1),
                new CoupledService.MemberCommand(2L, 1),
                new CoupledService.MemberCommand(3L, 1)));

        // 调前 50+40+50=140，目标 50+45+45=140
        var view = coupledService.submitAdjustment(group.group().id(), "req-ca-unc", T0,
                List.of(new CoupledService.TargetCommand(1L, 50, 0, order.id()),
                        target(2L, 45, 0), target(3L, 45, 0)));

        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(openingOf(2L)).isEqualByComparingTo(new BigDecimal("45.00"));
        assertThat(openingOf(3L)).isEqualByComparingTo(new BigDecimal("45.00"));
        // 不变阀：无任务、无版本、不消费额度
        var item1 = view.items().stream().filter(i -> i.valveId() == 1L).findFirst().orElseThrow();
        assertThat(item1.taskId()).isNull();
        assertThat(item1.unsealId()).isEqualTo(order.id());
        assertThat(quotaUsedOf(order.id())).isZero();
        assertThat(countOf("unseal_quota_usage")).isZero();
        assertThat(countOf("adjustment_task")).isEqualTo(2);
        assertThat(countOf("valve_opening_version")).isEqualTo(2);
        assertThat(countOf("unseal_order")).isEqualTo(1);
    }

    @Test
    void 铅封双阀凭合法解封单联调成功并逐阀消费额度() {
        setOpening(1L, "40.00");
        setOpening(2L, "60.00");
        UnsealOrder order1 = sealAndUnseal(1L, "a");
        UnsealOrder order2 = sealAndUnseal(2L, "b");
        var group = createGroup("req-cg-sealed", 1L, 2L);

        var view = coupledService.submitAdjustment(group.group().id(), "req-ca-sealed", T0,
                List.of(new CoupledService.TargetCommand(1L, 50, 0, order1.id()),
                        new CoupledService.TargetCommand(2L, 50, 0, order2.id())));

        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(openingOf(2L)).isEqualByComparingTo(new BigDecimal("50.00"));
        // 每个实际变化阀消费一次额度，轨迹可查
        assertThat(quotaUsedOf(order1.id())).isEqualTo(1);
        assertThat(quotaUsedOf(order2.id())).isEqualTo(1);
        assertThat(countOf("unseal_quota_usage")).isEqualTo(2);
        assertThat(sealService.getQuotaTrail(order1.id())).hasSize(1);
        assertThat(sealService.getQuotaTrail(order2.id())).hasSize(1);
        assertThat(view.items()).allMatch(i -> i.unsealId() != null && i.taskId() != null);
    }

    @Test
    void 第二只没有解封额度时第一只也不能先动() {
        setOpening(1L, "40.00");
        setOpening(2L, "60.00");
        UnsealOrder order1 = sealAndUnseal(1L, "ok");
        UnsealOrder order2 = sealAndUnseal(2L, "empty");
        // 第二只阀解封额度已用尽
        jdbc.update("UPDATE unseal_order SET quota_used = quota_total WHERE id = ?", order2.id());
        var group = createGroup("req-cg-noquota", 1L, 2L);

        assertThatThrownBy(() -> coupledService.submitAdjustment(group.group().id(),
                "req-ca-noquota", T0,
                List.of(new CoupledService.TargetCommand(1L, 50, 0, order1.id()),
                        new CoupledService.TargetCommand(2L, 50, 0, order2.id()))))
                .isInstanceOf(UnprocessableException.class);
        // 全组零变化：第一只阀未先动，额度未消费，无联调记录
        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("40.00"));
        assertThat(openingOf(2L)).isEqualByComparingTo(new BigDecimal("60.00"));
        assertThat(quotaUsedOf(order1.id())).isZero();
        assertThat(countOf("unseal_quota_usage")).isZero();
        assertThat(countOf("coupled_adjustment")).isZero();
        assertThat(countOf("adjustment_task")).isZero();
        assertThat(countOf("valve_opening_version")).isZero();
    }

    @Test
    void 失效解封全组零变化() {
        setOpening(1L, "40.00");
        setOpening(2L, "60.00");
        UnsealOrder order1 = sealAndUnseal(1L, "ok2");
        UnsealOrder order2 = sealAndUnseal(2L, "revoked");
        sealService.revokeUnseal(order2.id(), "req-revoke-2", 1);
        var group = createGroup("req-cg-revoked", 1L, 2L);

        assertThatThrownBy(() -> coupledService.submitAdjustment(group.group().id(),
                "req-ca-revoked", T0,
                List.of(new CoupledService.TargetCommand(1L, 50, 0, order1.id()),
                        new CoupledService.TargetCommand(2L, 50, 0, order2.id()))))
                .isInstanceOf(UnprocessableException.class);
        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("40.00"));
        assertThat(quotaUsedOf(order1.id())).isZero();
        assertThat(countOf("coupled_adjustment")).isZero();
    }

    @Test
    void 铅封阀缺少解封单全组零变化() {
        setOpening(1L, "40.00");
        setOpening(2L, "60.00");
        sealAndUnseal(1L, "need");
        var group = createGroup("req-cg-noseal", 1L, 2L);

        // 阀 1 有铅封但未提供解封单
        assertThatThrownBy(() -> coupledService.submitAdjustment(group.group().id(),
                "req-ca-noseal", T0, List.of(target(1L, 50, 0), target(2L, 50, 0))))
                .isInstanceOf(UnprocessableException.class);
        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("40.00"));
        assertThat(countOf("coupled_adjustment")).isZero();
    }

    @Test
    void 联调幂等重放与异参409() {
        setOpening(1L, "40.00");
        setOpening(2L, "60.00");
        var group = createGroup("req-cg-idem", 1L, 2L);
        var first = coupledService.submitAdjustment(group.group().id(), "req-ca-idem", T0,
                List.of(target(1L, 50, 0), target(2L, 50, 0)));

        // 同参成功先重放原响应：同联调单、不重复生成任务与版本
        var replay = coupledService.submitAdjustment(group.group().id(), "req-ca-idem", T0,
                List.of(target(1L, 50, 0), target(2L, 50, 0)));
        assertThat(replay.adjustment().id()).isEqualTo(first.adjustment().id());
        assertThat(countOf("coupled_adjustment")).isEqualTo(1);
        assertThat(countOf("adjustment_task")).isEqualTo(2);
        assertThat(countOf("valve_opening_version")).isEqualTo(2);

        // 异参 409：同键不同时刻
        assertThatThrownBy(() -> coupledService.submitAdjustment(group.group().id(), "req-ca-idem",
                T0.plus(Duration.ofHours(1)), List.of(target(1L, 50, 0), target(2L, 50, 0))))
                .isInstanceOf(ConflictException.class);
        // 异参 409：同键不同目标
        assertThatThrownBy(() -> coupledService.submitAdjustment(group.group().id(), "req-ca-idem",
                T0, List.of(target(1L, 45, 1), target(2L, 55, 1))))
                .isInstanceOf(ConflictException.class);
        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(openingOf(2L)).isEqualByComparingTo(new BigDecimal("50.00"));
    }

    @Test
    void 联调成功后各阀沿用原温差评估及归档() {
        submitReadings(40.00, 42.50, 41.00, 41.00);
        setOpening(1L, "40.00");
        setOpening(2L, "60.00");
        var group = createGroup("req-cg-asm", 1L, 2L);
        var view = coupledService.submitAdjustment(group.group().id(), "req-ca-asm", T0,
                List.of(target(1L, 50, 0), target(2L, 50, 0)));

        clock.advance(Duration.ofHours(4));
        for (var item : view.items()) {
            var assessment = adjustmentService.assess(item.taskId(), "req-asm-" + item.taskId());
            assertThat(assessment.taskId()).isEqualTo(item.taskId());
            AdjustmentTask archived = adjustmentService.archive(item.taskId());
            assertThat(archived.status()).isEqualTo(AdjustmentTask.TaskStatus.ARCHIVED);
        }
    }

    @Test
    void 建组校验与活动组唯一() {
        // 成员数越界
        assertThatThrownBy(() -> coupledService.createGroup(STATION_ID, "req-cg-few",
                List.of(new CoupledService.MemberCommand(1L, 1))))
                .isInstanceOf(UnprocessableException.class);
        assertThatThrownBy(() -> coupledService.createGroup(STATION_ID, "req-cg-many",
                List.of(new CoupledService.MemberCommand(1L, 1),
                        new CoupledService.MemberCommand(2L, 1),
                        new CoupledService.MemberCommand(3L, 1),
                        new CoupledService.MemberCommand(4L, 1),
                        new CoupledService.MemberCommand(1L, 2),
                        new CoupledService.MemberCommand(2L, 2))))
                .isInstanceOf(UnprocessableException.class);
        // 权重越界
        assertThatThrownBy(() -> coupledService.createGroup(STATION_ID, "req-cg-w0",
                List.of(new CoupledService.MemberCommand(1L, 0),
                        new CoupledService.MemberCommand(2L, 1))))
                .isInstanceOf(UnprocessableException.class);
        assertThatThrownBy(() -> coupledService.createGroup(STATION_ID, "req-cg-w11",
                List.of(new CoupledService.MemberCommand(1L, 11),
                        new CoupledService.MemberCommand(2L, 1))))
                .isInstanceOf(UnprocessableException.class);
        // 阀不存在
        assertThatThrownBy(() -> coupledService.createGroup(STATION_ID, "req-cg-404",
                List.of(new CoupledService.MemberCommand(1L, 1),
                        new CoupledService.MemberCommand(99L, 1))))
                .isInstanceOf(NotFoundException.class);

        var group = createGroup("req-cg-uniq", 1L, 2L);
        // 同键同参重放
        var replay = createGroup("req-cg-uniq", 1L, 2L);
        assertThat(replay.group().id()).isEqualTo(group.group().id());
        assertThat(countOf("coupled_group")).isEqualTo(1);
        // 同键异参 409
        assertThatThrownBy(() -> createGroup("req-cg-uniq", 1L, 3L))
                .isInstanceOf(ConflictException.class);
        // 阀只能属于一个活动组
        assertThatThrownBy(() -> createGroup("req-cg-dup", 2L, 3L))
                .isInstanceOf(UnprocessableException.class);
    }

    @Test
    void 改组冻结版本且旧版409() {
        setOpening(1L, "40.00");
        var group = createGroup("req-cg-mod", 1L, 2L);

        // 旧版 409
        assertThatThrownBy(() -> coupledService.modifyGroup(group.group().id(), 9,
                List.of(new CoupledService.MemberCommand(1L, 1),
                        new CoupledService.MemberCommand(3L, 1))))
                .isInstanceOf(ConflictException.class);

        var modified = coupledService.modifyGroup(group.group().id(), 1,
                List.of(new CoupledService.MemberCommand(1L, 2),
                        new CoupledService.MemberCommand(3L, 3)));
        assertThat(modified.group().version()).isEqualTo(2);
        assertThat(modified.members()).hasSize(2);
        assertThat(modified.members().stream().map(m -> m.valveId()))
                .containsExactly(1L, 3L);
        assertThat(modified.members().stream().map(m -> m.weight()))
                .containsExactly(2, 3);

        // 解除后禁止修改
        coupledService.dissolveGroup(group.group().id(), 2);
        assertThatThrownBy(() -> coupledService.modifyGroup(group.group().id(), 3,
                List.of(new CoupledService.MemberCommand(1L, 1),
                        new CoupledService.MemberCommand(2L, 1))))
                .isInstanceOf(UnprocessableException.class);
    }

    @Test
    void 解除只释放组归属不回拨开度不返还额度() {
        setOpening(1L, "40.00");
        setOpening(2L, "60.00");
        UnsealOrder order1 = sealAndUnseal(1L, "dis");
        var group = createGroup("req-cg-dis", 1L, 2L);
        coupledService.submitAdjustment(group.group().id(), "req-ca-dis", T0,
                List.of(new CoupledService.TargetCommand(1L, 50, 0, order1.id()),
                        target(2L, 50, 0)));

        // 旧版 409
        assertThatThrownBy(() -> coupledService.dissolveGroup(group.group().id(), 9))
                .isInstanceOf(ConflictException.class);
        var dissolved = coupledService.dissolveGroup(group.group().id(), 1);
        assertThat(dissolved.group().status()).isEqualTo(CoupledGroup.GroupStatus.DISSOLVED);
        assertThat(dissolved.group().version()).isEqualTo(2);
        assertThat(dissolved.group().dissolvedAt()).isEqualTo(T0);

        // 不回拨开度、不返还已消费额度、历史留痕可复算
        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(openingOf(2L)).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(quotaUsedOf(order1.id())).isEqualTo(1);
        var history = coupledService.listGroupAdjustments(group.group().id());
        assertThat(history).hasSize(1);
        assertThat(history.get(0).weightedBefore()).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(history.get(0).weightedAfter()).isEqualByComparingTo(new BigDecimal("100"));

        // 已解除组禁止联调与重复解除
        assertThatThrownBy(() -> coupledService.submitAdjustment(group.group().id(), "req-ca-dis2",
                T0, List.of(target(1L, 50, 1), target(2L, 50, 1))))
                .isInstanceOf(UnprocessableException.class);
        assertThatThrownBy(() -> coupledService.dissolveGroup(group.group().id(), 2))
                .isInstanceOf(UnprocessableException.class);

        // 组归属已释放：阀可加入新活动组
        var newGroup = createGroup("req-cg-new", 1L, 2L);
        assertThat(newGroup.group().status()).isEqualTo(CoupledGroup.GroupStatus.ACTIVE);
    }
}
