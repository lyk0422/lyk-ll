package com.example.starter.cutover;

import static com.example.starter.cutover.CutoverFixtures.steps;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.cutover.dto.ActionResult;
import com.example.starter.cutover.dto.ApproveRequest;
import com.example.starter.cutover.dto.BatchEdgeRef;
import com.example.starter.cutover.dto.BatchMemberRef;
import com.example.starter.cutover.dto.BatchMemberView;
import com.example.starter.cutover.dto.BatchResult;
import com.example.starter.cutover.dto.BatchView;
import com.example.starter.cutover.dto.BlockedMemberView;
import com.example.starter.cutover.dto.CreateCutoverRequest;
import com.example.starter.cutover.dto.ExpireCheckRequest;
import com.example.starter.cutover.dto.PublishBatchRequest;
import com.example.starter.cutover.dto.SealRequest;
import com.example.starter.cutover.dto.StartExecutionRequest;
import com.example.starter.cutover.dto.StepReportRequest;
import com.example.starter.cutover.repo.CutoverRepository;
import com.example.starter.cutover.service.CutoverBatchService;
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
 * 跨设备割接依赖批次：发布冻结、执行门禁、依赖阻断传播的 H2 业务测试。
 */
@SpringBootTest
class CutoverBatchServiceTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 11, 5, 2, 0);
    private static final LocalDateTime END = BASE.plusHours(2);
    private static final LocalDateTime PUBLISHED_AT = LocalDateTime.of(2026, 11, 4, 18, 30);

    private static final String DEV_A = "DEV-CORE-SW-01";
    private static final String DEV_B = "DEV-CORE-SW-02";
    private static final String DEV_C = "DEV-DB-01";
    private static final String DEV_D = "DEV-FW-01";

    @Autowired
    private CutoverService service;
    @Autowired
    private CutoverBatchService batchService;
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

    // ---------------- 工具 ----------------

    /** 走完 申请->分析->审批，到达 APPROVED；每次成功变更版本加 1。 */
    private void prepareApproved(String id, String device) {
        service.create(new CreateCutoverRequest(id, "割接-" + id, device,
                Priority.MEDIUM, BASE, END,
                steps("停流量", "NORMAL", "硬性检查", "HARDCHECK")));
        service.analyze(id);
        service.approve(new ApproveRequest("ap-" + id, id, true, null));
    }

    private int versionOf(String id) {
        return service.get(id).version();
    }

    private BatchMemberRef mem(String id) {
        return new BatchMemberRef(id, versionOf(id));
    }

    private PublishBatchRequest batchReq(String batchId, List<BatchMemberRef> members,
                                         List<BatchEdgeRef> edges) {
        return new PublishBatchRequest(batchId, PUBLISHED_AT, members, edges);
    }

    private BatchMemberView memberOf(BatchView view, String requestId) {
        return view.members().stream()
                .filter(m -> m.requestId().equals(requestId)).findFirst().orElseThrow();
    }

    // ---------------- 发布主流程与幂等 ----------------

    @Test
    @DisplayName("发布主流程：原子冻结成员/依赖/窗口快照并绑定批次，成员版本加 1")
    void publishFreezesAndBinds() {
        prepareApproved("B-A", DEV_A);
        prepareApproved("B-B", DEV_B);
        prepareApproved("B-C", DEV_C);
        int va = versionOf("B-A");

        BatchResult result = batchService.publish(batchReq("BAT-1",
                List.of(mem("B-C"), mem("B-A"), mem("B-B")),
                List.of(new BatchEdgeRef("B-A", "B-B"), new BatchEdgeRef("B-A", "B-C"))));

        assertThat(result.replayed()).isFalse();
        BatchView view = result.batch();
        assertThat(view.batchId()).isEqualTo("BAT-1");
        assertThat(view.publishedAt()).isEqualTo(PUBLISHED_AT);
        // 拓扑按申请编号稳定排序
        assertThat(view.members()).extracting(BatchMemberView::requestId)
                .containsExactly("B-A", "B-B", "B-C");
        assertThat(view.edges()).extracting(e -> e.fromRequestId() + "->" + e.toRequestId())
                .containsExactly("B-A->B-B", "B-A->B-C");
        // 窗口快照冻结、版本加 1、绑定批次
        assertThat(memberOf(view, "B-A").windowStart()).isEqualTo(BASE);
        assertThat(memberOf(view, "B-A").windowEnd()).isEqualTo(END);
        assertThat(memberOf(view, "B-A").expectedVersion()).isEqualTo(va);
        assertThat(memberOf(view, "B-A").currentVersion()).isEqualTo(va + 1);
        assertThat(memberOf(view, "B-A").status()).isEqualTo("APPROVED");
        assertThat(memberOf(view, "B-A").blockedBy()).isNull();
        assertThat(service.get("B-A").batchId()).isEqualTo("BAT-1");
        assertThat(service.get("B-B").batchId()).isEqualTo("BAT-1");
        // 每个成员一条绑定事件
        assertThat(service.events("B-A"))
                .anyMatch(e -> "BATCH_BIND".equals(e.eventType()));
        assertThat(service.events("B-C"))
                .anyMatch(e -> "BATCH_BIND".equals(e.eventType()));
    }

    @Test
    @DisplayName("发布幂等：同键同参重放首次冻结结果，不重复绑定；同键异参 409")
    void publishIdempotentAndConflict() {
        prepareApproved("I-A", DEV_A);
        prepareApproved("I-B", DEV_B);
        PublishBatchRequest req = batchReq("BAT-I",
                List.of(mem("I-A"), mem("I-B")),
                List.of(new BatchEdgeRef("I-A", "I-B")));
        batchService.publish(req);
        int versionAfterFirst = versionOf("I-A");

        BatchResult replay = batchService.publish(req);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.message()).contains("同键同参重放");
        // 重放不重复绑定，版本不变
        assertThat(versionOf("I-A")).isEqualTo(versionAfterFirst);
        assertThat(repo.listBatchMembers("BAT-I")).hasSize(2);

        // 同键异参：不同依赖边 -> 409
        PublishBatchRequest other = batchReq("BAT-I",
                List.of(mem("I-A"), mem("I-B")), List.of());
        assertThatThrownBy(() -> batchService.publish(other))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("不一致");
        // 异参失败不产生新记录
        assertThat(repo.listBatchEdges("BAT-I")).hasSize(1);
    }

    @Test
    @DisplayName("失败不占键：版本不符整批拒绝后，修正参数可用同 requestId 成功发布")
    void publishFailureDoesNotOccupyKey() {
        prepareApproved("F-A", DEV_A);
        prepareApproved("F-B", DEV_B);

        PublishBatchRequest bad = batchReq("BAT-F",
                List.of(mem("F-A"), new BatchMemberRef("F-B", 99)),
                List.of(new BatchEdgeRef("F-A", "F-B")));
        assertThatThrownBy(() -> batchService.publish(bad))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("版本");
        // 失败不占键：无批次、无成员、无绑定
        assertThat(repo.findBatch("BAT-F")).isNull();
        assertThat(service.get("F-A").batchId()).isNull();

        BatchResult ok = batchService.publish(batchReq("BAT-F",
                List.of(mem("F-A"), mem("F-B")),
                List.of(new BatchEdgeRef("F-A", "F-B"))));
        assertThat(ok.replayed()).isFalse();
        assertThat(service.get("F-A").batchId()).isEqualTo("BAT-F");
    }

    // ---------------- 整批拒绝分支 ----------------

    @Test
    @DisplayName("整批拒绝：成员重复/边端点越界/自环/环路")
    void publishRejectsInvalidTopology() {
        prepareApproved("T-A", DEV_A);
        prepareApproved("T-B", DEV_B);
        prepareApproved("T-C", DEV_C);

        // 成员重复
        assertThatThrownBy(() -> batchService.publish(batchReq("BAT-T1",
                List.of(mem("T-A"), mem("T-A")), List.of())))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("唯一");
        // 边端点不在批内
        assertThatThrownBy(() -> batchService.publish(batchReq("BAT-T2",
                List.of(mem("T-A"), mem("T-B")),
                List.of(new BatchEdgeRef("T-A", "T-C")))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("两端都必须在批内");
        // 自环
        assertThatThrownBy(() -> batchService.publish(batchReq("BAT-T3",
                List.of(mem("T-A"), mem("T-B")),
                List.of(new BatchEdgeRef("T-A", "T-A")))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("自环");
        // 环路 A->B->C->A
        assertThatThrownBy(() -> batchService.publish(batchReq("BAT-T4",
                List.of(mem("T-A"), mem("T-B"), mem("T-C")),
                List.of(new BatchEdgeRef("T-A", "T-B"), new BatchEdgeRef("T-B", "T-C"),
                        new BatchEdgeRef("T-C", "T-A")))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("环路");
        // 全部失败不占键、不绑定
        assertThat(repo.findBatch("BAT-T1")).isNull();
        assertThat(repo.findBatch("BAT-T4")).isNull();
        assertThat(service.get("T-A").batchId()).isNull();
        assertThat(service.get("T-B").batchId()).isNull();
        assertThat(service.get("T-C").batchId()).isNull();
    }

    @Test
    @DisplayName("整批拒绝：成员已开始/未批准/版本不符/已属其他批次；成员不存在 404")
    void publishRejectsInvalidMembers() {
        prepareApproved("M-A", DEV_A);
        prepareApproved("M-B", DEV_B);
        prepareApproved("M-C", DEV_C);
        // 未审批申请（SUBMITTED），使用不同时段避免与同设备申请窗口冲突
        service.create(new CreateCutoverRequest("M-S", "割接-M-S", DEV_D,
                Priority.MEDIUM, BASE.plusDays(1), BASE.plusDays(1).plusHours(2),
                steps("s1", "NORMAL")));

        // 已开始成员：M-A 进入执行
        service.startExecution(new StartExecutionRequest("ex-M-A", "M-A", BASE));
        assertThatThrownBy(() -> batchService.publish(batchReq("BAT-M1",
                List.of(mem("M-A"), mem("M-B")),
                List.of(new BatchEdgeRef("M-A", "M-B")))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("APPROVED");

        // 未批准成员
        assertThatThrownBy(() -> batchService.publish(batchReq("BAT-M2",
                List.of(new BatchMemberRef("M-S", 1), mem("M-B")),
                List.of())))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("APPROVED");

        // 版本不符
        assertThatThrownBy(() -> batchService.publish(batchReq("BAT-M3",
                List.of(new BatchMemberRef("M-B", versionOf("M-B") + 1), mem("M-C")),
                List.of())))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("版本");

        // 成员不存在 -> 404
        assertThatThrownBy(() -> batchService.publish(batchReq("BAT-M4",
                List.of(new BatchMemberRef("M-NOPE", 1), mem("M-B")),
                List.of())))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("M-NOPE");

        // 已属其他批次
        batchService.publish(batchReq("BAT-M5",
                List.of(mem("M-B"), mem("M-C")),
                List.of(new BatchEdgeRef("M-B", "M-C"))));
        prepareApproved("M-D", DEV_D);
        assertThatThrownBy(() -> batchService.publish(batchReq("BAT-M6",
                List.of(mem("M-B"), mem("M-D")),
                List.of(new BatchEdgeRef("M-B", "M-D")))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("最多属于一个");
        // 整批拒绝：M-D 未被绑定
        assertThat(service.get("M-D").batchId()).isNull();
    }

    // ---------------- 执行门禁 ----------------

    @Test
    @DisplayName("执行门禁：前置未封板返回等待且不写步骤不占键；前置封板后同键放行")
    void gateWaitsUntilPrerequisiteSealed() {
        prepareApproved("G-A", DEV_A);
        prepareApproved("G-B", DEV_B);
        batchService.publish(batchReq("BAT-G",
                List.of(mem("G-A"), mem("G-B")),
                List.of(new BatchEdgeRef("G-A", "G-B"))));

        // 前置未开始 -> 等待，不写步骤、不改状态、不占幂等键
        ActionResult wait1 = service.startExecution(
                new StartExecutionRequest("ex-G-B", "G-B", BASE));
        assertThat(wait1.waiting()).isTrue();
        assertThat(wait1.message()).contains("等待");
        assertThat(service.get("G-B").status()).isEqualTo("APPROVED");
        assertThat(service.events("G-B"))
                .noneMatch(e -> "EXECUTE_START".equals(e.eventType()));
        assertThat(repo.listSteps("G-B"))
                .allMatch(s -> "PENDING".equals(s.getStatus()));

        // 前置执行并验证通过但未封板 -> 仍等待
        service.startExecution(new StartExecutionRequest("ex-G-A", "G-A", BASE));
        service.report(new StepReportRequest("r-G-A1", "G-A", 1, true,
                BASE.plusMinutes(10), null));
        service.report(new StepReportRequest("r-G-A2", "G-A", 2, true,
                BASE.plusMinutes(20), null));
        assertThat(service.get("G-A").status()).isEqualTo("VERIFIED");
        ActionResult wait2 = service.startExecution(
                new StartExecutionRequest("ex-G-B", "G-B", BASE));
        assertThat(wait2.waiting()).isTrue();

        // 前置封板 -> 同一 requestUid 放行（等待未占键）
        service.seal(new SealRequest("seal-G-A", "G-A"));
        ActionResult go = service.startExecution(
                new StartExecutionRequest("ex-G-B", "G-B", BASE));
        assertThat(go.waiting()).isNull();
        assertThat(go.request().status()).isEqualTo("EXECUTING");
    }

    // ---------------- 依赖阻断传播 ----------------

    @Test
    @DisplayName("前置回退同事务传播：可达未开始后继转依赖阻断，无关支路继续，阻断明细保留首个来源")
    void rollbackPropagatesDependencyBlock() {
        prepareApproved("P-A", DEV_A);
        prepareApproved("P-B", DEV_B);
        prepareApproved("P-C", DEV_C);
        prepareApproved("P-D", DEV_D);
        // A->B->C，D 为无关支路
        batchService.publish(batchReq("BAT-P",
                List.of(mem("P-A"), mem("P-B"), mem("P-C"), mem("P-D")),
                List.of(new BatchEdgeRef("P-A", "P-B"), new BatchEdgeRef("P-B", "P-C"))));

        // A 执行，硬性检查失败 -> 回退，同事务传播
        service.startExecution(new StartExecutionRequest("ex-P-A", "P-A", BASE));
        service.report(new StepReportRequest("r-P-A1", "P-A", 1, true,
                BASE.plusMinutes(10), null));
        ActionResult failed = service.report(new StepReportRequest("r-P-A2", "P-A", 2,
                false, BASE.plusMinutes(20), null));
        assertThat(failed.request().status()).isEqualTo("ROLLED_BACK");

        // 传播与前置状态提交同事务：返回后即可见
        assertThat(service.get("P-B").status()).isEqualTo("DEPENDENCY_BLOCKED");
        assertThat(service.get("P-C").status()).isEqualTo("DEPENDENCY_BLOCKED");
        // 无关支路不受影响，可继续启动
        assertThat(service.get("P-D").status()).isEqualTo("APPROVED");
        ActionResult dStart = service.startExecution(
                new StartExecutionRequest("ex-P-D", "P-D", BASE));
        assertThat(dStart.request().status()).isEqualTo("EXECUTING");

        // 阻断成员启动 -> 422 业务拒绝
        assertThatThrownBy(() -> service.startExecution(
                new StartExecutionRequest("ex-P-B", "P-B", BASE)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("APPROVED");

        // 阻断事件与明细：B 的直接失败前置为 A，C 的直接阻断前置为 B
        assertThat(service.events("P-B"))
                .anyMatch(e -> "DEPENDENCY_BLOCK".equals(e.eventType()));
        assertThat(service.events("P-C"))
                .anyMatch(e -> "DEPENDENCY_BLOCK".equals(e.eventType()));
        List<BlockedMemberView> blocks = batchService.getBlocks("BAT-P");
        assertThat(blocks).extracting(BlockedMemberView::requestId)
                .containsExactly("P-B", "P-C");
        assertThat(blocks.get(0).blockedBy()).isEqualTo("P-A");
        assertThat(blocks.get(0).blockingSources()).containsExactly("P-A");
        assertThat(blocks.get(1).blockedBy()).isEqualTo("P-A");
        assertThat(blocks.get(1).blockingSources()).containsExactly("P-B");
    }

    @Test
    @DisplayName("多前置先后失败：保留首个阻断来源，明细汇集全部失败前置")
    void firstBlockingSourceIsKept() {
        prepareApproved("S-A", DEV_A);
        prepareApproved("S-B", DEV_B);
        prepareApproved("S-C", DEV_C);
        // A->C 且 B->C
        batchService.publish(batchReq("BAT-S",
                List.of(mem("S-A"), mem("S-B"), mem("S-C")),
                List.of(new BatchEdgeRef("S-A", "S-C"), new BatchEdgeRef("S-B", "S-C"))));

        // A 先失败 -> C 阻断，首个来源 A
        service.startExecution(new StartExecutionRequest("ex-S-A", "S-A", BASE));
        service.report(new StepReportRequest("r-S-A1", "S-A", 1, true,
                BASE.plusMinutes(10), null));
        service.report(new StepReportRequest("r-S-A2", "S-A", 2, false,
                BASE.plusMinutes(20), null));
        assertThat(service.get("S-C").status()).isEqualTo("DEPENDENCY_BLOCKED");

        // B 后失败 -> C 已被阻断，首个来源不覆盖
        service.startExecution(new StartExecutionRequest("ex-S-B", "S-B", BASE));
        service.report(new StepReportRequest("r-S-B1", "S-B", 1, true,
                BASE.plusMinutes(10), null));
        service.report(new StepReportRequest("r-S-B2", "S-B", 2, false,
                BASE.plusMinutes(20), null));

        List<BlockedMemberView> blocks = batchService.getBlocks("BAT-S");
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).requestId()).isEqualTo("S-C");
        assertThat(blocks.get(0).blockedBy()).isEqualTo("S-A");
        assertThat(blocks.get(0).blockingSources()).containsExactly("S-A", "S-B");
    }

    @Test
    @DisplayName("前置窗口超期：同事务传播依赖阻断；已封板结果不改")
    void expirePropagatesDependencyBlock() {
        prepareApproved("E-A", DEV_A);
        prepareApproved("E-B", DEV_B);
        prepareApproved("E-C", DEV_C);
        // A->B；C 独立但同批，先完成封板
        batchService.publish(batchReq("BAT-E",
                List.of(mem("E-A"), mem("E-B"), mem("E-C")),
                List.of(new BatchEdgeRef("E-A", "E-B"))));
        service.startExecution(new StartExecutionRequest("ex-E-C", "E-C", BASE));
        service.report(new StepReportRequest("r-E-C1", "E-C", 1, true,
                BASE.plusMinutes(10), null));
        service.report(new StepReportRequest("r-E-C2", "E-C", 2, true,
                BASE.plusMinutes(20), null));
        service.seal(new SealRequest("seal-E-C", "E-C"));

        // A 窗口结束仍有未完成步骤 -> WINDOW_EXPIRED，传播阻断 B
        service.startExecution(new StartExecutionRequest("ex-E-A", "E-A", BASE));
        ActionResult expired = service.expireCheck(
                new ExpireCheckRequest("exp-E-A", "E-A", END));
        assertThat(expired.request().status()).isEqualTo("WINDOW_EXPIRED");
        assertThat(service.get("E-B").status()).isEqualTo("DEPENDENCY_BLOCKED");
        // 已封板成员不受影响
        assertThat(service.get("E-C").status()).isEqualTo("SEALED");

        List<BlockedMemberView> blocks = batchService.getBlocks("BAT-E");
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).requestId()).isEqualTo("E-B");
        assertThat(blocks.get(0).blockedBy()).isEqualTo("E-A");
    }

    @Test
    @DisplayName("拓扑查询：成员乱序发布仍按申请编号稳定排序；查询不存在批次 404")
    void topologySortedAndNotFound() {
        prepareApproved("Q-C", DEV_C);
        prepareApproved("Q-A", DEV_A);
        prepareApproved("Q-B", DEV_B);
        batchService.publish(batchReq("BAT-Q",
                List.of(mem("Q-C"), mem("Q-A"), mem("Q-B")),
                List.of(new BatchEdgeRef("Q-B", "Q-C"), new BatchEdgeRef("Q-A", "Q-C"))));

        BatchView view = batchService.getBatch("BAT-Q");
        assertThat(view.members()).extracting(BatchMemberView::requestId)
                .containsExactly("Q-A", "Q-B", "Q-C");
        assertThat(view.edges()).extracting(e -> e.fromRequestId() + "->" + e.toRequestId())
                .containsExactly("Q-A->Q-C", "Q-B->Q-C");

        assertThatThrownBy(() -> batchService.getBatch("BAT-NOPE"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> batchService.getBlocks("BAT-NOPE"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("未绑定批次的旧单申请行为不变：无门禁直接放行")
    void unboundRequestBehaviorUnchanged() {
        prepareApproved("U-A", DEV_A);
        ActionResult result = service.startExecution(
                new StartExecutionRequest("ex-U-A", "U-A", BASE));
        assertThat(result.waiting()).isNull();
        assertThat(result.request().status()).isEqualTo("EXECUTING");
        assertThat(result.request().batchId()).isNull();
        assertThat(result.request().version()).isGreaterThanOrEqualTo(1);
    }
}
