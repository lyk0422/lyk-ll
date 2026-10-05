package com.example.starter.cutover;

import static com.example.starter.cutover.CutoverFixtures.steps;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.cutover.dto.ActionResult;
import com.example.starter.cutover.dto.ApproveRequest;
import com.example.starter.cutover.dto.BatchActionResult;
import com.example.starter.cutover.dto.BatchEdgeRef;
import com.example.starter.cutover.dto.BatchMemberRef;
import com.example.starter.cutover.dto.BatchMemberView;
import com.example.starter.cutover.dto.BatchView;
import com.example.starter.cutover.dto.BlockDetailView;
import com.example.starter.cutover.dto.CreateCutoverRequest;
import com.example.starter.cutover.dto.ExpireCheckRequest;
import com.example.starter.cutover.dto.PublishBatchRequest;
import com.example.starter.cutover.dto.SealRequest;
import com.example.starter.cutover.dto.StartExecutionRequest;
import com.example.starter.cutover.dto.StepReportRequest;
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
 * 割接批次业务测试（真实 H2 MySQL 兼容库，无 mock）：
 * 发布冻结、结构/版本校验、幂等、启动门禁、失败传播、阻断明细与批次关闭。
 */
@SpringBootTest
class BatchServiceTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 11, 10, 2, 0);
    private static final LocalDateTime PUBLISH_AT = LocalDateTime.of(2026, 11, 9, 22, 30);

    private static final String DEV1 = "DEV-CORE-SW-01";
    private static final String DEV2 = "DEV-CORE-SW-02";
    private static final String DEV3 = "DEV-DB-01";
    private static final String DEV4 = "DEV-FW-01";

    @Autowired
    private CutoverService cutoverService;
    @Autowired
    private BatchService batchService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM cutover_block_record");
        jdbc.update("DELETE FROM cutover_batch_edge");
        jdbc.update("DELETE FROM cutover_batch_member");
        jdbc.update("DELETE FROM cutover_batch");
        jdbc.update("DELETE FROM cutover_event");
        jdbc.update("DELETE FROM cutover_step");
        jdbc.update("DELETE FROM cutover_request");
    }

    // ---------------- 工具 ----------------

    /** 申请 -> 影响分析 -> 审批通过；此时版本号 = 3。 */
    private void prepareApproved(String id, String device) {
        cutoverService.create(new CreateCutoverRequest(id, "割接-" + id, device,
                Priority.MEDIUM, BASE, BASE.plusHours(2),
                steps("停流量", "NORMAL", "硬性检查", "HARDCHECK")));
        cutoverService.analyze(id);
        cutoverService.approve(new ApproveRequest("ap-" + id, id, true, null));
    }

    private BatchMemberRef member(String id) {
        return new BatchMemberRef(id, 3);
    }

    private BatchActionResult publish(String batchId, List<BatchMemberRef> members,
                                      List<BatchEdgeRef> edges) {
        return batchService.publish(new PublishBatchRequest(batchId, PUBLISH_AT, members, edges));
    }

    /** 执行 -> 两步成功 -> 验证通过 -> 封板。 */
    private void executeAndSeal(String id) {
        cutoverService.startExecution(new StartExecutionRequest("ex-" + id, id, BASE));
        cutoverService.report(new StepReportRequest(
                "r1-" + id, id, 1, true, BASE.plusMinutes(20), null));
        cutoverService.report(new StepReportRequest(
                "r2-" + id, id, 2, true, BASE.plusMinutes(30), null));
        cutoverService.seal(new SealRequest("seal-" + id, id));
    }

    // ---------------- 发布主流程 ----------------

    @Test
    @DisplayName("发布主流程：原子冻结成员/依赖/窗口快照并绑定批次，版本从1开始成功变更加1")
    void publishFreezesMembersEdgesAndWindowSnapshot() {
        prepareApproved("R-A", DEV1);
        prepareApproved("R-B", DEV2);
        prepareApproved("R-C", DEV3);

        BatchActionResult result = publish("B-1",
                List.of(member("R-A"), member("R-B"), member("R-C")),
                List.of(new BatchEdgeRef("R-A", "R-B"), new BatchEdgeRef("R-A", "R-C")));

        assertThat(result.replayed()).isFalse();
        BatchView batch = result.batch();
        assertThat(batch.status()).isEqualTo("PUBLISHED");
        assertThat(batch.version()).isEqualTo(1);
        assertThat(batch.publishedAt()).isEqualTo(PUBLISH_AT);
        // 拓扑按申请编号稳定排序
        assertThat(batch.members()).extracting(BatchMemberView::requestId)
                .containsExactly("R-A", "R-B", "R-C");
        // 发布时冻结版本与窗口快照
        assertThat(batch.members()).allSatisfy(m -> {
            assertThat(m.requestVersion()).isEqualTo(3);
            assertThat(m.windowStart()).isEqualTo(BASE);
            assertThat(m.windowEnd()).isEqualTo(BASE.plusHours(2));
            assertThat(m.requestStatus()).isEqualTo("APPROVED");
            assertThat(m.blockingSources()).isEmpty();
        });
        assertThat(batch.edges()).extracting(e -> e.predecessor() + "->" + e.successor())
                .containsExactly("R-A->R-B", "R-A->R-C");

        // 成员绑定批次；绑定是成功变更，版本 3 -> 4
        assertThat(cutoverService.get("R-A").batchId()).isEqualTo("B-1");
        assertThat(cutoverService.get("R-A").version()).isEqualTo(4);
        assertThat(cutoverService.get("R-B").version()).isEqualTo(4);
    }

    @Test
    @DisplayName("发布校验：成员重复/边端点出批/自环/环路/版本不符/非已批准/已属未结束批次 整批拒绝(422)，申请不存在 404")
    void publishValidationRejectsWholeBatch() {
        prepareApproved("R-A", DEV1);
        prepareApproved("R-B", DEV2);

        // 成员重复
        assertThatThrownBy(() -> publish("B-DUP",
                List.of(member("R-A"), member("R-A")), List.of()))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("唯一");
        // 边端点出批
        assertThatThrownBy(() -> publish("B-OUT",
                List.of(member("R-A")), List.of(new BatchEdgeRef("R-A", "R-ZZ"))))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("批内");
        // 自环
        assertThatThrownBy(() -> publish("B-SELF",
                List.of(member("R-A")), List.of(new BatchEdgeRef("R-A", "R-A"))))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("自环");
        // 环路
        assertThatThrownBy(() -> publish("B-CYCLE",
                List.of(member("R-A"), member("R-B")),
                List.of(new BatchEdgeRef("R-A", "R-B"), new BatchEdgeRef("R-B", "R-A"))))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("环路");
        // 版本不符
        assertThatThrownBy(() -> publish("B-VER",
                List.of(new BatchMemberRef("R-A", 2)), List.of()))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("版本不符");
        // 申请不存在 -> 404
        assertThatThrownBy(() -> publish("B-404",
                List.of(new BatchMemberRef("R-NONE", 1)), List.of()))
                .isInstanceOf(NotFoundException.class);
        // 未批准（SUBMITTED）不能入批
        cutoverService.create(new CreateCutoverRequest("R-SUB", "割接-R-SUB", DEV3,
                Priority.MEDIUM, BASE, BASE.plusHours(2), steps("s1", "NORMAL")));
        assertThatThrownBy(() -> publish("B-STATE",
                List.of(new BatchMemberRef("R-SUB", 1)), List.of()))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("APPROVED");

        // 失败不占键：以上 batchId 均未落库
        assertThatThrownBy(() -> batchService.getBatch("B-DUP"))
                .isInstanceOf(NotFoundException.class);
        // 一项申请最多属于一个未结束批次
        publish("B-OK", List.of(member("R-A")), List.of());
        assertThatThrownBy(() -> publish("B-AGAIN",
                List.of(member("R-A"), member("R-B")), List.of()))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("未结束批次");
        // 整批拒绝不产生任何记录：B-AGAIN 不存在，R-B 未绑定
        assertThat(cutoverService.get("R-B").batchId()).isNull();
    }

    @Test
    @DisplayName("已开始成员整批拒绝：执行中的申请不能入批")
    void publishRejectsStartedMember() {
        prepareApproved("R-A", DEV1);
        prepareApproved("R-B", DEV2);
        cutoverService.startExecution(new StartExecutionRequest("ex-R-A", "R-A", BASE));

        assertThatThrownBy(() -> publish("B-STARTED",
                List.of(member("R-A"), member("R-B")), List.of()))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("APPROVED");
        assertThat(cutoverService.get("R-B").batchId()).isNull();
    }

    @Test
    @DisplayName("发布幂等：同键同参（含成员顺序不同）重放首次冻结结果；同键异参 409；失败不占键")
    void publishIdempotency() {
        prepareApproved("R-A", DEV1);
        prepareApproved("R-B", DEV2);
        List<BatchEdgeRef> edges = List.of(new BatchEdgeRef("R-A", "R-B"));

        BatchActionResult first = publish("B-IDEM",
                List.of(member("R-A"), member("R-B")), edges);
        assertThat(first.replayed()).isFalse();

        // 同键同参：成员集合顺序不同仍为同参（集合语义），重放首次冻结结果
        BatchActionResult replay = publish("B-IDEM",
                List.of(member("R-B"), member("R-A")), edges);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.batch().members()).extracting(BatchMemberView::requestId)
                .containsExactly("R-A", "R-B");

        // 同键异参：精确时刻不同 -> 409
        assertThatThrownBy(() -> batchService.publish(new PublishBatchRequest(
                "B-IDEM", PUBLISH_AT.plusMinutes(1),
                List.of(member("R-A"), member("R-B")), edges)))
                .isInstanceOf(ConflictException.class);
        // 同键异参：依赖边不同 -> 409
        assertThatThrownBy(() -> publish("B-IDEM",
                List.of(member("R-A"), member("R-B")), List.of()))
                .isInstanceOf(ConflictException.class);

        // 失败不占键：先以不符版本发布失败，修正后同键成功（使用未入批的 R-C）
        prepareApproved("R-C", DEV3);
        assertThatThrownBy(() -> publish("B-RETRY",
                List.of(new BatchMemberRef("R-C", 99)), List.of()))
                .isInstanceOf(BusinessRuleException.class);
        BatchActionResult retried = publish("B-RETRY", List.of(member("R-C")), List.of());
        assertThat(retried.replayed()).isFalse();
        assertThat(retried.batch().status()).isEqualTo("PUBLISHED");
    }

    // ---------------- 启动门禁 ----------------

    @Test
    @DisplayName("启动门禁：前置未封板返回等待且不写步骤不占键；前置封板后可启动；未绑定批次旧行为不变")
    void startGateWaitsUntilPredecessorsSealed() {
        prepareApproved("R-A", DEV1);
        prepareApproved("R-B", DEV2);
        prepareApproved("R-FREE", DEV3);
        publish("B-GATE", List.of(member("R-A"), member("R-B")),
                List.of(new BatchEdgeRef("R-A", "R-B")));

        // 前置 R-A 尚未开始：R-B 启动返回等待，不写步骤、不写事件、状态不变
        ActionResult waiting = cutoverService.startExecution(
                new StartExecutionRequest("ex-R-B", "R-B", BASE));
        assertThat(waiting.replayed()).isFalse();
        assertThat(waiting.message()).contains("等待");
        assertThat(waiting.request().status()).isEqualTo("APPROVED");
        assertThat(waiting.request().steps()).allSatisfy(s -> {
            assertThat(s.status()).isEqualTo("PENDING");
            assertThat(s.startedAt()).isNull();
        });
        // 等待不占键、不写执行事件（审批事件属于准备阶段）
        assertThat(cutoverService.events("R-B"))
                .extracting(e -> e.eventType())
                .doesNotContain("EXECUTE_START");

        // 前置执行中：仍然等待
        cutoverService.startExecution(new StartExecutionRequest("ex-R-A", "R-A", BASE));
        ActionResult waiting2 = cutoverService.startExecution(
                new StartExecutionRequest("ex-R-B", "R-B", BASE));
        assertThat(waiting2.message()).contains("等待");
        assertThat(cutoverService.get("R-B").status()).isEqualTo("APPROVED");

        // 前置验证通过但未封板：仍等待（须验证通过并封板）
        cutoverService.report(new StepReportRequest(
                "r1-R-A", "R-A", 1, true, BASE.plusMinutes(20), null));
        cutoverService.report(new StepReportRequest(
                "r2-R-A", "R-A", 2, true, BASE.plusMinutes(30), null));
        assertThat(cutoverService.get("R-A").status()).isEqualTo("VERIFIED");
        assertThat(cutoverService.startExecution(
                new StartExecutionRequest("ex-R-B", "R-B", BASE)).message()).contains("等待");

        // 前置封板后：同键重试成功启动（等待未占键）
        cutoverService.seal(new SealRequest("seal-R-A", "R-A"));
        ActionResult started = cutoverService.startExecution(
                new StartExecutionRequest("ex-R-B", "R-B", BASE));
        assertThat(started.replayed()).isFalse();
        assertThat(started.request().status()).isEqualTo("EXECUTING");

        // 未绑定批次的旧单申请：无门禁，批准即可启动
        ActionResult free = cutoverService.startExecution(
                new StartExecutionRequest("ex-R-FREE", "R-FREE", BASE));
        assertThat(free.request().status()).isEqualTo("EXECUTING");
        assertThat(free.request().batchId()).isNull();
    }

    // ---------------- 失败传播 ----------------

    @Test
    @DisplayName("失败传播：前置回退同事务阻断全部可达未开始后继并记录首个阻断来源；无关支路继续；已开始成员不受影响")
    void failurePropagatesToReachableSuccessorsInSameTransaction() {
        prepareApproved("R-A", DEV1);
        prepareApproved("R-B", DEV2);
        prepareApproved("R-C", DEV3);
        prepareApproved("R-D", DEV4);
        // R-A -> R-B -> R-C；R-D 为无关支路（无边）
        publish("B-PROP", List.of(member("R-A"), member("R-B"), member("R-C"), member("R-D")),
                List.of(new BatchEdgeRef("R-A", "R-B"), new BatchEdgeRef("R-B", "R-C")));

        // R-A 执行中硬性检查失败 -> 回退；传播应同事务完成
        cutoverService.startExecution(new StartExecutionRequest("ex-R-A", "R-A", BASE));
        cutoverService.report(new StepReportRequest(
                "r1-R-A", "R-A", 1, true, BASE.plusMinutes(10), null));
        ActionResult failed = cutoverService.report(new StepReportRequest(
                "r2-R-A", "R-A", 2, false, BASE.plusMinutes(20), null));
        assertThat(failed.request().status()).isEqualTo("ROLLED_BACK");

        // 可达未开始后继 R-B、R-C 已同事务转依赖阻断
        assertThat(cutoverService.get("R-B").status()).isEqualTo("DEPENDENCY_BLOCKED");
        assertThat(cutoverService.get("R-C").status()).isEqualTo("DEPENDENCY_BLOCKED");
        // 无关支路 R-D 不受影响，可正常启动
        assertThat(cutoverService.get("R-D").status()).isEqualTo("APPROVED");
        ActionResult dStarted = cutoverService.startExecution(
                new StartExecutionRequest("ex-R-D", "R-D", BASE));
        assertThat(dStarted.request().status()).isEqualTo("EXECUTING");

        // 阻断明细：首个阻断来源集合，R-B<-R-A(ROLLBACK)，R-C<-R-B(DEPENDENCY_BLOCKED 传导)
        List<BlockDetailView> blocks = batchService.listBlocks("B-PROP");
        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).requestId()).isEqualTo("R-B");
        assertThat(blocks.get(0).sourceRequestId()).isEqualTo("R-A");
        assertThat(blocks.get(0).cause()).isEqualTo("ROLLBACK");
        assertThat(blocks.get(1).requestId()).isEqualTo("R-C");
        assertThat(blocks.get(1).sourceRequestId()).isEqualTo("R-B");
        assertThat(blocks.get(1).cause()).isEqualTo("DEPENDENCY_BLOCKED");

        // 拓扑查询保留首个阻断来源集合
        BatchView topo = batchService.getBatch("B-PROP");
        assertThat(topo.members()).extracting(BatchMemberView::requestId)
                .containsExactly("R-A", "R-B", "R-C", "R-D");
        assertThat(topo.members().get(1).blockingSources()).containsExactly("R-A");
        assertThat(topo.members().get(2).blockingSources()).containsExactly("R-B");

        // 被阻断成员不能启动（终态 422）
        assertThatThrownBy(() -> cutoverService.startExecution(
                new StartExecutionRequest("ex-R-B", "R-B", BASE)))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    @DisplayName("已开始成员按原回退流程处理，已封板结果不改，传播不产生多余阻断记录")
    void startedSuccessorFollowsOriginalFlow() {
        prepareApproved("R-A", DEV1);
        prepareApproved("R-B", DEV2);
        publish("B-STARTED-SUCC", List.of(member("R-A"), member("R-B")),
                List.of(new BatchEdgeRef("R-A", "R-B")));

        // R-A 完成并封板后 R-B 启动；R-B 执行中自身失败，按原回退流程处理。
        executeAndSeal("R-A");
        cutoverService.startExecution(new StartExecutionRequest("ex-R-B", "R-B", BASE));
        cutoverService.report(new StepReportRequest(
                "r1-R-B", "R-B", 1, true, BASE.plusMinutes(40), null));
        ActionResult failed = cutoverService.report(new StepReportRequest(
                "r2-R-B", "R-B", 2, false, BASE.plusMinutes(50), null));
        assertThat(failed.request().status()).isEqualTo("ROLLED_BACK");
        // 已封板的 R-A 结果不改
        assertThat(cutoverService.get("R-A").status()).isEqualTo("SEALED");
        // R-B 是边的末端，无后继被阻断
        assertThat(batchService.listBlocks("B-STARTED-SUCC")).isEmpty();
        // 全部成员终态 -> 批次关闭
        assertThat(batchService.getBatch("B-STARTED-SUCC").status()).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("窗口超期传播：前置 WINDOW_EXPIRED 同事务阻断后继，阻断原因为 WINDOW_EXPIRED")
    void windowExpiryPropagates() {
        prepareApproved("R-A", DEV1);
        prepareApproved("R-B", DEV2);
        publish("B-EXP", List.of(member("R-A"), member("R-B")),
                List.of(new BatchEdgeRef("R-A", "R-B")));

        cutoverService.startExecution(new StartExecutionRequest("ex-R-A", "R-A", BASE));
        // 窗口结束仍有未完成步骤 -> WINDOW_EXPIRED，传播阻断 R-B
        ActionResult expired = cutoverService.expireCheck(
                new ExpireCheckRequest("exp-R-A", "R-A", BASE.plusHours(2)));
        assertThat(expired.request().status()).isEqualTo("WINDOW_EXPIRED");
        assertThat(cutoverService.get("R-B").status()).isEqualTo("DEPENDENCY_BLOCKED");
        List<BlockDetailView> blocks = batchService.listBlocks("B-EXP");
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).cause()).isEqualTo("WINDOW_EXPIRED");
        assertThat(blocks.get(0).sourceRequestId()).isEqualTo("R-A");
    }

    // ---------------- 批次关闭 ----------------

    @Test
    @DisplayName("全部成员到达终态后批次关闭（版本+1），关闭后释放未结束批次约束")
    void batchClosesWhenAllMembersTerminal() {
        prepareApproved("R-A", DEV1);
        prepareApproved("R-B", DEV2);
        publish("B-CLOSE", List.of(member("R-A"), member("R-B")),
                List.of(new BatchEdgeRef("R-A", "R-B")));

        executeAndSeal("R-A");
        // 仍有未终态成员，批次未结束
        assertThat(batchService.getBatch("B-CLOSE").status()).isEqualTo("PUBLISHED");
        executeAndSeal("R-B");
        BatchView closed = batchService.getBatch("B-CLOSE");
        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(closed.version()).isEqualTo(2);
    }

    @Test
    @DisplayName("依赖阻断为终态：巡检/封板/拒绝均按终态处理")
    void dependencyBlockedIsTerminal() {
        prepareApproved("R-A", DEV1);
        prepareApproved("R-B", DEV2);
        publish("B-TERM", List.of(member("R-A"), member("R-B")),
                List.of(new BatchEdgeRef("R-A", "R-B")));

        cutoverService.startExecution(new StartExecutionRequest("ex-R-A", "R-A", BASE));
        cutoverService.report(new StepReportRequest(
                "r1-R-A", "R-A", 1, false, BASE.plusMinutes(10), null));
        assertThat(cutoverService.get("R-B").status()).isEqualTo("DEPENDENCY_BLOCKED");

        // 巡检：终态幂等返回，不得转为 WINDOW_EXPIRED
        ActionResult check = cutoverService.expireCheck(
                new ExpireCheckRequest("exp-R-B", "R-B", BASE.plusHours(3)));
        assertThat(check.message()).contains("终态");
        assertThat(cutoverService.get("R-B").status()).isEqualTo("DEPENDENCY_BLOCKED");
        // 封板拒绝
        assertThatThrownBy(() -> cutoverService.seal(new SealRequest("seal-R-B", "R-B")))
                .isInstanceOf(BusinessRuleException.class);
        // 审批拒绝：终态不可再拒绝
        assertThatThrownBy(() -> cutoverService.approve(
                new ApproveRequest("ap2-R-B", "R-B", false, "取消")))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("依赖阻断");
        // 全部成员终态，批次关闭
        assertThat(batchService.getBatch("B-TERM").status()).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("版本语义：申请版本从1开始，每次成功变更加1")
    void requestVersionIncrementsOnEachChange() {
        cutoverService.create(new CreateCutoverRequest("R-V", "割接-R-V", DEV1,
                Priority.MEDIUM, BASE, BASE.plusHours(2), steps("s1", "NORMAL")));
        assertThat(cutoverService.get("R-V").version()).isEqualTo(1);
        cutoverService.analyze("R-V");
        assertThat(cutoverService.get("R-V").version()).isEqualTo(2);
        cutoverService.approve(new ApproveRequest("ap-R-V", "R-V", true, null));
        assertThat(cutoverService.get("R-V").version()).isEqualTo(3);
        cutoverService.startExecution(new StartExecutionRequest("ex-R-V", "R-V", BASE));
        assertThat(cutoverService.get("R-V").version()).isEqualTo(4);
        cutoverService.report(new StepReportRequest(
                "r1-R-V", "R-V", 1, true, BASE.plusMinutes(5), null));
        assertThat(cutoverService.get("R-V").version()).isEqualTo(5);
        cutoverService.seal(new SealRequest("seal-R-V", "R-V"));
        assertThat(cutoverService.get("R-V").version()).isEqualTo(6);
        // 幂等重放不产生变更，版本不变
        cutoverService.seal(new SealRequest("seal-R-V-2", "R-V"));
        assertThat(cutoverService.get("R-V").version()).isEqualTo(6);
    }
}
