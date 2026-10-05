package com.example.starter.cutover;

import static com.example.starter.cutover.CutoverFixtures.steps;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.cutover.dto.ActionResult;
import com.example.starter.cutover.dto.ApproveRequest;
import com.example.starter.cutover.dto.BatchActionResult;
import com.example.starter.cutover.dto.BatchEdgeRef;
import com.example.starter.cutover.dto.BatchEdgeView;
import com.example.starter.cutover.dto.BatchMemberRef;
import com.example.starter.cutover.dto.BatchView;
import com.example.starter.cutover.dto.BlockDetailView;
import com.example.starter.cutover.dto.CloseBatchRequest;
import com.example.starter.cutover.dto.CreateCutoverRequest;
import com.example.starter.cutover.dto.ExpireCheckRequest;
import com.example.starter.cutover.dto.PublishBatchRequest;
import com.example.starter.cutover.dto.SealRequest;
import com.example.starter.cutover.dto.StartExecutionRequest;
import com.example.starter.cutover.dto.StepReportRequest;
import com.example.starter.cutover.repo.CutoverRepository;
import com.example.starter.cutover.service.BatchService;
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
 * 批次发布、执行门禁、阻断传播与结束批次业务测试（真实 H2 MySQL 兼容库，无 mock）。
 */
