package com.example.starter;

import com.example.starter.domain.AdjustmentGroup;
import com.example.starter.domain.AdjustmentTask;
import com.example.starter.domain.CoupledAdjustmentItem;
import com.example.starter.domain.UnsealOrder;
import com.example.starter.domain.ValveSeal;
import com.example.starter.service.AdjustmentService;
import com.example.starter.service.ConflictException;
import com.example.starter.service.CoupledAdjustmentService;
import com.example.starter.service.NotFoundException;
import com.example.starter.service.SealService;
import com.example.starter.service.UnprocessableException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 联调主流程与失败分支：40/60→50/50 全组原子成功、净额守恒、缺阀、缺额度、
 * 版本与幂等、24 小时次数、组解除及联调后原评估归档流程。
 */
class CoupledAdjustmentServiceTest extends IntegrationTestBase {

    private static final Instant REQ_AT = Instant.parse("2026-09-26T01:00:00Z");

    @Autowired
    private CoupledAdjustmentService coupledService;
    @Autowired
    private SealService sealService;
    @Autowired
    private AdjustmentService adjustmentService;

    private void setOpening(long unitId, String opening) {
        jdbc.update("UPDATE valve SET opening_percent = ? WHERE unit_id = ?",
                new BigDecimal(opening), unitId);
    }

    private CoupledAdjustmentService.GroupView createGroup(String requestId, long... valveIds) {
        List<CoupledAdjustmentService.MemberSpec> members = java.util.Arrays.stream(valveIds)
                .mapToObj(v -> new CoupledAdjustmentService.MemberSpec(v, 1))
                .toList();
        return coupledService.createGroup(STATION_ID, requestId, members);
    }

    private CoupledAdjustmentService.TargetSpec target(long valveId, int version, int opening) {
        return new CoupledAdjustmentService.TargetSpec(valveId, version, opening, null, null);
    }

    private CoupledAdjustmentService.TargetSpec sealedTarget(long valveId, int version, int opening,
                                                             long unsealId, int unsealVersion) {
        return new CoupledAdjustmentService.TargetSpec(valveId, version, opening,
                unsealId, unsealVersion);
    }

    @Test
    void 两只同权阀40和60联调到50和50一起成功() {
        setOpening(1L, "40.00");
        setOpening(2L, "60.00");
        CoupledAdjustmentService.GroupView group = createGroup("req-g-1", 1L, 2L);
        assertThat(group.group().version()).isEqualTo(1);
        assertThat(group.members()).hasSize(2);

        CoupledAdjustmentService.CoupledView view = coupledService.submit(group.group().id(),
                "req-c-1", 1, REQ_AT,
                List.of(target(1L, 0, 50), target(2L, 0, 50)));

        // 全组原子生效：两只阀一起到位，逐阀版本与任务同事务生成
        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(openingOf(2L)).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(view.items()).hasSize(2);
        assertThat(view.items()).allMatch(i -> i.taskId() != null);
        assertThat(countOf("adjustment_task")).isEqualTo(2);
        assertThat(countOf("valve_opening_version")).isEqualTo(2);
        // 联调单版本成功加 1
        assertThat(coupledService.getGroup(group.group().id()).group().version()).isEqualTo(2);
        // 联调前后开度保留，可复算净加权开度
        CoupledAdjustmentItem first = view.items().get(0);
        assertThat(first.oldOpening()).isEqualByComparingTo(new BigDecimal("40.00"));
        assertThat(first.newOpening()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(view.items().get(1).oldOpening()).isEqualByComparingTo(new BigDecimal("60.00"));
    }

    @Test
    void 加权净额不守恒全组零变化() {
        setOpening(1L, "40.00");
        setOpening(2L, "60.00");
        CoupledAdjustmentService.GroupView group = createGroup("req-g-2", 1L, 2L);

        // 加权目标 50+55=105 ≠ 调前 40+60=100
        assertThatThrownBy(() -> coupledService.submit(group.group().id(), "req-c-2", 1, REQ_AT,
                List.of(target(1L, 0, 50), target(2L, 0, 55))))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("加权目标总和");

        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("40.00"));
        assertThat(openingOf(2L)).isEqualByComparingTo(new BigDecimal("60.00"));
        assertThat(countOf("adjustment_task")).isZero();
        assertThat(countOf("valve_opening_version")).isZero();
        assertThat(countOf("coupled_adjustment")).isZero();
        assertThat(coupledService.getGroup(group.group().id()).group().version()).isEqualTo(1);
    }

