package com.example.starter.rolling;

import com.example.starter.rolling.api.dto.FreezeRequest;
import com.example.starter.rolling.api.dto.IssueRequest;
import com.example.starter.rolling.api.dto.ScheduleCreateRequest;
import com.example.starter.rolling.api.dto.ScheduleView;
import com.example.starter.rolling.domain.ScheduleStatus;
import com.example.starter.rolling.service.ConflictException;
import com.example.starter.rolling.service.ValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 排产单主流程、幂等重放、版本冻结与违例整单拒绝（H2 事务边界）。
 */
class ScheduleLifecycleIntegrationTest extends AbstractRollingIntegrationTest {

    @Test
    void 编制校验下达执行冻结完整流转() {
        ScheduleView draft = service.createOrRevise(simpleOrder("req-1", "L1",
                items(item("C1", 1250, "3", true, 0), item("C2", 1200, "3", false, 1))));
        assertThat(draft.status()).isEqualTo(ScheduleStatus.DRAFT.name());
        assertThat(draft.versionNo()).isEqualTo(1);
        assertThat(draft.items()).hasSize(2);
        assertThat(draft.items().get(0).warmup()).isTrue();

        ScheduleView validated = service.validate(draft.id());
        assertThat(validated.status()).isEqualTo(ScheduleStatus.VALIDATED.name());

        ScheduleView issued = service.issue(draft.id(), new IssueRequest("req-issue-1"));
        assertThat(issued.status()).isEqualTo(ScheduleStatus.ISSUED.name());
        assertThat(issued.effective()).isTrue();

        var actual = service.reportActual(actual("req-act-1", "L1", "C1", 1, "3"));
        assertThat(actual.accepted()).isTrue();

        ScheduleView executing = service.get(draft.id());
        assertThat(executing.status()).isEqualTo(ScheduleStatus.EXECUTING.name());

        ScheduleView frozen = service.freeze(draft.id(), new FreezeRequest("req-freeze-1"));
        assertThat(frozen.status()).isEqualTo(ScheduleStatus.FROZEN.name());
        assertThat(frozen.frozenAt()).isNotNull();
    }

    @Test
    void 同键同参重放返回首次结果且不产生新版本() {
        var request = simpleOrder("req-replay", "L2",
                items(item("C1", 1250, "3", true, 0)));
        ScheduleView first = service.createOrRevise(request);
        ScheduleView replay = service.createOrRevise(request);

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.versionNo()).isEqualTo(1);
        Integer orderCount = jdbc.queryForObject(
                "SELECT COUNT(1) FROM schedule_order WHERE request_id = ?", Integer.class, "req-replay");
        assertThat(orderCount).isEqualTo(1);
    }

    @Test
    void 同键异参返回409且不产生新版本() {
        service.createOrRevise(simpleOrder("req-conflict", "L3",
                items(item("C1", 1250, "3", true, 0))));

        var differentParams = simpleOrder("req-conflict", "L3",
                items(item("C1", 1250, "3", true, 0), item("C2", 1200, "2", false, 1)));
        assertThatThrownBy(() -> service.createOrRevise(differentParams))
                .isInstanceOf(ConflictException.class);

        Integer orderCount = jdbc.queryForObject(
                "SELECT COUNT(1) FROM schedule_order", Integer.class);
        Integer itemCount = jdbc.queryForObject("SELECT COUNT(1) FROM schedule_item", Integer.class);
        assertThat(orderCount).isEqualTo(1);
        assertThat(itemCount).isEqualTo(1);
    }

    @Test
    void 下达后修改生成新版本且新版本生效旧版本失效() {
        ScheduleView v1 = draftThenValidateThenIssue("req-v1", "L4",
                items(item("C1", 1250, "3", true, 0)));

        var reviseRequest = createRequest("req-v2", "L4", "U1", v1.id(),
                items(item("C1", 1250, "2", true, 0), item("C2", 1200, "2", true, 1)),
                List.of());
        ScheduleView v2Draft = service.createOrRevise(reviseRequest);
        assertThat(v2Draft.versionNo()).isEqualTo(2);
        assertThat(v2Draft.baseVersionId()).isEqualTo(v1.id());
        assertThat(v2Draft.effective()).isFalse();

        service.validate(v2Draft.id());
        ScheduleView v2 = service.issue(v2Draft.id(), new IssueRequest("req-v2-issue"));
        assertThat(v2.effective()).isTrue();

        ScheduleView oldV1 = service.get(v1.id());
        assertThat(oldV1.effective()).isFalse();
        assertThat(oldV1.status()).isEqualTo(ScheduleStatus.ISSUED.name());

        assertThat(service.get(v1.id()).id()).isNotEqualTo(service.get(v2.id()).id());
    }

    @Test
    void 未下达版本不允许修改() {
        ScheduleView draft = service.createOrRevise(simpleOrder("req-draft", "L5",
                items(item("C1", 1250, "3", true, 0))));
        var revise = createRequest("req-revise", "L5", "U1", draft.id(),
                items(item("C1", 1250, "2", true, 0)), List.of());
        assertThatThrownBy(() -> service.createOrRevise(revise))
                .isInstanceOf(com.example.starter.rolling.service.BusinessException.class)
                .hasMessageContaining("仅下达后的版本允许修改");
    }

    @Test
    void 违例整单拒绝不写入任何排程数据() {
        // 跳两级 + 烫辊位第 6 块 + 超 10 公里，三种违例同时出现。
        var badRequest = simpleOrder("req-bad", "L6", items(
                item("C1", 1250, "6", false, 0),
                item("C2", 1250, "5", false, 1),
                item("C3", 1150, "1", false, 2),
                item("C4", 1100, "1", false, 3),
                item("C5", 1050, "1", false, 4),
                item("C6", 1000, "1", true, 5)));

        assertThatThrownBy(() -> service.createOrRevise(badRequest))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> {
                    var violations = ((ValidationException) ex).getViolations();
                    assertThat(violations).anyMatch(v -> v.contains("跳档不得超过一级"));
                    assertThat(violations).anyMatch(v -> v.contains("10 公里"));
                    assertThat(violations).anyMatch(v -> v.contains("前 5 块"));
                });

        assertThat(jdbc.queryForObject("SELECT COUNT(1) FROM schedule_order", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(1) FROM schedule_item", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM idempotent_record WHERE request_id = ?",
                Integer.class, "req-bad")).isZero();
    }

    @Test
    void 日终冻结为终态重复冻结幂等成功() {
        ScheduleView issued = draftThenValidateThenIssue("req-fz", "L7",
                items(item("C1", 1250, "3", true, 0)));
        ScheduleView f1 = service.freeze(issued.id(), new FreezeRequest("req-fz-1"));
        ScheduleView f2 = service.freeze(issued.id(), new FreezeRequest("req-fz-2"));

        assertThat(f1.status()).isEqualTo(ScheduleStatus.FROZEN.name());
        assertThat(f2.status()).isEqualTo(ScheduleStatus.FROZEN.name());
        assertThat(f2.frozenAt()).isEqualTo(f1.frozenAt());
    }

    @Test
    void 未校验排产单不可下达() {
        ScheduleView draft = service.createOrRevise(simpleOrder("req-noissue", "L8",
                items(item("C1", 1250, "3", true, 0))));
        assertThatThrownBy(() -> service.issue(draft.id(), new IssueRequest("req-noissue-i")))
                .isInstanceOf(com.example.starter.rolling.service.BusinessException.class)
                .hasMessageContaining("仅校验通过的排产单可下达");
    }
}
