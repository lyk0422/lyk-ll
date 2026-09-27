package com.example.starter.cutover;

import static com.example.starter.cutover.CutoverFixtures.steps;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.cutover.dto.ActionResult;
import com.example.starter.cutover.dto.ApproveRequest;
import com.example.starter.cutover.dto.BatchActionResult;
import com.example.starter.cutover.dto.BatchMemberInput;
import com.example.starter.cutover.dto.BatchMemberStateView;
import com.example.starter.cutover.dto.CreateCutoverRequest;
import com.example.starter.cutover.dto.DependencyEdgeInput;
import com.example.starter.cutover.dto.PublishBatchRequest;
import com.example.starter.cutover.dto.StartExecutionRequest;
import com.example.starter.cutover.dto.StepReportRequest;
import com.example.starter.cutover.dto.TopologyNodeView;
import com.example.starter.cutover.dto.TopologyView;
import com.example.starter.cutover.repo.CutoverRepository;
import com.example.starter.cutover.service.BatchService;
import com.example.starter.cutover.service.CutoverService;
import com.example.starter.cutover.support.FingerprintUtil;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 割接批次发布、执行门禁、前置失败同事务阻断传播、拓扑/阻断明细与幂等业务测试
 * （真实 H2 MySQL 兼容库，无 mock）。
 */