    @Test
    void 加权守恒按权重精确比较不舍入() {
        setOpening(1L, "40.00");
        setOpening(2L, "60.00");
        // 权重 2:1，调前加权和 2*40+1*60=140；目标 45/50 → 2*45+50=140 精确守恒
        CoupledAdjustmentService.GroupView group = coupledService.createGroup(STATION_ID,
                "req-g-2w", List.of(new CoupledAdjustmentService.MemberSpec(1L, 2),
                        new CoupledAdjustmentService.MemberSpec(2L, 1)));

        CoupledAdjustmentService.CoupledView view = coupledService.submit(group.group().id(),
                "req-c-2w", 1, REQ_AT, List.of(target(1L, 0, 45), target(2L, 0, 50)));

        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("45.00"));
        assertThat(openingOf(2L)).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(view.items()).hasSize(2);
        // 净加权开度复算：2*45+1*50 = 140 = 调前加权和
        CoupledAdjustmentService.GroupView after = coupledService.getGroup(group.group().id());
        BigDecimal net = after.members().stream()
                .map(m -> m.currentOpening().multiply(BigDecimal.valueOf(m.weight())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(net).isEqualByComparingTo(new BigDecimal("140.00"));
    }

    @Test
    void 缺阀全组零变化() {
        setOpening(1L, "40.00");
        setOpening(2L, "60.00");
        CoupledAdjustmentService.GroupView group = createGroup("req-g-3", 1L, 2L);

        assertThatThrownBy(() -> coupledService.submit(group.group().id(), "req-c-3", 1, REQ_AT,
                List.of(target(1L, 0, 50))))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("缺少成员阀");

        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("40.00"));
        assertThat(countOf("coupled_adjustment")).isZero();
    }

    @Test
    void 第二只阀没有解封额度时第一只也不能先动() {
        setOpening(1L, "40.00");
        setOpening(2L, "60.00");
        ValveSeal seal1 = sealService.registerSeal(1L, "req-s-q1", "SEAL-Q1", 0);
        ValveSeal seal2 = sealService.registerSeal(2L, "req-s-q2", "SEAL-Q2", 0);
        UnsealOrder order1 = sealService.approveUnseal(seal1.id(), "req-u-q1", 1, 0,
                "张三", "李四", T0, T0.plus(Duration.ofHours(2)));
        UnsealOrder order2 = sealService.approveUnseal(seal2.id(), "req-u-q2", 1, 0,
                "张三", "李四", T0, T0.plus(Duration.ofHours(2)));
        // 第二只阀解封额度耗尽（合成数据直接置位）
        jdbc.update("UPDATE unseal_order SET quota_used = quota_total WHERE id = ?", order2.id());
        CoupledAdjustmentService.GroupView group = createGroup("req-g-4", 1L, 2L);

        assertThatThrownBy(() -> coupledService.submit(group.group().id(), "req-c-4", 1, REQ_AT,
                List.of(sealedTarget(1L, 0, 50, order1.id(), 1),
                        sealedTarget(2L, 0, 50, order2.id(), 1))))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("额度已用尽");

        // 全组零变化：第一只阀没有先动，额度、次数、版本均未消费
        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("40.00"));
        assertThat(openingOf(2L)).isEqualByComparingTo(new BigDecimal("60.00"));
        assertThat(countOf("adjustment_task")).isZero();
        assertThat(countOf("valve_opening_version")).isZero();
        assertThat(countOf("unseal_quota_usage")).isZero();
        assertThat(countOf("coupled_adjustment")).isZero();
        assertThat(sealService.getUnseal(order1.id()).quotaUsed()).isZero();

