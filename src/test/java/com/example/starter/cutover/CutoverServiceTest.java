package com.example.starter.cutover;

import static com.example.starter.cutover.CutoverFixtures.DEVICE_SW1;
import static com.example.starter.cutover.CutoverFixtures.steps;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.cutover.dto.ActionResult;
import com.example.starter.cutover.dto.ApproveRequest;
import com.example.starter.cutover.dto.CreateCutoverRequest;
import com.example.starter.cutover.dto.ExpireCheckRequest;
import com.example.starter.cutover.dto.RequestView;
import com.example.starter.cutover.dto.SealRequest;
import com.example.starter.cutover.dto.StartExecutionRequest;
import com.example.starter.cutover.dto.StepReportRequest;
import com.example.starter.cutover.repo.CutoverRepository;
import com.example.starter.cutover.service.CutoverService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 割接主流程与失败分支业务测试（真实 H2 MySQL 兼容库，无 mock）。
 */
@SpringBootTest
class CutoverServiceTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 1, 2, 0);

    @Autowired
    private CutoverService service;
    @Autowired
    private CutoverRepository repo;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM cutover_event");
        jdbc.update("DELETE FROM cutover_step");
        jdbc.update("DELETE FROM cutover_request");
    }

    private CreateCutoverRequest req(String id, Priority p, LocalDateTime s, LocalDateTime e) {
        return new CreateCutoverRequest(id, "割接-" + id, DEVICE_SW1, p, s, e,
                steps("停流量", "NORMAL", "配置下发", "NORMAL",
                        "关键验证", "CRITICAL", "硬性检查", "HARDCHECK"));
    }

    private ActionResult prepareApproved(String id, LocalDateTime s, LocalDateTime e, Priority p) {
        service.create(req(id, p, s, e));
        service.analyze(id);
        return service.approve(new ApproveRequest("uid-approve-" + id, id, true, "同意"));
    }

    private ActionResult prepareExecuting(String id, LocalDateTime s, LocalDateTime e, Priority p) {
        prepareApproved(id, s, e, p);
        return service.startExecution(new StartExecutionRequest("uid-exec-" + id, id, s));
    }

    private StepReportRequest report(String uid, String id, int seq, boolean ok, LocalDateTime at) {
        return new StepReportRequest(uid, id, seq, ok, at, null);
    }

    @Test
    @DisplayName("主流程：申请->影响分析(列出全部关联业务)->审批->执行->逐条回报->验证->封板")
    void happyFlow() {
        LocalDateTime end = BASE.plusHours(4);
        service.create(req("R-HAPPY", Priority.MEDIUM, BASE, end));

        RequestView submitted = service.get("R-HAPPY");
        assertThat(submitted.status()).isEqualTo("SUBMITTED");
        assertThat(submitted.steps()).hasSize(4);

        ActionResult analyzed = service.analyze("R-HAPPY");
        assertThat(analyzed.replayed()).isFalse();
        // DEV-CORE-SW-01 关联 支付/订单/风控 三个业务系统，影响分析必须列全。
        assertThat(analyzed.request().impactSystems())
                .containsExactly("BIZ-ORDER", "BIZ-PAY", "BIZ-RISK");
        assertThat(analyzed.request().status()).isEqualTo("IMPACT_ANALYZED");

        service.approve(new ApproveRequest("u-ap1", "R-HAPPY", true, null));
        service.startExecution(new StartExecutionRequest("u-ex1", "R-HAPPY", BASE));

        assertThat(service.report(report("u-r1", "R-HAPPY", 1, true, BASE.plusMinutes(5)))
                .request().status()).isEqualTo("EXECUTING");
        assertThat(service.report(report("u-r2", "R-HAPPY", 2, true, BASE.plusMinutes(10)))
                .request().status()).isEqualTo("EXECUTING");
        assertThat(service.report(report("u-r3", "R-HAPPY", 3, true, BASE.plusMinutes(18)))
                .request().status()).isEqualTo("EXECUTING");
        ActionResult last = service.report(report("u-r4", "R-HAPPY", 4, true, BASE.plusMinutes(25)));
        assertThat(last.request().status()).isEqualTo("VERIFIED");

        ActionResult seal = service.seal(new SealRequest("u-seal1", "R-HAPPY"));
        assertThat(seal.request().status()).isEqualTo("SEALED");

        // 事件与回退判据记录可复算查询。
        assertThat(service.events("R-HAPPY"))
                .extracting(e -> e.eventType())
                .contains("APPROVE", "EXECUTE_START", "STEP_REPORT", "SEAL");
    }

    @Test
    @DisplayName("窗口半开区间：端点相接不冲突，区间重叠才冲突")
    void halfOpenWindowConflict() {
        // A [02:00,03:00) 已批准
        prepareApproved("R-A", BASE, BASE.plusHours(1), Priority.MEDIUM);
        // B [03:00,04:00) 与 A 端点相接，合法并可批准
        prepareApproved("R-B", BASE.plusHours(1), BASE.plusHours(2), Priority.MEDIUM);
        assertThat(service.get("R-B").status()).isEqualTo("APPROVED");

        // C [02:30,03:30) 与 A、B 均重叠，低/同级 -> 拒绝
        ActionResult c = service.create(req("R-C", Priority.MEDIUM,
                BASE.plusMinutes(30), BASE.plusMinutes(90)));
        assertThat(c.request().status()).isEqualTo("REJECTED");
        assertThat(c.request().rejectReason()).contains("冲突");
        // 拒绝的申请不产生步骤记录
        assertThat(repo.listSteps("R-C")).isEmpty();
    }

    @Test
    @DisplayName("优先级抢占：同窗口高优先级申请通过，在途低优先级被抢占拒绝；低对高再申请被拒")
    void priorityPreemption() {
        prepareApproved("R-LOW", BASE, BASE.plusHours(2), Priority.LOW);

        ActionResult high = service.create(req("R-HIGH", Priority.HIGH,
                BASE.plusMinutes(10), BASE.plusMinutes(100)));
        assertThat(high.request().status()).isEqualTo("SUBMITTED");

        RequestView lowAfter = service.get("R-LOW");
        assertThat(lowAfter.status()).isEqualTo("REJECTED");
        assertThat(lowAfter.rejectReason()).contains("抢占");

        // 再来一个 MEDIUM 与 HIGH 重叠 -> 拒绝
        ActionResult mid = service.create(req("R-MID", Priority.MEDIUM,
                BASE.plusMinutes(20), BASE.plusMinutes(110)));
        assertThat(mid.request().status()).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("窗口最长 4 小时：恰 240 分钟合法，241 分钟拒绝")
    void windowExactlyFourHours() {
        ActionResult exact = service.create(req("R-240", Priority.LOW, BASE, BASE.plusMinutes(240)));
        assertThat(exact.request().status()).isEqualTo("SUBMITTED");

        assertThatThrownBy(() ->
                service.create(req("R-241", Priority.LOW, BASE, BASE.plusMinutes(241))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("240");
    }

    @Test
    @DisplayName("关键验证点恰满 10 分钟未通过 -> 自动回退，后续步骤冻结并生成回退步骤")
    void criticalVerificationExactTimeout() {
        LocalDateTime end = BASE.plusHours(2);
        prepareExecuting("R-T", BASE, end, Priority.MEDIUM);

        service.report(report("t-r1", "R-T", 1, true, BASE.plusMinutes(5)));
        // 关键验证点 seq=3，其上一步 seq=2 在 02:10 完成；恰 10 分钟后 02:20 回报通过仍判超时
        service.report(report("t-r2", "R-T", 2, true, BASE.plusMinutes(10)));
        ActionResult timed = service.report(report("t-r3", "R-T", 3, true, BASE.plusMinutes(20)));

        assertThat(timed.request().status()).isEqualTo("ROLLED_BACK");
        assertThat(timed.request().rollbackCause()).isEqualTo("CRITICAL_TIMEOUT");

        var steps = repo.listSteps("R-T");
        assertThat(steps).filteredOn(s -> s.getStatus().equals("FROZEN")).hasSize(1); // seq4 冻结
        assertThat(steps).anySatisfy(s -> {
            assertThat(s.getStepType()).isEqualTo("ROLLBACK");
            assertThat(s.getStatus()).isEqualTo("SUCCESS");
        });
        assertThat(service.events("R-T"))
                .extracting(e -> e.eventType()).contains("ROLLBACK_TRIGGER");

        // 冻结后继续回报后续步骤 -> 拒绝
        assertThatThrownBy(() -> service.report(report("t-r4", "R-T", 4, true, BASE.plusMinutes(21))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("冻结");
    }

    @Test
    @DisplayName("关键验证点 9 分 59 秒口径以下（9 分钟）通过有效，不触发回退")
    void criticalWithinTimeoutPasses() {
        LocalDateTime end = BASE.plusHours(2);
        prepareExecuting("R-OK", BASE, end, Priority.MEDIUM);
        service.report(report("ok-r1", "R-OK", 1, true, BASE.plusMinutes(5)));
        service.report(report("ok-r2", "R-OK", 2, true, BASE.plusMinutes(10)));
        // 距上一步完成 9 分钟，通过
        ActionResult r3 = service.report(report("ok-r3", "R-OK", 3, true, BASE.plusMinutes(19)));
        assertThat(r3.request().status()).isEqualTo("EXECUTING");
    }

    @Test
    @DisplayName("验证点逾期未回报：巡检在恰满 10 分钟时按 CRITICAL_TIMEOUT 回退（窗口尚未结束）")
    void criticalNoReportTimeoutBySweep() {
        LocalDateTime end = BASE.plusHours(2);
        prepareExecuting("R-SWEEP", BASE, end, Priority.MEDIUM);
        service.report(report("sw-r1", "R-SWEEP", 1, true, BASE.plusMinutes(5)));
        service.report(report("sw-r2", "R-SWEEP", 2, true, BASE.plusMinutes(10)));

        // 距验证点开始（02:10）9 分钟巡检：不动作
        ActionResult early = service.expireCheck(
                new ExpireCheckRequest("sw-e1", "R-SWEEP", BASE.plusMinutes(19)));
        assertThat(early.request().status()).isEqualTo("EXECUTING");

        // 恰满 10 分钟（02:20）仍未回报 -> CRITICAL_TIMEOUT 回退，即使窗口未结束
        ActionResult timed = service.expireCheck(
                new ExpireCheckRequest("sw-e2", "R-SWEEP", BASE.plusMinutes(20)));
        assertThat(timed.request().status()).isEqualTo("ROLLED_BACK");
        assertThat(timed.request().rollbackCause()).isEqualTo("CRITICAL_TIMEOUT");
        assertThat(repo.countUnfinishedSteps("R-SWEEP")).isZero();
    }

    @Test
    @DisplayName("任一硬性检查失败 -> HARDCHECK_FAILED 回退，同事务冻结全部未完成步骤")
    void hardcheckFailureFreezesAtomically() {
        LocalDateTime end = BASE.plusHours(2);
        prepareExecuting("R-HC", BASE, end, Priority.MEDIUM);
        service.report(report("hc-r1", "R-HC", 1, true, BASE.plusMinutes(5)));
        service.report(report("hc-r2", "R-HC", 2, true, BASE.plusMinutes(10)));
        service.report(report("hc-r3", "R-HC", 3, true, BASE.plusMinutes(15)));
        ActionResult failed = service.report(report("hc-r4", "R-HC", 4, false, BASE.plusMinutes(20)));

        assertThat(failed.request().status()).isEqualTo("ROLLED_BACK");
        assertThat(failed.request().rollbackCause()).isEqualTo("HARDCHECK_FAILED");

        // 原子性：不存在任何 PENDING/RUNNING 残留步骤
        assertThat(repo.countUnfinishedSteps("R-HC")).isZero();
        var statuses = repo.listSteps("R-HC").stream()
                .map(s -> s.getStatus()).toList();
        assertThat(statuses).doesNotContain("PENDING", "RUNNING");
    }

    @Test
    @DisplayName("窗口终点口径：恰在窗口终点完成有效；窗口结束仍有未完成步骤 -> WINDOW_EXPIRED 强制回退")
    void windowEndpointSemantics() {
        LocalDateTime end = BASE.plusHours(1);
        prepareExecuting("R-END", BASE, end, Priority.MEDIUM);
        service.report(report("e-r1", "R-END", 1, true, BASE.plusMinutes(40)));
        service.report(report("e-r2", "R-END", 2, true, BASE.plusMinutes(50)));
        service.report(report("e-r3", "R-END", 3, true, BASE.plusMinutes(55)));
        // 恰在窗口终点 03:00 回报最后一步：有效
        ActionResult last = service.report(report("e-r4", "R-END", 4, true, end));
        assertThat(last.request().status()).isEqualTo("VERIFIED");

        ActionResult check = service.expireCheck(
                new ExpireCheckRequest("u-exp-ok", "R-END", end));
        assertThat(check.message()).contains("验证通过");
        assertThat(service.get("R-END").status()).isEqualTo("VERIFIED");

        // 另一个割接：窗口结束仍剩两步未完成 -> 超期强制回退
        prepareExecuting("R-EXP", BASE, end, Priority.LOW);
        service.report(report("x-r1", "R-EXP", 1, true, BASE.plusMinutes(30)));
        ActionResult expired = service.expireCheck(
                new ExpireCheckRequest("u-exp-1", "R-EXP", end));
        assertThat(expired.request().status()).isEqualTo("WINDOW_EXPIRED");
        assertThat(expired.request().rollbackCause()).isEqualTo("WINDOW_EXPIRED");
        assertThat(repo.countUnfinishedSteps("R-EXP")).isZero();

        // 恰终点之前一刻巡检不判超期
        prepareExecuting("R-EXP2", BASE.plusHours(3), BASE.plusHours(4), Priority.LOW);
        ActionResult before = service.expireCheck(new ExpireCheckRequest("u-exp-2", "R-EXP2",
                BASE.plusHours(4).minusMinutes(1)));
        assertThat(before.message()).contains("尚未结束");
        assertThat(service.get("R-EXP2").status()).isEqualTo("EXECUTING");
    }

    @Test
    @DisplayName("回报晚于窗口终点（非恰点）-> 强制回退 WINDOW_EXPIRED")
    void reportAfterWindowEndRollsBack() {
        LocalDateTime end = BASE.plusHours(1);
        prepareExecuting("R-LATE", BASE, end, Priority.MEDIUM);
        ActionResult r = service.report(report("l-r1", "R-LATE", 1, true, end.plusSeconds(1)));
        assertThat(r.request().status()).isEqualTo("WINDOW_EXPIRED");
    }

    @Test
    @DisplayName("同键重放幂等：申请/审批/回报同键同参返回首次结果；异参 409 且不产生新步骤")
    void idempotentReplayAndConflict() {
        LocalDateTime end = BASE.plusHours(2);
        CreateCutoverRequest first = req("R-IDEM", Priority.MEDIUM, BASE, end);
        service.create(first);
        ActionResult replay = service.create(req("R-IDEM", Priority.MEDIUM, BASE, end));
        assertThat(replay.replayed()).isTrue();
        assertThat(repo.listSteps("R-IDEM")).hasSize(4); // 无重复步骤

        // 同 requestId 异参 -> 409
        assertThatThrownBy(() -> service.create(
                new CreateCutoverRequest("R-IDEM", "割接-R-IDEM", DEVICE_SW1, Priority.HIGH,
                        BASE, end, steps("a", "NORMAL"))))
                .isInstanceOf(ConflictException.class);

        service.analyze("R-IDEM");
        ActionResult approve1 = service.approve(
                new ApproveRequest("u-ap", "R-IDEM", true, null));
        ActionResult approve2 = service.approve(
                new ApproveRequest("u-ap", "R-IDEM", true, null));
        assertThat(approve1.replayed()).isFalse();
        assertThat(approve2.replayed()).isTrue();

        // 同审批键异参 -> 409
        assertThatThrownBy(() -> service.approve(
                new ApproveRequest("u-ap", "R-IDEM", false, null)))
                .isInstanceOf(ConflictException.class);

        service.startExecution(new StartExecutionRequest("u-ex", "R-IDEM", BASE));
        service.report(report("u-s1", "R-IDEM", 1, true, BASE.plusMinutes(5)));
        ActionResult reportReplay = service.report(
                report("u-s1", "R-IDEM", 1, true, BASE.plusMinutes(5)));
        assertThat(reportReplay.replayed()).isTrue();
        // 同回报键异参 -> 409，步骤时序不被篡改
        assertThatThrownBy(() -> service.report(
                report("u-s1", "R-IDEM", 1, false, BASE.plusMinutes(5))))
                .isInstanceOf(ConflictException.class);
        assertThat(repo.listSteps("R-IDEM").get(0).getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("封板终态：重复封板幂等成功；封板后补报只记迟到事件，终态保持 SEALED")
    void sealedIsTerminalWithLateEvents() {
        LocalDateTime end = BASE.plusHours(2);
        prepareExecuting("R-SEAL", BASE, end, Priority.MEDIUM);
        for (int i = 1; i <= 4; i++) {
            service.report(report("s-r" + i, "R-SEAL", i, true, BASE.plusMinutes(5L * i)));
        }
        service.seal(new SealRequest("u-seal-a", "R-SEAL"));

        ActionResult again = service.seal(new SealRequest("u-seal-b", "R-SEAL"));
        assertThat(again.replayed()).isTrue();
        assertThat(again.request().status()).isEqualTo("SEALED");

        // 封板后补报（哪怕是失败）：只记迟到事件，不回退、不改终态
        ActionResult late = service.report(
                report("u-late-1", "R-SEAL", 2, false, BASE.plusMinutes(90)));
        assertThat(late.message()).contains("迟到");
        assertThat(service.get("R-SEAL").status()).isEqualTo("SEALED");
        assertThat(service.events("R-SEAL"))
                .filteredOn(e -> e.eventType().equals("LATE_REPORT"))
                .hasSize(1)
                .allMatch(e -> e.lateFlag());
    }

    @Test
    @DisplayName("回退终态后封板被拒绝，保证唯一终态")
    void cannotSealAfterRollback() {
        LocalDateTime end = BASE.plusHours(2);
        prepareExecuting("R-RB", BASE, end, Priority.MEDIUM);
        service.report(report("rb-r1", "R-RB", 1, false, BASE.plusMinutes(5)));
        assertThat(service.get("R-RB").status()).isEqualTo("ROLLED_BACK");
        assertThatThrownBy(() -> service.seal(new SealRequest("u-seal-rb", "R-RB")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("终态唯一");
    }

    @Test
    @DisplayName("步骤必须按序回报，跳序被拒绝")
    void sequentialReportingEnforced() {
        LocalDateTime end = BASE.plusHours(2);
        prepareExecuting("R-SEQ", BASE, end, Priority.MEDIUM);
        assertThatThrownBy(() -> service.report(report("q-2", "R-SEQ", 2, true, BASE.plusMinutes(5))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("按序");
    }

    @Test
    @DisplayName("未完成影响分析不能审批通过")
    void approveRequiresImpactAnalysis() {
        service.create(req("R-NIA", Priority.LOW, BASE.plusHours(5), BASE.plusHours(6)));
        assertThatThrownBy(() -> service.approve(
                new ApproveRequest("u-nia", "R-NIA", true, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("影响分析");
    }

    @Test
    @DisplayName("影响分析可复算：重复调用返回幂等结果且清单不变")
    void impactAnalysisRecomputable() {
        service.create(req("R-RE", Priority.LOW, BASE.plusHours(6), BASE.plusHours(7)));
        List<String> first = service.analyze("R-RE").request().impactSystems();
        ActionResult again = service.analyze("R-RE");
        assertThat(again.replayed()).isTrue();
        assertThat(again.request().impactSystems()).isEqualTo(first);
    }
}