@SpringBootTest
class BatchServiceTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 20, 2, 0);

    private static final String DEV1 = "DEV-CORE-SW-01";
    private static final String DEV2 = "DEV-CORE-SW-02";
    private static final String DEV3 = "DEV-DB-01";
    private static final String DEV4 = "DEV-FW-01";

    @Autowired
    private CutoverService cutover;
    @Autowired
    private BatchService batches;
    @Autowired
    private CutoverRepository repo;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM cutover_block");
        jdbc.update("DELETE FROM cutover_batch_edge");
        jdbc.update("DELETE FROM cutover_batch_member");
        jdbc.update("DELETE FROM cutover_batch");
        jdbc.update("DELETE FROM cutover_event");
        jdbc.update("DELETE FROM cutover_step");
        jdbc.update("DELETE FROM cutover_request");
    }

    /** 申请->影响分析->审批通过，使其成为“已批准未开始（APPROVED，版本1）”。 */
    private void prepareApproved(String id, String device) {
        LocalDateTime s = BASE;
        LocalDateTime e = BASE.plusHours(2);
        cutover.create(new CreateCutoverRequest(id, "割接-" + id, device, Priority.MEDIUM,
                s, e, steps("停流量", "NORMAL", "硬性检查", "HARDCHECK")));
        cutover.analyze(id);
        cutover.approve(new ApproveRequest("ap-" + id, id, true, null));
    }

    /** 成员入参携带其当前版本（APPROVED 时已历经创建/分析/审批，版本为 3）。 */
    private BatchMemberInput member(String id) {
        return new BatchMemberInput(id, cutover.get(id).version());
    }

    private DependencyEdgeInput edge(String from, String to) {
        return new DependencyEdgeInput(from, to);
    }

    private PublishBatchRequest publishReq(String batchId, LocalDateTime frozenAt,
                                           List<BatchMemberInput> members,
                                           List<DependencyEdgeInput> edges) {
        return new PublishBatchRequest(batchId, frozenAt, members, edges);
    }

    private BatchMemberStateView stateOf(BatchActionResult r, String requestId) {
        return r.batch().memberStates().stream()
                .filter(s -> s.requestId().equals(requestId)).findFirst().orElseThrow();
    }

    // ---------------- 发布主流程 ----------------

    @Test
    @DisplayName("发布主流程：原子冻结成员/依赖/窗口快照，绑定批次，成员版本1->2")
    void publishFreezesAndBinds() {
        prepareApproved("A", DEV1);
        prepareApproved("B", DEV2);

        BatchActionResult r = batches.publish(publishReq("BATCH-1", BASE,
                List.of(member("A"), member("B")), List.of(edge("A", "B"))));

        assertThat(r.replayed()).isFalse();
        assertThat(r.batch().status()).isEqualTo("OPEN");
        assertThat(r.batch().members()).extracting(m -> m.requestId())
                .containsExactly("A", "B"); // 按申请编号稳定排序
        assertThat(r.batch().edges()).hasSize(1);
        // 成员冻结窗口快照
        assertThat(r.batch().members().get(0).windowStart()).isEqualTo(BASE);
        // 绑定后成员版本 +1（APPROVED 时为 3，绑定后为 4），openBatchId 占用
        assertThat(stateOf(r, "A").version()).isEqualTo(4L);
        assertThat(cutover.get("A").openBatchId()).isEqualTo("BATCH-1");
        assertThat(cutover.get("A").version()).isEqualTo(4L);
        // 冻结表落库
        assertThat(repo.listBatchMembers("BATCH-1")).hasSize(2);
        assertThat(repo.listBatchEdges("BATCH-1")).hasSize(1);
        // 发布证据事件（批次事件 request_id 记为批次ID，直接经仓储复算）
        assertThat(repo.listEvents("BATCH-1"))
                .extracting(e -> e.getEventType()).contains("BATCH_PUBLISH");
    }

    @Test
    @DisplayName("无依赖批次也可发布；成员按编号排序而非入参顺序")
    void publishWithoutEdgesSortsMembers() {
        prepareApproved("C", DEV3);
        prepareApproved("A", DEV1);
        prepareApproved("B", DEV2);

        BatchActionResult r = batches.publish(publishReq("BATCH-SORT", BASE,
                List.of(member("C"), member("A"), member("B")), List.of()));

        assertThat(r.batch().members()).extracting(m -> m.requestId())
                .containsExactly("A", "B", "C");
        assertThat(r.batch().edges()).isEmpty();
    }

    // ---------------- 发布失败分支（整批拒绝，不占键） ----------------

    @Test
    @DisplayName("成员重复整批拒绝 422")
    void duplicateMembersRejected() {
        prepareApproved("A", DEV1);
        assertThatThrownBy(() -> batches.publish(publishReq("BATCH-DUP", BASE,
                List.of(member("A"), member("A")), List.of())))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("成员重复");
        assertThat(repo.findBatch("BATCH-DUP")).isNull();
    }

    @Test
    @DisplayName("自环整批拒绝 422，且失败不占用 requestId 键（随后同键合法发布成功）")
    void selfLoopRejectedAndKeyNotOccupied() {
        prepareApproved("A", DEV1);
        assertThatThrownBy(() -> batches.publish(publishReq("BATCH-KEY", BASE,
                List.of(member("A")), List.of(edge("A", "A")))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("自环");

        // 失败不占键：同键去掉自环后可成功发布
        BatchActionResult ok = batches.publish(publishReq("BATCH-KEY", BASE,
                List.of(member("A")), List.of()));
        assertThat(ok.replayed()).isFalse();
        assertThat(ok.batch().batchId()).isEqualTo("BATCH-KEY");
    }

    @Test
    @DisplayName("环路整批拒绝 422")
    void cycleRejected() {
        prepareApproved("A", DEV1);
        prepareApproved("B", DEV2);
        prepareApproved("C", DEV3);
        assertThatThrownBy(() -> batches.publish(publishReq("BATCH-CYC", BASE,
                List.of(member("A"), member("B"), member("C")),
                List.of(edge("A", "B"), edge("B", "C"), edge("C", "A")))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("环路");
    }

    @Test
    @DisplayName("边端点不在批内整批拒绝 422")
    void edgeEndpointOutsideBatchRejected() {
        prepareApproved("A", DEV1);
        prepareApproved("B", DEV2);
        assertThatThrownBy(() -> batches.publish(publishReq("BATCH-EDGE", BASE,
                List.of(member("A")), List.of(edge("A", "B")))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("端点不在批内");
    }

    @Test
    @DisplayName("成员不存在返回 404")
    void missingMember404() {
        prepareApproved("A", DEV1);
        assertThatThrownBy(() -> batches.publish(publishReq("BATCH-404", BASE,
                List.of(member("A"), member("GHOST")), List.of())))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("GHOST");
    }

    @Test
    @DisplayName("成员版本不符整批拒绝 422")
    void versionMismatchRejected() {
        prepareApproved("A", DEV1);
        assertThatThrownBy(() -> batches.publish(publishReq("BATCH-VER", BASE,
                List.of(new BatchMemberInput("A", 9L)), List.of())))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("版本不符");
        // 拒绝后成员未被绑定，版本保持 APPROVED 时的 3
        assertThat(cutover.get("A").version()).isEqualTo(3L);
        assertThat(cutover.get("A").openBatchId()).isNull();
    }

    @Test
    @DisplayName("成员已开始（EXECUTING）整批拒绝 422")
    void startedMemberRejected() {
        prepareApproved("A", DEV1);
        cutover.startExecution(new StartExecutionRequest("ex-A", "A", BASE));
        assertThatThrownBy(() -> batches.publish(publishReq("BATCH-STARTED", BASE,
                List.of(member("A")), List.of())))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("已批准且未开始");
    }

    @Test
    @DisplayName("成员已属于另一未结束批次：第二次发布整批拒绝 422")
    void memberAlreadyBoundRejected() {
        prepareApproved("A", DEV1);
        prepareApproved("B", DEV2);
        batches.publish(publishReq("BATCH-X", BASE, List.of(member("A")), List.of()));

        assertThatThrownBy(() -> batches.publish(publishReq("BATCH-Y", BASE.plusMinutes(1),
                List.of(member("A"), member("B")), List.of())))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("最多属于一个未结束批次");
    }

    // ---------------- 幂等与指纹 ----------------

    @Test
    @DisplayName("同键同参重放返回首次冻结结果 replayed=true，版本不重复递增")
    void sameKeySameParamsReplay() {
        prepareApproved("A", DEV1);
        var req = publishReq("BATCH-IDEM", BASE, List.of(member("A")), List.of());
        BatchActionResult first = batches.publish(req);
        BatchActionResult replay = batches.publish(req);

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.batch().frozenAt()).isEqualTo(BASE);
        // 重放不重复递增版本、不重复写成员行
        assertThat(cutover.get("A").version()).isEqualTo(4L);
        assertThat(repo.listBatchMembers("BATCH-IDEM")).hasSize(1);
    }

    @Test
    @DisplayName("同键异参（成员版本/依赖集合/发布时刻变化）返回 409")
    void sameKeyDifferentParams409() {
        prepareApproved("A", DEV1);
        prepareApproved("B", DEV2);
        batches.publish(publishReq("BATCH-CONF", BASE,
                List.of(member("A")), List.of()));

        // 发布时刻不同 -> 409
        assertThatThrownBy(() -> batches.publish(publishReq("BATCH-CONF", BASE.plusMinutes(1),
                List.of(member("A")), List.of())))
                .isInstanceOf(ConflictException.class);
        // 成员集合不同 -> 409
        assertThatThrownBy(() -> batches.publish(publishReq("BATCH-CONF", BASE,
                List.of(member("A"), member("B")), List.of())))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("结构化指纹：文本包含分隔符也无法混淆结构，不同载荷指纹必然不同")
    void structuredFingerprintDistinguishesDelimiterText() {
        // 单成员 "A -> B" 无依赖 与 成员 A/B 且有 A->B 依赖，拼接文本可能相同但结构不同
        String flat = FingerprintUtil.sha256("X",
                List.of(List.of("A -> B", 1)), List.of());
        String structured = FingerprintUtil.sha256("X",
                List.of(List.of("A", 1), List.of("B", 1)),
                List.of(List.of("A", "B")));
        assertThat(flat).isNotEqualTo(structured);

        // 合法文本含分隔符 " -> " 仍可正常作为成员发布，不影响边解析
        prepareApproved("A", DEV1);
        prepareApproved("A -> B", DEV2);
        BatchActionResult r = batches.publish(publishReq("BATCH-DELIM", BASE,
                List.of(member("A"), member("A -> B")), List.of(edge("A", "A -> B"))));
        assertThat(r.batch().edges()).hasSize(1);
        assertThat(r.batch().edges().get(0).toRequestId()).isEqualTo("A -> B");
    }

    // ---------------- 执行门禁 ----------------

    @Test
    @DisplayName("门禁：直接前置未全部验证封板返回等待、不写步骤不占键；前置封板后同键启动成功")
    void gateWaitsThenStartsAfterPrerequisiteSealed() {
        prepareApproved("A", DEV1);
        prepareApproved("B", DEV2);
        batches.publish(publishReq("BATCH-GATE", BASE,
                List.of(member("A"), member("B")), List.of(edge("A", "B"))));

        // A 仍 APPROVED，B 启动 -> 等待，不写任何 B 的步骤/事件
        ActionResult waiting = cutover.startExecution(
                new StartExecutionRequest("ex-B", "B", BASE));
        assertThat(waiting.waiting()).isTrue();
        assertThat(waiting.waitingOn()).containsExactly("A");
        assertThat(cutover.get("B").status()).isEqualTo("APPROVED");
        // 等待不写执行步骤：原有计划步骤全部保持 PENDING，无开始/回报时间
        assertThat(repo.listSteps("B")).isNotEmpty().allSatisfy(s -> {
            assertThat(s.getStatus()).isEqualTo("PENDING");
            assertThat(s.getStartedAt()).isNull();
            assertThat(s.getReportedAt()).isNull();
        });
        assertThat(repo.findEvent("ex-B")).isNull();

        // A 执行并全步骤成功、封板
        cutover.startExecution(new StartExecutionRequest("ex-A", "A", BASE));
        cutover.report(new StepReportRequest("r-A1", "A", 1, true, BASE.plusMinutes(10), null));
        cutover.report(new StepReportRequest("r-A2", "A", 2, true, BASE.plusMinutes(20), null));
        cutover.seal(new com.example.starter.cutover.dto.SealRequest("seal-A", "A"));
        assertThat(cutover.get("A").status()).isEqualTo("SEALED");

        // 同键 ex-B 现在首次真正生效（等待未占键）
        ActionResult started = cutover.startExecution(
                new StartExecutionRequest("ex-B", "B", BASE));
        assertThat(started.waiting()).isFalse();
        assertThat(started.request().status()).isEqualTo("EXECUTING");
    }

    @Test
    @DisplayName("门禁：前置仅 VERIFIED 未封板仍等待")
    void verifiedButNotSealedStillWaits() {
        prepareApproved("A", DEV1);
        prepareApproved("B", DEV2);
        batches.publish(publishReq("BATCH-GATE2", BASE,
                List.of(member("A"), member("B")), List.of(edge("A", "B"))));
        cutover.startExecution(new StartExecutionRequest("ex-A2", "A", BASE));
        cutover.report(new StepReportRequest("r-A21", "A", 1, true, BASE.plusMinutes(10), null));
        cutover.report(new StepReportRequest("r-A22", "A", 2, true, BASE.plusMinutes(20), null));
        assertThat(cutover.get("A").status()).isEqualTo("VERIFIED");

        ActionResult waiting = cutover.startExecution(
                new StartExecutionRequest("ex-B2", "B", BASE));
        assertThat(waiting.waiting()).isTrue();
        assertThat(waiting.waitingOn()).containsExactly("A");
    }

    @Test
    @DisplayName("被依赖阻断的成员启动被业务拒绝 422")
    void blockedMemberCannotStart() {
        prepareApproved("A", DEV1);
        prepareApproved("B", DEV2);
        batches.publish(publishReq("BATCH-BLK-START", BASE,
                List.of(member("A"), member("B")), List.of(edge("A", "B"))));
        cutover.startExecution(new StartExecutionRequest("ex-Ax", "A", BASE));
        // A 硬性检查失败回退
        cutover.report(new StepReportRequest("r-Ax1", "A", 1, false, BASE.plusMinutes(5), null));
        assertThat(cutover.get("B").status()).isEqualTo("DEPENDENCY_BLOCKED");

        assertThatThrownBy(() -> cutover.startExecution(
                new StartExecutionRequest("ex-Bx", "B", BASE)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("依赖阻断");
    }

    // ---------------- 失败传播 ----------------

    @Test
    @DisplayName("前置回退：尚未开始的全部可达后继同事务转依赖阻断，来源为失败前置")
    void failurePropagatesAlongChain() {
        prepareApproved("A", DEV1);
        prepareApproved("B", DEV2);
        prepareApproved("C", DEV3);
        batches.publish(publishReq("BATCH-CHAIN", BASE,
                List.of(member("A"), member("B"), member("C")),
                List.of(edge("A", "B"), edge("B", "C"))));

        cutover.startExecution(new StartExecutionRequest("ex-Ac", "A", BASE));
        cutover.report(new StepReportRequest("r-Ac1", "A", 1, false, BASE.plusMinutes(5), null));

        // B、C 均被阻断，首个阻断来源集合都是直接失败原点 A（传播不把中间节点当来源）
        assertThat(cutover.get("B").status()).isEqualTo("DEPENDENCY_BLOCKED");
        assertThat(cutover.get("C").status()).isEqualTo("DEPENDENCY_BLOCKED");
        assertThat(cutover.get("B").blockedSources()).containsExactly("A");
        assertThat(cutover.get("C").blockedSources()).containsExactly("A");
        // B/C 未开始：计划步骤全部保持 PENDING，不写任何执行步骤/回退步骤
        assertThat(repo.listSteps("B")).isNotEmpty()
                .allSatisfy(s -> assertThat(s.getStatus()).isEqualTo("PENDING"));
        assertThat(repo.listSteps("C")).isNotEmpty()
                .allSatisfy(s -> assertThat(s.getStatus()).isEqualTo("PENDING"));
        // 阻断明细落库，传播证据事件存在
        assertThat(batches.blocks("BATCH-CHAIN"))
                .extracting(b -> b.requestId()).containsExactly("B", "C");
        assertThat(repo.listEvents("B").stream()
                .anyMatch(e -> "DEPENDENCY_BLOCKED".equals(e.getEventType()))).isTrue();
    }

    @Test
    @DisplayName("无关支路不受影响：失败支路被阻断，独立支路可继续执行")
    void unrelatedBranchContinues() {
        prepareApproved("A", DEV1);
        prepareApproved("B", DEV2);
        prepareApproved("D", DEV4);
        batches.publish(publishReq("BATCH-BRANCH", BASE,
                List.of(member("A"), member("B"), member("D")),
                List.of(edge("A", "B"))));

        cutover.startExecution(new StartExecutionRequest("ex-Ab", "A", BASE));
        cutover.report(new StepReportRequest("r-Ab1", "A", 1, false, BASE.plusMinutes(5), null));

        assertThat(cutover.get("B").status()).isEqualTo("DEPENDENCY_BLOCKED");
        // D 无任何前置，门禁直接满足，可正常开始
        ActionResult dStart = cutover.startExecution(
                new StartExecutionRequest("ex-D", "D", BASE));
        assertThat(dStart.request().status()).isEqualTo("EXECUTING");
    }

    @Test
    @DisplayName("首个阻断来源集合保留：先失败的前置入集，另一前置随后失败不改写首集")
    void firstBlockingSourceSetRetained() {
        prepareApproved("X", DEV1);
        prepareApproved("Y", DEV2);
        prepareApproved("Z", DEV3);
        batches.publish(publishReq("BATCH-DIAMOND", BASE,
                List.of(member("X"), member("Y"), member("Z")),
                List.of(edge("X", "Z"), edge("Y", "Z"))));

        cutover.startExecution(new StartExecutionRequest("ex-X", "X", BASE));
        cutover.report(new StepReportRequest("r-X1", "X", 1, false, BASE.plusMinutes(5), null));
        assertThat(cutover.get("Z").blockedSources()).containsExactly("X");

        // 随后 Y 也失败：Z 已阻断，首个阻断来源集合仍为 {X}，不被改写
        cutover.startExecution(new StartExecutionRequest("ex-Y", "Y", BASE));
        cutover.report(new StepReportRequest("r-Y1", "Y", 1, false, BASE.plusMinutes(6), null));
        assertThat(cutover.get("Z").blockedSources()).containsExactly("X");
        assertThat(batches.blocks("BATCH-DIAMOND").stream()
                .filter(b -> b.requestId().equals("Z"))
                .findFirst().orElseThrow()
                .sourceRequests()).containsExactly("X");
    }

    @Test
    @DisplayName("前置窗口超期（WINDOW_EXPIRED）同样传播阻断")
    void windowExpiredPropagatesBlock() {
        prepareApproved("A", DEV1);
        prepareApproved("B", DEV2);
        batches.publish(publishReq("BATCH-EXP", BASE,
                List.of(member("A"), member("B")), List.of(edge("A", "B"))));
        cutover.startExecution(new StartExecutionRequest("ex-Ae", "A", BASE));
        // 窗口结束巡检：A 仍有未完成步骤 -> WINDOW_EXPIRED
        ActionResult expired = cutover.expireCheck(new com.example.starter.cutover.dto.ExpireCheckRequest(
                "exp-A", "A", BASE.plusHours(2)));
        assertThat(expired.request().status()).isEqualTo("WINDOW_EXPIRED");
        assertThat(cutover.get("B").status()).isEqualTo("DEPENDENCY_BLOCKED");
        assertThat(cutover.get("B").blockedSources()).containsExactly("A");
    }

    // ---------------- 拓扑 / 批次关闭 ----------------

    @Test
    @DisplayName("拓扑查询按申请编号稳定排序并标注门禁 ready/waitingOn")
    void topologySortedAndGateAnnotated() {
        prepareApproved("A", DEV1);
        prepareApproved("B", DEV2);
        prepareApproved("C", DEV3);
        batches.publish(publishReq("BATCH-TOPO", BASE,
                List.of(member("C"), member("A"), member("B")),
                List.of(edge("A", "B"), edge("B", "C"))));

        TopologyView topo = batches.topology("BATCH-TOPO");
        assertThat(topo.nodes()).extracting(TopologyNodeView::requestId)
                .containsExactly("A", "B", "C");
        TopologyNodeView a = topo.nodes().get(0);
        TopologyNodeView b = topo.nodes().get(1);
        TopologyNodeView c = topo.nodes().get(2);
        assertThat(a.ready()).isTrue(); // A 无前置
        assertThat(a.directPrerequisites()).isEmpty();
        assertThat(b.ready()).isFalse();
        assertThat(b.waitingOn()).containsExactly("A");
        assertThat(b.directPrerequisites()).containsExactly("A");
        assertThat(c.waitingOn()).containsExactly("B");
    }

    @Test
    @DisplayName("全部成员封板后批次 CLOSED，成员 openBatchId 释放")
    void batchClosesWhenAllTerminal() {
        prepareApproved("A", DEV1);
        batches.publish(publishReq("BATCH-CLOSE", BASE,
                List.of(member("A")), List.of()));
        cutover.startExecution(new StartExecutionRequest("ex-Az", "A", BASE));
        cutover.report(new StepReportRequest("r-Az1", "A", 1, true, BASE.plusMinutes(10), null));
        cutover.report(new StepReportRequest("r-Az2", "A", 2, true, BASE.plusMinutes(20), null));
        cutover.seal(new com.example.starter.cutover.dto.SealRequest("seal-Az", "A"));

        assertThat(batches.get("BATCH-CLOSE").status()).isEqualTo("CLOSED");
        assertThat(cutover.get("A").openBatchId()).isNull();
    }

    @Test
    @DisplayName("批次查询不存在返回 404")
    void batchNotFound404() {
        assertThatThrownBy(() -> batches.get("NOPE"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> batches.topology("NOPE"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> batches.blocks("NOPE"))
                .isInstanceOf(NotFoundException.class);
    }
}