        // 失败不占键：补足额度后同键可成功
        jdbc.update("UPDATE unseal_order SET quota_used = 0 WHERE id = ?", order2.id());
        CoupledAdjustmentService.CoupledView view = coupledService.submit(group.group().id(),
                "req-c-4", 1, REQ_AT,
                List.of(sealedTarget(1L, 0, 50, order1.id(), 1),
                        sealedTarget(2L, 0, 50, order2.id(), 1)));
        assertThat(view.items()).hasSize(2);
        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(openingOf(2L)).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(sealService.getUnseal(order1.id()).quotaUsed()).isEqualTo(1);
        assertThat(sealService.getUnseal(order2.id()).quotaUsed()).isEqualTo(1);
    }

    @Test
    void 铅封阀联调成功逐阀消费额度且不变阀不消费() {
        setOpening(1L, "40.00");
        setOpening(2L, "60.00");
        setOpening(3L, "70.00");
        ValveSeal seal1 = sealService.registerSeal(1L, "req-s-p1", "SEAL-P1", 0);
        ValveSeal seal3 = sealService.registerSeal(3L, "req-s-p3", "SEAL-P3", 0);
        UnsealOrder order1 = sealService.approveUnseal(seal1.id(), "req-u-p1", 1, 0,
                "张三", "李四", T0, T0.plus(Duration.ofHours(2)));
        UnsealOrder order3 = sealService.approveUnseal(seal3.id(), "req-u-p3", 1, 0,
                "张三", "李四", T0, T0.plus(Duration.ofHours(2)));
        CoupledAdjustmentService.GroupView group = createGroup("req-g-5", 1L, 2L, 3L);

        // 阀1 40→50、阀2 60→50 变化，阀3 70→70 不变：加权 50+50+70 = 40+60+70 守恒
        CoupledAdjustmentService.CoupledView view = coupledService.submit(group.group().id(),
                "req-c-5", 1, REQ_AT,
                List.of(sealedTarget(1L, 0, 50, order1.id(), 1),
                        target(2L, 0, 50),
                        sealedTarget(3L, 0, 70, order3.id(), 1)));

        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(openingOf(2L)).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(openingOf(3L)).isEqualByComparingTo(new BigDecimal("70.00"));
        // 变化阀各消费一次额度和次数；不变阀不消费
        assertThat(sealService.getUnseal(order1.id()).quotaUsed()).isEqualTo(1);
        assertThat(sealService.getUnseal(order3.id()).quotaUsed()).isZero();
        assertThat(countOf("adjustment_task")).isEqualTo(2);
        assertThat(countOf("valve_opening_version")).isEqualTo(2);
        assertThat(countOf("unseal_quota_usage")).isEqualTo(1);
        CoupledAdjustmentItem unchanged = view.items().stream()
                .filter(i -> i.valveId() == 3L).findFirst().orElseThrow();
        assertThat(unchanged.taskId()).isNull();
        assertThat(unchanged.unsealId()).isEqualTo(order3.id());
    }

    @Test
    void 失效解封全组零变化() {
        setOpening(1L, "40.00");
        setOpening(2L, "60.00");
        ValveSeal seal1 = sealService.registerSeal(1L, "req-s-x1", "SEAL-X1", 0);
        ValveSeal seal2 = sealService.registerSeal(2L, "req-s-x2", "SEAL-X2", 0);
        UnsealOrder order1 = sealService.approveUnseal(seal1.id(), "req-u-x1", 1, 0,
                "张三", "李四", T0, T0.plus(Duration.ofHours(2)));
        UnsealOrder order2 = sealService.approveUnseal(seal2.id(), "req-u-x2", 1, 0,
                "张三", "李四", T0, T0.plus(Duration.ofHours(2)));
        // 第二只阀解封单被撤销，失效
        sealService.revokeUnseal(order2.id(), "req-rv-x2", 1);
        CoupledAdjustmentService.GroupView group = createGroup("req-g-6", 1L, 2L);

        assertThatThrownBy(() -> coupledService.submit(group.group().id(), "req-c-6", 1, REQ_AT,
                List.of(sealedTarget(1L, 0, 50, order1.id(), 1),
                        sealedTarget(2L, 0, 50, order2.id(), 2))))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("已撤销或已终结");

        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("40.00"));
        assertThat(sealService.getUnseal(order1.id()).quotaUsed()).isZero();
        assertThat(countOf("coupled_adjustment")).isZero();
    }

    @Test
    void 旧版本与旧组版本分别拒绝且全组零变化() {
        setOpening(1L, "40.00");
        setOpening(2L, "60.00");
        CoupledAdjustmentService.GroupView group = createGroup("req-g-7", 1L, 2L);

        // 逐阀旧版本：阀1 声称版本 5，实际 0
        assertThatThrownBy(() -> coupledService.submit(group.group().id(), "req-c-7a", 1, REQ_AT,
                List.of(target(1L, 5, 50), target(2L, 0, 50))))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("开度版本不一致");
        // 联调单旧版本：组版本 1，声称 2 → 409
        assertThatThrownBy(() -> coupledService.submit(group.group().id(), "req-c-7b", 2, REQ_AT,
                List.of(target(1L, 0, 50), target(2L, 0, 50))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("联调组版本不一致");

        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("40.00"));
        assertThat(openingOf(2L)).isEqualByComparingTo(new BigDecimal("60.00"));
        assertThat(countOf("coupled_adjustment")).isZero();
    }

    @Test
    void 幂等同参重放异参409且失败不占键() {
        setOpening(1L, "40.00");
        setOpening(2L, "60.00");
        CoupledAdjustmentService.GroupView group = createGroup("req-g-8", 1L, 2L);

        CoupledAdjustmentService.CoupledView first = coupledService.submit(group.group().id(),
                "req-c-8", 1, REQ_AT, List.of(target(1L, 0, 50), target(2L, 0, 50)));
        // 同参同键：重放原响应，不重复生成任务与版本
        CoupledAdjustmentService.CoupledView replay = coupledService.submit(group.group().id(),
                "req-c-8", 1, REQ_AT, List.of(target(1L, 0, 50), target(2L, 0, 50)));
        assertThat(replay.coupled().id()).isEqualTo(first.coupled().id());
        assertThat(countOf("adjustment_task")).isEqualTo(2);
        assertThat(countOf("valve_opening_version")).isEqualTo(2);
        assertThat(countOf("coupled_adjustment")).isEqualTo(1);
        // 同键异参：409
        assertThatThrownBy(() -> coupledService.submit(group.group().id(), "req-c-8", 1, REQ_AT,
                List.of(target(1L, 0, 45), target(2L, 0, 55))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("同键异参");
        // 同键异 UTC 时刻亦 409（时刻参与指纹）
        assertThatThrownBy(() -> coupledService.submit(group.group().id(), "req-c-8", 1,
                REQ_AT.plusSeconds(60), List.of(target(1L, 0, 50), target(2L, 0, 50))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("同键异参");
    }

    @Test
    void 恰10个百分点允许超过则全组零变化() {
        setOpening(1L, "40.00");
        setOpening(2L, "60.00");
        CoupledAdjustmentService.GroupView group = createGroup("req-g-9", 1L, 2L);

        // 阀1 变化 11 个百分点：超限
        assertThatThrownBy(() -> coupledService.submit(group.group().id(), "req-c-9a", 1, REQ_AT,
                List.of(target(1L, 0, 51), target(2L, 0, 49))))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("不超过 10 个百分点");
        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("40.00"));

        // 恰 10：40→50、60→50 均合法
        coupledService.submit(group.group().id(), "req-c-9b", 1, REQ_AT,
                List.of(target(1L, 0, 50), target(2L, 0, 50)));
        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(openingOf(2L)).isEqualByComparingTo(new BigDecimal("50.00"));
    }

    @Test
    void 任一阀24小时次数超限全组零变化() {
        setOpening(1L, "40.00");
        setOpening(2L, "60.00");
        // 合成数据：单元1 在 24 小时内已执行 3 次调节
        for (int i = 0; i < 3; i++) {
            jdbc.update("INSERT INTO adjustment_task (station_id, unit_id, request_id, fingerprint,"
                            + " status, executed_at, created_at) VALUES (1, 1, ?, 'fp', 'EXECUTING',"
                            + " ?, ?)",
                    "req-hist-" + i, Timestamp.from(T0.minus(Duration.ofHours(1))),
                    Timestamp.from(T0.minus(Duration.ofHours(1))));
        }
        CoupledAdjustmentService.GroupView group = createGroup("req-g-10", 1L, 2L);

        assertThatThrownBy(() -> coupledService.submit(group.group().id(), "req-c-10", 1, REQ_AT,
                List.of(target(1L, 0, 50), target(2L, 0, 50))))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("24 小时内调节次数已达上限");

        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("40.00"));
        assertThat(openingOf(2L)).isEqualByComparingTo(new BigDecimal("60.00"));
        assertThat(countOf("valve_opening_version")).isZero();
        assertThat(countOf("coupled_adjustment")).isZero();
    }

    @Test
    void 建组校验与阀唯一活动组() {
        // 单阀不成组
        assertThatThrownBy(() -> createGroup("req-g-11a", 1L))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("2~5");
        // 权重越界
        assertThatThrownBy(() -> coupledService.createGroup(STATION_ID, "req-g-11b",
                List.of(new CoupledAdjustmentService.MemberSpec(1L, 0),
                        new CoupledAdjustmentService.MemberSpec(2L, 1))))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("权重");
        assertThatThrownBy(() -> coupledService.createGroup(STATION_ID, "req-g-11c",
                List.of(new CoupledAdjustmentService.MemberSpec(1L, 11),
                        new CoupledAdjustmentService.MemberSpec(2L, 1))))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("权重");
        // 重复阀
        assertThatThrownBy(() -> coupledService.createGroup(STATION_ID, "req-g-11d",
                List.of(new CoupledAdjustmentService.MemberSpec(1L, 1),
                        new CoupledAdjustmentService.MemberSpec(1L, 2))))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("重复");
        // 跨站阀
        assertThatThrownBy(() -> coupledService.createGroup(999L, "req-g-11e",
                List.of(new CoupledAdjustmentService.MemberSpec(1L, 1),
                        new CoupledAdjustmentService.MemberSpec(2L, 1))))
                .isInstanceOf(NotFoundException.class);

        CoupledAdjustmentService.GroupView group = createGroup("req-g-11f", 1L, 2L);
        assertThat(group.group().status()).isEqualTo(AdjustmentGroup.GroupStatus.ACTIVE);
        // 阀只能属于一个活动组
        assertThatThrownBy(() -> coupledService.createGroup(STATION_ID, "req-g-11g",
                List.of(new CoupledAdjustmentService.MemberSpec(1L, 1),
                        new CoupledAdjustmentService.MemberSpec(3L, 1))))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("已属于其他活动联调组");
        // 建组幂等：同键同参重放，异参 409
        CoupledAdjustmentService.GroupView replay = createGroup("req-g-11f", 1L, 2L);
        assertThat(replay.group().id()).isEqualTo(group.group().id());
        assertThat(countOf("adjustment_group")).isEqualTo(1);
        assertThatThrownBy(() -> createGroup("req-g-11f", 1L, 3L))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("同键异参");
    }

    @Test
    void 组解除只释放归属不回拨开度不返还额度() {
        setOpening(1L, "40.00");
        setOpening(2L, "60.00");
        ValveSeal seal1 = sealService.registerSeal(1L, "req-s-d1", "SEAL-D1", 0);
        UnsealOrder order1 = sealService.approveUnseal(seal1.id(), "req-u-d1", 1, 0,
                "张三", "李四", T0, T0.plus(Duration.ofHours(2)));
        CoupledAdjustmentService.GroupView group = createGroup("req-g-12", 1L, 2L);
        coupledService.submit(group.group().id(), "req-c-12", 1, REQ_AT,
                List.of(sealedTarget(1L, 0, 50, order1.id(), 1), target(2L, 0, 50)));
        assertThat(sealService.getUnseal(order1.id()).quotaUsed()).isEqualTo(1);

        // 旧版解除 409（联调成功后组版本已为 2）
        assertThatThrownBy(() -> coupledService.dissolveGroup(group.group().id(), "req-d-12a", 1))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("联调组版本不一致");
        AdjustmentGroup dissolved = coupledService.dissolveGroup(group.group().id(), "req-d-12", 2);
        assertThat(dissolved.status()).isEqualTo(AdjustmentGroup.GroupStatus.DISSOLVED);
        assertThat(dissolved.version()).isEqualTo(3);
        // 同键重复解除重放；异键重复解除 409
        AdjustmentGroup replayed = coupledService.dissolveGroup(group.group().id(), "req-d-12", 2);
        assertThat(replayed.dissolvedAt()).isEqualTo(dissolved.dissolvedAt());
        assertThatThrownBy(() -> coupledService.dissolveGroup(group.group().id(), "req-d-12b", 3))
                .isInstanceOf(ConflictException.class);
        // 不回拨开度、不返还已消费额度
        assertThat(openingOf(1L)).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(sealService.getUnseal(order1.id()).quotaUsed()).isEqualTo(1);
        // 解除后禁止联调
        assertThatThrownBy(() -> coupledService.submit(group.group().id(), "req-c-12b", 3, REQ_AT,
                List.of(target(1L, 1, 40), target(2L, 1, 60))))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("已解除");
        // 归属释放：同一批阀可重建新组
        CoupledAdjustmentService.GroupView regrouped = createGroup("req-g-12b", 1L, 2L);
        assertThat(regrouped.group().status()).isEqualTo(AdjustmentGroup.GroupStatus.ACTIVE);
    }

    @Test
    void 联调成功后各阀仍走原温差评估及归档() {
        setOpening(1L, "40.00");
        setOpening(2L, "60.00");
        submitReadings(40.00, 42.50, 41.00, 41.00);
        CoupledAdjustmentService.GroupView group = createGroup("req-g-13", 1L, 2L);
        CoupledAdjustmentService.CoupledView view = coupledService.submit(group.group().id(),
                "req-c-13", 1, REQ_AT, List.of(target(1L, 0, 50), target(2L, 0, 50)));

        // 联调不自动评估：任务仍为执行中，稳定期满 4 小时后逐任务评估
        long taskId = view.items().get(0).taskId();
        assertThat(adjustmentService.getTask(taskId).status())
                .isEqualTo(AdjustmentTask.TaskStatus.EXECUTING);
        clock.advance(Duration.ofHours(4));
        submitReadings(41.00, 41.50, 41.00, 41.00);
        adjustmentService.assess(taskId, "req-asm-13");
        assertThat(adjustmentService.getTask(taskId).status())
                .isEqualTo(AdjustmentTask.TaskStatus.ASSESSED);
        adjustmentService.archive(taskId);
        assertThat(adjustmentService.getTask(taskId).status())
                .isEqualTo(AdjustmentTask.TaskStatus.ARCHIVED);
    }
}