@SpringBootTest
class BatchServiceTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 3, 2, 0);
    private static final LocalDateTime END = BASE.plusHours(2);

    private static final String DEV_A = "DEV-DB-01";
    private static final String DEV_B = "DEV-FW-01";
    private static final String DEV_C = "DEV-CORE-SW-02";
    private static final String DEV_D = "DEV-CORE-SW-01";

    @Autowired
    private CutoverService cutoverService;
    @Autowired
    private BatchService batchService;
    @Autowired
    private CutoverRepository repo;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM cutover_batch_edge");
        jdbc.update("DELETE FROM cutover_batch_member");
        jdbc.update("DELETE FROM cutover_batch");
        jdbc.update("DELETE FROM cutover_event");
        jdbc.update("DELETE FROM cutover_step");
        jdbc.update("DELETE FROM cutover_request");
    }

    // ---------------- 测试数据准备 ----------------

    /** 申请 -> 影响分析 -> 审批，返回当前版本号。 */
    private int prepareApproved(String id, String device) {
        cutoverService.create(new CreateCutoverRequest(id, "割接-" + id, device,
                Priority.MEDIUM, BASE, END,
                steps("停流量", "NORMAL", "配置下发", "NORMAL", "硬性检查", "HARDCHECK")));
        cutoverService.analyze(id);
        cutoverService.approve(new ApproveRequest("ap-" + id, id, true, null));
        return cutoverService.get(id).version();
    }

    private PublishBatchRequest publishReq(String batchId, List<BatchMemberRef> members,
                                           List<BatchEdgeRef> edges) {
        return new PublishBatchRequest(batchId, "批次-" + batchId, BASE, members, edges);
    }

    private BatchMemberRef member(String id, int version) {
        return new BatchMemberRef(id, version);
    }

    private BatchEdgeRef edge(String pred, String succ) {
        return new BatchEdgeRef(pred, succ);
    }

    /** 启动并在第一步失败，触发 STEP_FAILED 回退。 */
    private void startAndFail(String id) {
        cutoverService.startExecution(new StartExecutionRequest("ex-" + id, id, BASE));
        cutoverService.report(new StepReportRequest("rp-" + id, id, 1, false,
                BASE.plusMinutes(5), null));
    }

    /** 启动、全部步骤成功并封板。 */
    private void completeAndSeal(String id) {
        cutoverService.startExecution(new StartExecutionRequest("ex-" + id, id, BASE));
        for (int seq = 1; seq <= 3; seq++) {
            cutoverService.report(new StepReportRequest("rp-" + id + "-" + seq, id, seq, true,
                    BASE.plusMinutes(5L * seq), null));
        }
        cutoverService.seal(new SealRequest("seal-" + id, id));
    }

    // ---------------- 发布主流程与拓扑查询 ----------------

    @Test
    @DisplayName("发布主流程：原子冻结成员/依赖/窗口快照，拓扑按申请编号稳定升序")
    void publishHappyPathAndTopologySorted() {
        int va = prepareApproved("B-A", DEV_A);
        int vb = prepareApproved("B-B", DEV_B);

        // 成员乱序传入，拓扑查询仍按申请编号稳定升序
        BatchActionResult result = batchService.publish(publishReq("BAT-1",
                List.of(member("B-B", vb), member("B-A", va)),
                List.of(edge("B-A", "B-B"))));

        assertThat(result.replayed()).isFalse();
        BatchView view = result.batch();
        assertThat(view.status()).isEqualTo("PUBLISHED");
        assertThat(view.version()).isEqualTo(1);
        assertThat(view.members()).extracting(m -> m.requestId())
                .containsExactly("B-A", "B-B");
        assertThat(view.members()).allSatisfy(m -> {
            assertThat(m.memberStatus()).isEqualTo("READY");
            assertThat(m.windowStart()).isEqualTo(BASE);
            assertThat(m.windowEnd()).isEqualTo(END);
            assertThat(m.blockSources()).isEmpty();
        });
        // 版本快照为发布时申请版本
        assertThat(view.members().get(0).requestVersion()).isEqualTo(va);
        assertThat(view.members().get(1).requestVersion()).isEqualTo(vb);
        assertThat(view.edges()).containsExactly(new BatchEdgeView("B-A", "B-B"));

        BatchView queried = batchService.get("BAT-1");
        assertThat(queried.members()).extracting(m -> m.requestId())
                .containsExactly("B-A", "B-B");
        assertThat(batchService.blocks("BAT-1")).isEmpty();
    }

    @Test
    @DisplayName("发布校验：重复成员/自环/边端点出批/环路均整批拒绝(422)")
    void publishStructuralRejections() {
        int va = prepareApproved("B-A", DEV_A);
        int vb = prepareApproved("B-B", DEV_B);

        assertThatThrownBy(() -> batchService.publish(publishReq("BAT-DUP",
                List.of(member("B-A", va), member("B-A", va)), List.of())))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("唯一");

        assertThatThrownBy(() -> batchService.publish(publishReq("BAT-SELF",
                List.of(member("B-A", va)), List.of(edge("B-A", "B-A")))))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("自环");

        assertThatThrownBy(() -> batchService.publish(publishReq("BAT-OUT",
                List.of(member("B-A", va)), List.of(edge("B-A", "B-B")))))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("都在批内");

        assertThatThrownBy(() -> batchService.publish(publishReq("BAT-CYCLE",
                List.of(member("B-A", va), member("B-B", vb)),
                List.of(edge("B-A", "B-B"), edge("B-B", "B-A")))))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("环路");
    }

    @Test
    @DisplayName("发布校验：成员不存在404、未批准/已开始/版本不符422，且失败不占键")
    void publishStateRejectionsAndKeyNotBurned() {
        int va = prepareApproved("B-A", DEV_A);

        // 成员不存在 -> 404
        int finalVa = va;
        assertThatThrownBy(() -> batchService.publish(publishReq("BAT-MISS",
                List.of(member("B-A", finalVa), member("B-NONE", 1)), List.of())))
                .isInstanceOf(NotFoundException.class);

        // 版本不符 -> 422
        assertThatThrownBy(() -> batchService.publish(publishReq("BAT-VER",
                List.of(member("B-A", finalVa + 1)), List.of())))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("版本不符");

        // 未批准（仅提交） -> 422
        cutoverService.create(new CreateCutoverRequest("B-PEND", "割接-B-PEND", DEV_B,
                Priority.MEDIUM, BASE, END, steps("s1", "NORMAL")));
        int pendVersion = cutoverService.get("B-PEND").version();
        assertThatThrownBy(() -> batchService.publish(publishReq("BAT-PEND",
                List.of(member("B-PEND", pendVersion)), List.of())))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("APPROVED");

        // 已开始 -> 422
        int vb = prepareApproved("B-B", DEV_C);
        cutoverService.startExecution(new StartExecutionRequest("ex-B-B", "B-B", BASE));
        assertThatThrownBy(() -> batchService.publish(publishReq("BAT-STARTED",
                List.of(member("B-B", vb)), List.of())))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("APPROVED");

        // 失败不占键：修正参数后同 requestId 可成功发布
        BatchActionResult ok = batchService.publish(publishReq("BAT-VER",
                List.of(member("B-A", finalVa)), List.of()));
        assertThat(ok.replayed()).isFalse();
        assertThat(ok.batch().status()).isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName("发布幂等：同键同参重放首次冻结结果，同键异参409；一申请只能属于一个未结束批次")
    void publishIdempotentAndSingleOpenBatch() {
        int va = prepareApproved("B-A", DEV_A);
        PublishBatchRequest req = publishReq("BAT-1", List.of(member("B-A", va)), List.of());

        BatchActionResult first = batchService.publish(req);
        assertThat(first.replayed()).isFalse();

        BatchActionResult replay = batchService.publish(req);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.batch().members()).hasSize(1);

        // 同键异参（批次名不同） -> 409
        PublishBatchRequest mutated = new PublishBatchRequest("BAT-1", "另一个名称", BASE,
                List.of(member("B-A", va)), List.of());
        assertThatThrownBy(() -> batchService.publish(mutated))
                .isInstanceOf(ConflictException.class);

        // 同键异参（集合不同：含分隔符文本的成员集合变化） -> 409
        int vb = prepareApproved("B-B", DEV_B);
        PublishBatchRequest mutatedMembers = new PublishBatchRequest("BAT-1", "批次-BAT-1",
                BASE, List.of(member("B-A", va), member("B-B", vb)), List.of());
        assertThatThrownBy(() -> batchService.publish(mutatedMembers))
                .isInstanceOf(ConflictException.class);

        // 一项申请最多属于一个未结束批次
        assertThatThrownBy(() -> batchService.publish(publishReq("BAT-2",
                List.of(member("B-A", cutoverService.get("B-A").version())), List.of())))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("未结束批次");
    }

    // ---------------- 执行门禁 ----------------

    @Test
    @DisplayName("执行门禁：前置未封板返回等待且不写步骤/事件；前置封板后放行并置STARTED")
    void startGateWaitsUntilPrerequisiteSealed() {
        int va = prepareApproved("B-A", DEV_A);
        int vb = prepareApproved("B-B", DEV_B);
        batchService.publish(publishReq("BAT-1",
                List.of(member("B-A", va), member("B-B", vb)),
                List.of(edge("B-A", "B-B"))));

        // 前置未开始：B 启动返回等待，状态不变、无步骤/事件写入
        ActionResult wait1 = cutoverService.startExecution(
                new StartExecutionRequest("ex-B-B", "B-B", BASE));
        assertThat(wait1.message()).contains("等待");
        assertThat(wait1.request().status()).isEqualTo("APPROVED");
        assertThat(repo.listSteps("B-B")).allSatisfy(s -> {
            assertThat(s.getStatus()).isEqualTo("PENDING");
            assertThat(s.getStartedAt()).isNull();
        });
        assertThat(cutoverService.events("B-B"))
                .noneMatch(e -> "EXECUTE_START".equals(e.eventType()));

        // 前置执行中：仍返回等待
        cutoverService.startExecution(new StartExecutionRequest("ex-B-A", "B-A", BASE));
        ActionResult wait2 = cutoverService.startExecution(
                new StartExecutionRequest("ex-B-B", "B-B", BASE));
        assertThat(wait2.message()).contains("等待");
        assertThat(wait2.request().status()).isEqualTo("APPROVED");

        // 前置验证通过并封板后：放行，成员置 STARTED
        for (int seq = 1; seq <= 3; seq++) {
            cutoverService.report(new StepReportRequest("rp-B-A-" + seq, "B-A", seq, true,
                    BASE.plusMinutes(5L * seq), null));
        }
        cutoverService.seal(new SealRequest("seal-B-A", "B-A"));

        ActionResult go = cutoverService.startExecution(
                new StartExecutionRequest("ex-B-B", "B-B", BASE));
        assertThat(go.request().status()).isEqualTo("EXECUTING");
        BatchView view = batchService.get("BAT-1");
        assertThat(view.members().get(1).memberStatus()).isEqualTo("STARTED");
    }

    @Test
    @DisplayName("未绑定批次的旧单申请行为不变：无门禁直接启动")
    void unboundLegacyRequestUnchanged() {
        prepareApproved("B-FREE", DEV_A);
        ActionResult result = cutoverService.startExecution(
                new StartExecutionRequest("ex-B-FREE", "B-FREE", BASE));
        assertThat(result.request().status()).isEqualTo("EXECUTING");
    }

    // ---------------- 阻断传播 ----------------

    @Test
    @DisplayName("阻断传播：前置回退同事务阻断全部可达未开始后继，保留首个阻断来源，无关支路继续")
    void blockPropagationChainAndUnrelatedBranch() {
        int va = prepareApproved("B-A", DEV_A);
        int vb = prepareApproved("B-B", DEV_B);
        int vc = prepareApproved("B-C", DEV_C);
        int vd = prepareApproved("B-D", DEV_D);
        // A -> B -> C，D 为无关支路
        batchService.publish(publishReq("BAT-1",
                List.of(member("B-A", va), member("B-B", vb), member("B-C", vc),
                        member("B-D", vd)),
                List.of(edge("B-A", "B-B"), edge("B-B", "B-C"))));

        startAndFail("B-A");
        assertThat(cutoverService.get("B-A").status()).isEqualTo("ROLLED_BACK");

        // B、C 同事务被阻断；D 不受影响
        BatchView view = batchService.get("BAT-1");
        assertThat(view.members().get(0).memberStatus()).isEqualTo("STARTED");
        assertThat(view.members().get(1).memberStatus()).isEqualTo("BLOCKED");
        assertThat(view.members().get(1).blockSources()).containsExactly("B-A");
        assertThat(view.members().get(2).memberStatus()).isEqualTo("BLOCKED");
        assertThat(view.members().get(2).blockSources()).containsExactly("B-B");
        assertThat(view.members().get(3).memberStatus()).isEqualTo("READY");

        // 阻断明细入口
        List<BlockDetailView> blocks = batchService.blocks("BAT-1");
        assertThat(blocks).extracting(b -> b.requestId()).containsExactly("B-B", "B-C");
        assertThat(blocks.get(0).blockSources()).containsExactly("B-A");
        assertThat(blocks.get(1).blockSources()).containsExactly("B-B");

        // 被阻断成员启动 -> 422
        assertThatThrownBy(() -> cutoverService.startExecution(
                new StartExecutionRequest("ex-B-B", "B-B", BASE)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("依赖阻断");

        // 无关支路 D 可正常启动
        ActionResult go = cutoverService.startExecution(
                new StartExecutionRequest("ex-B-D", "B-D", BASE));
        assertThat(go.request().status()).isEqualTo("EXECUTING");
    }

    @Test
    @DisplayName("首个阻断来源集合保留：第二前置随后回退不覆盖已阻断成员的来源")
    void firstBlockSourcesRetained() {
        int va = prepareApproved("B-A", DEV_A);
        int vb = prepareApproved("B-B", DEV_B);
        int vc = prepareApproved("B-C", DEV_C);
        // A -> C, B -> C：A、B 均为 C 的直接前置
        batchService.publish(publishReq("BAT-1",
                List.of(member("B-A", va), member("B-B", vb), member("B-C", vc)),
                List.of(edge("B-A", "B-C"), edge("B-B", "B-C"))));

        startAndFail("B-A");
        assertThat(batchService.get("BAT-1").members().get(2).blockSources())
                .containsExactly("B-A");

        // B 随后回退：C 已阻断，首个来源集合不被覆盖
        startAndFail("B-B");
        assertThat(batchService.get("BAT-1").members().get(2).blockSources())
                .containsExactly("B-A");
    }

    @Test
    @DisplayName("前置窗口超期同样同事务传播阻断")
    void windowExpiryPropagatesBlock() {
        int va = prepareApproved("B-A", DEV_A);
        int vb = prepareApproved("B-B", DEV_B);
        batchService.publish(publishReq("BAT-1",
                List.of(member("B-A", va), member("B-B", vb)),
                List.of(edge("B-A", "B-B"))));

        cutoverService.startExecution(new StartExecutionRequest("ex-B-A", "B-A", BASE));
        // 窗口结束仍有未完成步骤 -> WINDOW_EXPIRED 并传播
        cutoverService.expireCheck(new ExpireCheckRequest("exp-B-A", "B-A", END));
        assertThat(cutoverService.get("B-A").status()).isEqualTo("WINDOW_EXPIRED");

        BatchView view = batchService.get("BAT-1");
        assertThat(view.members().get(1).memberStatus()).isEqualTo("BLOCKED");
        assertThat(view.members().get(1).blockSources()).containsExactly("B-A");
    }

    // ---------------- 结束批次与版本 ----------------

    @Test
    @DisplayName("结束批次：成员未完结/版本不符422；成功后版本+1；同键重放、异参409")
    void closeBatchFlow() {
        int va = prepareApproved("B-A", DEV_A);
        int vb = prepareApproved("B-B", DEV_B);
        batchService.publish(publishReq("BAT-1",
                List.of(member("B-A", va), member("B-B", vb)),
                List.of(edge("B-A", "B-B"))));

        // 成员未完结 -> 422
        assertThatThrownBy(() -> batchService.close(
                new CloseBatchRequest("cl-1", "BAT-1", 1, BASE.plusHours(3))))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("未完结");

        completeAndSeal("B-A");
        completeAndSeal("B-B");

        // 版本不符 -> 422
        assertThatThrownBy(() -> batchService.close(
                new CloseBatchRequest("cl-2", "BAT-1", 5, BASE.plusHours(3))))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("版本不符");

        // 正常结束：版本 1 -> 2
        BatchActionResult closed = batchService.close(
                new CloseBatchRequest("cl-3", "BAT-1", 1, BASE.plusHours(3)));
        assertThat(closed.replayed()).isFalse();
        assertThat(closed.batch().status()).isEqualTo("CLOSED");
        assertThat(closed.batch().version()).isEqualTo(2);

        // 同键同参重放
        BatchActionResult replay = batchService.close(
                new CloseBatchRequest("cl-3", "BAT-1", 1, BASE.plusHours(3)));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.batch().version()).isEqualTo(2);

        // 同键异参 -> 409
        assertThatThrownBy(() -> batchService.close(
                new CloseBatchRequest("cl-3", "BAT-1", 1, BASE.plusHours(4))))
                .isInstanceOf(ConflictException.class);

        // 批次不存在 -> 404
        assertThatThrownBy(() -> batchService.close(
                new CloseBatchRequest("cl-4", "BAT-NONE", 1, BASE.plusHours(3))))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("申请版本从1开始，成功变更加1；发布按expectedVersion校验")
    void requestVersionIncrements() {
        cutoverService.create(new CreateCutoverRequest("B-V", "割接-B-V", DEV_A,
                Priority.MEDIUM, BASE, END, steps("s1", "NORMAL")));
        assertThat(cutoverService.get("B-V").version()).isEqualTo(1);
        cutoverService.analyze("B-V");
        assertThat(cutoverService.get("B-V").version()).isEqualTo(2);
        cutoverService.approve(new ApproveRequest("ap-B-V", "B-V", true, null));
        assertThat(cutoverService.get("B-V").version()).isEqualTo(3);

        // 旧版本发布 -> 422；当前版本发布 -> 成功
        assertThatThrownBy(() -> batchService.publish(publishReq("BAT-V",
                List.of(member("B-V", 2)), List.of())))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("版本不符");
        BatchActionResult ok = batchService.publish(publishReq("BAT-V",
                List.of(member("B-V", 3)), List.of()));
        assertThat(ok.batch().members().get(0).requestVersion()).isEqualTo(3);
    }
}
