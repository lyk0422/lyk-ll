package com.example.starter.service;

import java.math.BigDecimal;

import com.example.starter.domain.WindowStatus;
import com.example.starter.error.BusinessException;
import com.example.starter.support.AbstractWindowTest;
import com.example.starter.support.TestClockConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 靠泊窗口业务测试：半开区间冲突、端点相接、引航员复用、限深、允许时段、
 * 幂等重放、终态状态机、自动生效与改期重校验。全部基于真实 H2 库执行。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class WindowServiceTest extends AbstractWindowTest {

    @Test
    void 内部重叠与已批准窗口冲突被拒绝() {
        var first = service.create(cmd("REQ-1", berthA, at("2026-10-01T10:00:00Z"), at("2026-10-01T12:00:00Z")));
        service.approve(first.window().id());

        assertThatThrownBy(() -> service.create(
                cmd("REQ-2", berthA, at("2026-10-01T11:00:00Z"), at("2026-10-01T13:00:00Z"))))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo("BERTH_WINDOW_CONFLICT");
                });
        // 包含关系同样算内部重叠
        assertThatThrownBy(() -> service.create(
                cmd("REQ-3", berthA, at("2026-10-01T10:30:00Z"), at("2026-10-01T11:30:00Z"))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo("BERTH_WINDOW_CONFLICT"));
        assertThat(countWindows()).isEqualTo(1);
    }

    @Test
    void 端点恰好相接不算冲突() {
        var first = service.create(cmd("REQ-1", berthA, at("2026-10-01T10:00:00Z"), at("2026-10-01T12:00:00Z")));
        service.approve(first.window().id());

        // 前邻接 [08:00, 10:00) 与后邻接 [12:00, 14:00) 均合法
        var before = service.create(cmd("REQ-2", berthA, at("2026-10-01T08:00:00Z"), at("2026-10-01T10:00:00Z")));
        var after = service.create(cmd("REQ-3", berthA, at("2026-10-01T12:00:00Z"), at("2026-10-01T14:00:00Z")));
        assertThat(before.created()).isTrue();
        assertThat(after.created()).isTrue();
        assertThat(countWindows()).isEqualTo(3);
    }

    @Test
    void 待审批窗口不与已批准窗口构成冲突但审批时重校验() {
        var approved = service.create(cmd("REQ-1", berthA, at("2026-10-01T10:00:00Z"), at("2026-10-01T12:00:00Z")));
        // 先建待审批（此时无已批准窗口），再批准另一个重叠窗口，最后审批应失败
        var pending = service.create(cmd("REQ-2", berthA, at("2026-10-01T11:00:00Z"), at("2026-10-01T13:00:00Z")));
        service.approve(approved.window().id());

        assertThatThrownBy(() -> service.approve(pending.window().id()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo("BERTH_WINDOW_CONFLICT"));
        assertThat(service.get(pending.window().id()).status()).isEqualTo(WindowStatus.PENDING);
    }

    @Test
    void 引航员同时段最多服务一艘船且错开时段可复用() {
        // 3 名引航员：w1 占 1 名、w2 占 2 名后同时段无剩余
        insertPilot("PILOT-CHARLIE");
        var w1 = service.create(cmd("REQ-1", berthA, at("2026-10-01T10:00:00Z"), at("2026-10-01T12:00:00Z")));
        var w2 = service.create(new WindowService.CreateCommand("REQ-2", "VESSEL-2", berthB,
                at("2026-10-01T10:00:00Z"), at("2026-10-01T12:00:00Z"), new BigDecimal("5.00"), 2));
        service.approve(w1.window().id());
        service.approve(w2.window().id());
        assertThat(service.get(w2.window().id()).pilotIds()).hasSize(2);

        // 第三艘同时段船无引航员可用（第三泊位，避开泊位冲突）
        long berthC = insertBerth("BERTH-C", 10.00);
        var w3 = service.create(cmd("REQ-3", berthA, at("2026-10-01T12:00:00Z"), at("2026-10-01T13:00:00Z")));
        var w3overlap = service.create(new WindowService.CreateCommand("REQ-4", "VESSEL-4", berthC,
                at("2026-10-01T11:00:00Z"), at("2026-10-01T12:00:00Z"), new BigDecimal("5.00"), 1));
        assertThatThrownBy(() -> service.approve(w3overlap.window().id()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo("PILOT_SHORTAGE"));

        // 错开时段（端点相接）可复用引航员
        var approved3 = service.approve(w3.window().id());
        assertThat(approved3.status()).isEqualTo(WindowStatus.APPROVED);
        assertThat(approved3.pilotIds()).hasSize(1);
    }

    @Test
    void 吃水超过泊位限深直接拒绝且不产生记录() {
        var tooDeep = new WindowService.CreateCommand("REQ-DEEP", "VESSEL-DEEP", berthB,
                at("2026-10-01T10:00:00Z"), at("2026-10-01T12:00:00Z"), new BigDecimal("9.00"), 1);
        assertThatThrownBy(() -> service.create(tooDeep))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.code()).isEqualTo("DRAFT_EXCEEDS_LIMIT");
                });
        assertThat(countWindows()).isZero();
    }

    @Test
    void 申请起止须完整落在允许时段内且端点等于边界合法() {
        // 恰好等于允许时段边界：合法
        var exact = service.create(cmd("REQ-EXACT", berthA, ALLOWED_START, ALLOWED_END));
        assertThat(exact.created()).isTrue();

        // 超出允许时段：拒绝
        assertThatThrownBy(() -> service.create(cmd("REQ-OUT", berthA,
                at("2026-10-03T00:00:00Z"), at("2026-10-03T02:00:00Z"))))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.code()).isEqualTo("OUTSIDE_ALLOWED_WINDOW");
                });
        assertThatThrownBy(() -> service.create(cmd("REQ-OUT-2", berthA,
                at("2026-09-30T22:00:00Z"), at("2026-10-01T02:00:00Z"))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo("OUTSIDE_ALLOWED_WINDOW"));
    }

    @Test
    void 同键同参重放返回首次结果且不新增记录() {
        var command = cmd("REQ-IDEM", berthA, at("2026-10-01T10:00:00Z"), at("2026-10-01T12:00:00Z"));
        var first = service.create(command);
        assertThat(first.created()).isTrue();

        var replay = service.create(command);
        assertThat(replay.created()).isFalse();
        assertThat(replay.window().id()).isEqualTo(first.window().id());
        assertThat(replay.window().fingerprint()).isEqualTo(first.window().fingerprint());
        assertThat(countWindows()).isEqualTo(1);
    }

    @Test
    void 同键异参返回409且不新增记录() {
        service.create(cmd("REQ-IDEM", berthA, at("2026-10-01T10:00:00Z"), at("2026-10-01T12:00:00Z")));

        var changed = new WindowService.CreateCommand("REQ-IDEM", "VESSEL-OTHER", berthA,
                at("2026-10-01T10:00:00Z"), at("2026-10-01T12:00:00Z"), new BigDecimal("6.00"), 1);
        assertThatThrownBy(() -> service.create(changed))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo("IDEMPOTENT_KEY_CONFLICT");
                });
        assertThat(countWindows()).isEqualTo(1);
    }

    @Test
    void 指纹序列化消除字段拼接歧义() {
        // 若简单拼接，("AB","C") 与 ("A","BC") 会得到相同指纹；定长前缀必须区分
        var c1 = new WindowService.CreateCommand("AB", "C", berthA,
                at("2026-10-01T10:00:00Z"), at("2026-10-01T12:00:00Z"), new BigDecimal("6.00"), 1);
        var c2 = new WindowService.CreateCommand("A", "BC", berthA,
                at("2026-10-01T10:00:00Z"), at("2026-10-01T12:00:00Z"), new BigDecimal("6.00"), 1);
        assertThat(WindowService.fingerprint(c1)).isNotEqualTo(WindowService.fingerprint(c2));
    }

    @Test
    void 终态操作幂等且不得复活() {
        // 驳回幂等
        var w1 = service.create(cmd("REQ-1", berthA, at("2026-10-01T10:00:00Z"), at("2026-10-01T12:00:00Z")));
        service.reject(w1.window().id());
        var rejectedAgain = service.reject(w1.window().id());
        assertThat(rejectedAgain.status()).isEqualTo(WindowStatus.REJECTED);
        assertThatThrownBy(() -> service.approve(w1.window().id()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo("WINDOW_TERMINATED"));
        assertThatThrownBy(() -> service.cancel(w1.window().id()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo("WINDOW_STATE_CONFLICT"));

        // 取消幂等
        var w2 = service.create(cmd("REQ-2", berthA, at("2026-10-01T14:00:00Z"), at("2026-10-01T16:00:00Z")));
        service.cancel(w2.window().id());
        var cancelledAgain = service.cancel(w2.window().id());
        assertThat(cancelledAgain.status()).isEqualTo(WindowStatus.CANCELLED);
        assertThatThrownBy(() -> service.approve(w2.window().id()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo("WINDOW_TERMINATED"));

        // 完成幂等：批准后推进时钟至生效，登记离泊两次
        var w3 = service.create(cmd("REQ-3", berthA, at("2026-10-01T18:00:00Z"), at("2026-10-01T20:00:00Z")));
        service.approve(w3.window().id());
        clock.set(at("2026-10-01T19:00:00Z"));
        assertThat(service.get(w3.window().id()).status()).isEqualTo(WindowStatus.ACTIVE);
        service.complete(w3.window().id());
        var completedAgain = service.complete(w3.window().id());
        assertThat(completedAgain.status()).isEqualTo(WindowStatus.COMPLETED);
        // 迟到审批不得复活已完成窗口
        assertThatThrownBy(() -> service.approve(w3.window().id()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo("WINDOW_TERMINATED"));
    }

    @Test
    void 到达起始时刻自动生效() {
        var created = service.create(cmd("REQ-1", berthA, at("2026-10-01T10:00:00Z"), at("2026-10-01T12:00:00Z")));
        service.approve(created.window().id());
        assertThat(service.get(created.window().id()).status()).isEqualTo(WindowStatus.APPROVED);

        clock.set(at("2026-10-01T10:00:00Z"));
        assertThat(service.get(created.window().id()).status()).isEqualTo(WindowStatus.ACTIVE);
    }

    @Test
    void 已批准窗口改期重新走完整冲突校验且与自身不算冲突() {
        var w1 = service.create(cmd("REQ-1", berthA, at("2026-10-01T10:00:00Z"), at("2026-10-01T12:00:00Z")));
        var w2 = service.create(cmd("REQ-2", berthA, at("2026-10-01T14:00:00Z"), at("2026-10-01T16:00:00Z")));
        service.approve(w1.window().id());
        service.approve(w2.window().id());

        // 改期撞上 w2：拒绝
        assertThatThrownBy(() -> service.reschedule(w1.window().id(),
                at("2026-10-01T15:00:00Z"), at("2026-10-01T17:00:00Z")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo("BERTH_WINDOW_CONFLICT"));

        // 改期到允许时段之外：拒绝
        assertThatThrownBy(() -> service.reschedule(w1.window().id(),
                at("2026-10-03T10:00:00Z"), at("2026-10-03T12:00:00Z")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo("OUTSIDE_ALLOWED_WINDOW"));

        // 改期后与自身原时段重叠：合法（与自身不算冲突）
        var moved = service.reschedule(w1.window().id(),
                at("2026-10-01T11:00:00Z"), at("2026-10-01T13:00:00Z"));
        assertThat(moved.status()).isEqualTo(WindowStatus.APPROVED);
        assertThat(moved.startAt()).isEqualTo(at("2026-10-01T11:00:00Z"));
        assertThat(moved.endAt()).isEqualTo(at("2026-10-01T13:00:00Z"));
        assertThat(moved.pilotIds()).hasSize(1);

        // 原时段已释放，其他窗口可使用
        var w3 = service.create(cmd("REQ-3", berthA, at("2026-10-01T09:00:00Z"), at("2026-10-01T10:00:00Z")));
        assertThat(w3.created()).isTrue();
    }

    @Test
    void 不存在的泊位与窗口返回404() {
        assertThatThrownBy(() -> service.create(cmd("REQ-X", 999L,
                at("2026-10-01T10:00:00Z"), at("2026-10-01T12:00:00Z"))))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.code()).isEqualTo("BERTH_NOT_FOUND");
                });
        assertThatThrownBy(() -> service.approve(999L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo("WINDOW_NOT_FOUND"));
    }
}
