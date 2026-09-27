package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.dto.OrderDetailResponse;
import com.example.starter.dto.PlanDetailResponse;
import com.example.starter.dto.PlanOrderReceiveRequest;
import com.example.starter.dto.PlanOrderReceiveResponse;
import com.example.starter.dto.PlanPublishRequest;
import com.example.starter.dto.PlanPublishResponse;
import com.example.starter.dto.PlanVersionResponse;
import com.example.starter.dto.ReportRequest;
import com.example.starter.web.ApiBadRequestException;
import com.example.starter.web.ApiConflictException;
import com.example.starter.web.ApiNotFoundException;
import com.example.starter.web.ApiUnprocessableException;

/**
 * 日前计划版本与计划绑定指令 H2 事务集成测试：主流程、版本原子替换与历史保留、
 * 整版 422 失败不占键、过期版本拒绝、基数冻结与新版本不重算、超时重分摊引用原计划、
 * 幂等重放与精确时刻指纹。
 */
@SpringBootTest
class PlanFlowIntegrationTest extends AbstractIntegrationTest {

    private static final String DATE = PlanFixtures.DATE;
    private static final Instant T0 = PlanFixtures.OP_TIME;

    @Autowired
    private PlanService planService;

    @Autowired
    private DispatchService dispatchService;

    @BeforeEach
    void setUp() {
        resetDb();
        pinClock(10, 0, 0);
    }

    private PlanPublishRequest planV1(String requestId, int expectedVersion) {
        // 三机组额定 2000/2000/3000，实时可用 2000/1600/2400。
        // 计划取 T-01=1500（低于实时），T-02=1800（高于实时），T-03=2400。
        return PlanFixtures.uniformPlan(requestId, FARM, expectedVersion, T0,
                "T-01", "1500", "T-02", "1800", "T-03", "2400");
    }

    @Test
    @DisplayName("发布首版：版本号从 1 开始，含 96 段×3 机组，查询为当前版本")
    void publishFirstVersion() {
        PlanPublishResponse resp = planService.publish(planV1("plan-pub-1", 0));

        assertThat(resp.versionNo()).isEqualTo(1);
        assertThat(resp.current()).isTrue();
        assertThat(resp.segmentCount()).isEqualTo(96);
        assertThat(resp.turbineCount()).isEqualTo(3);
        assertThat(resp.planId()).isNotNull();

        Integer entries = jdbc.queryForObject(
                "SELECT COUNT(*) FROM day_ahead_plan_entry WHERE plan_id = ?",
                Integer.class, resp.planId());
        assertThat(entries).isEqualTo(96 * 3);

        PlanVersionResponse versions = planService.versions(FARM, DATE);
        assertThat(versions.currentVersionNo()).isEqualTo(1);
        assertThat(versions.versions()).hasSize(1);

        PlanDetailResponse detail = planService.detail(resp.planId());
        assertThat(detail.current()).isTrue();
        assertThat(detail.segments()).hasSize(96);
        assertThat(detail.segments().get(10).entries()).hasSize(3);
    }

    @Test
    @DisplayName("发布新版本：原子替换当前指针，旧版本保留为历史，版本号 +1")
    void publishNewVersionSwapsCurrentAndKeepsHistory() {
        PlanPublishResponse v1 = planService.publish(planV1("plan-pub-a", 0));
        PlanPublishRequest v2req = PlanFixtures.uniformPlan(
                "plan-pub-b", FARM, 1, Instant.parse("2026-09-26T02:00:00Z"),
                "T-01", "1400", "T-02", "1700", "T-03", "2300");
        PlanPublishResponse v2 = planService.publish(v2req);

        assertThat(v2.versionNo()).isEqualTo(2);
        assertThat(v2.planId()).isNotEqualTo(v1.planId());

        PlanVersionResponse versions = planService.versions(FARM, DATE);
        assertThat(versions.currentVersionNo()).isEqualTo(2);
        assertThat(versions.versions()).hasSize(2);
        long currentCount = versions.versions().stream().filter(PlanVersionResponse.VersionSummary::current).count();
        assertThat(currentCount).isEqualTo(1);

        // 旧版本不可变且仍可查询，current=false；新条目独立。
        PlanDetailResponse oldDetail = planService.detail(v1.planId());
        assertThat(oldDetail.current()).isFalse();
        assertThat(oldDetail.versionNo()).isEqualTo(1);
        assertThat(oldDetail.segments().get(0).entries().stream()
                .filter(e -> e.turbineCode().equals("T-01")).findFirst().orElseThrow().plannedPowerKw())
                .isEqualTo(1500);

        // 库内恰一个当前版本。
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM day_ahead_plan WHERE farm_code=? AND plan_date=? AND current_flag='Y'",
                Integer.class, FARM, DATE)).isEqualTo(1);
    }

    @Test
    @DisplayName("expectedVersion 过期/错误：409，且不产生新版本")
    void staleExpectedVersionConflict() {
        planService.publish(planV1("plan-pub-c", 0));
        // 已到版本 1，仍以 expectedVersion=0 发布 → 409。
        assertThatThrownBy(() -> planService.publish(planV1("plan-pub-d", 0)))
                .isInstanceOf(ApiConflictException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM day_ahead_plan", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("同键同参重放返回首次版本；异参（计划值变化）409")
    void publishIdempotentReplayAndConflict() {
        PlanPublishRequest req = planV1("plan-pub-e", 0);
        PlanPublishResponse first = planService.publish(req);
        PlanPublishResponse replay = planService.publish(req);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.planId()).isEqualTo(first.planId());
        assertThat(replay.versionNo()).isEqualTo(1);

        PlanPublishRequest changed = PlanFixtures.uniformPlan("plan-pub-e", FARM, 0, T0,
                "T-01", "1499", "T-02", "1800", "T-03", "2400");
        assertThatThrownBy(() -> planService.publish(changed))
                .isInstanceOf(ApiConflictException.class);
    }

    @Test
    @DisplayName("整版拒绝：缺段/重复段/段内重复机组/超装机/机组集合不一致 → 422，且不占 requestId")
    void invalidWholePlanRejected422AndKeyNotOccupied() {
        // 缺段（95 段）。
        PlanPublishRequest missing = PlanFixtures.withoutSegment(planV1("plan-bad-1", 0), 50);
        assertThatThrownBy(() -> planService.publish(missing))
                .isInstanceOf(ApiUnprocessableException.class);

        // 重复时段。
        PlanPublishRequest dupSeg = PlanFixtures.withDuplicateSegmentIndex(planV1("plan-bad-2", 0), 50, 49);
        assertThatThrownBy(() -> planService.publish(dupSeg))
                .isInstanceOf(ApiUnprocessableException.class);

        // 段内重复机组。
        PlanPublishRequest dupTurbine = PlanFixtures.withOverriddenSegment(planV1("plan-bad-3", 0), 10,
                List.of(new PlanPublishRequest.EntryInput("T-01", 100),
                        new PlanPublishRequest.EntryInput("T-01", 200),
                        new PlanPublishRequest.EntryInput("T-03", 2400)));
        assertThatThrownBy(() -> planService.publish(dupTurbine))
                .isInstanceOf(ApiUnprocessableException.class);

        // 超装机（T-03 额定 3000）。
        PlanPublishRequest overCap = PlanFixtures.withOverriddenSegment(planV1("plan-bad-4", 0), 10,
                List.of(new PlanPublishRequest.EntryInput("T-01", 1500),
                        new PlanPublishRequest.EntryInput("T-02", 1800),
                        new PlanPublishRequest.EntryInput("T-03", 3001)));
        assertThatThrownBy(() -> planService.publish(overCap))
                .isInstanceOf(ApiUnprocessableException.class);

        // 机组集合不一致（缺 T-02，无法覆盖全部机组）。
        PlanPublishRequest wrongSet = PlanFixtures.withOverriddenSegment(planV1("plan-bad-5", 0), 10,
                List.of(new PlanPublishRequest.EntryInput("T-01", 1500),
                        new PlanPublishRequest.EntryInput("T-03", 2400)));
        assertThatThrownBy(() -> planService.publish(wrongSet))
                .isInstanceOf(ApiUnprocessableException.class);

        // 全部失败：无版本、无条目落库。
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM day_ahead_plan", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM day_ahead_plan_entry", Integer.class)).isZero();

        // 失败不占键：用 plan-bad-1 同键发布合法计划应成功（首版）。
        PlanPublishResponse retry = planService.publish(planV1("plan-bad-1", 0));
        assertThat(retry.versionNo()).isEqualTo(1);
    }

    @Test
    @DisplayName("非法日期 400；风场不存在发布 400；查询不存在版本/日期 404")
    void badRequestAndNotFoundMappings() {
        PlanPublishRequest badDate = new PlanPublishRequest("p", FARM, "2026/09/27", 0, T0,
                planV1("p", 0).segments());
        assertThatThrownBy(() -> planService.publish(badDate))
                .isInstanceOf(ApiBadRequestException.class);

        PlanPublishRequest noFarm = PlanFixtures.uniformPlan("p2", "NO-FARM", 0, T0,
                "T-01", "100");
        assertThatThrownBy(() -> planService.publish(noFarm))
                .isInstanceOf(ApiBadRequestException.class);

        assertThatThrownBy(() -> planService.versions(FARM, DATE))
                .isInstanceOf(ApiNotFoundException.class);
        assertThatThrownBy(() -> planService.detail(999_999L))
                .isInstanceOf(ApiNotFoundException.class);
    }

    @Test
    @DisplayName("绑定指令主流程：可参与出力=min(实时,计划)，冻结基数，分摊合计恰为目标")
    void bindOrderFreezesBasisAndAllocates() {
        planService.publish(planV1("plan-o-1", 0));
        Instant op = Instant.parse("2026-09-27T02:30:00Z"); // UTC，时段 10（02:30 起）
        // 实时可用 2000/1600/2400；计划 1500/1800/2400 → 可参与 1500/1600/2400，合计 5500。
        PlanOrderReceiveRequest req = new PlanOrderReceiveRequest(
                "po-1", FARM, 4000, DATE, 10, 1, op);
        PlanOrderReceiveResponse resp = planService.receivePlanOrder(req);

        assertThat(resp.status()).isEqualTo("DISPATCHED");
        assertThat(resp.versionNo()).isEqualTo(1);
        assertThat(resp.segmentIndex()).isEqualTo(10);
        assertThat(resp.participatingTotalKw()).isEqualTo(5500);
        assertThat(resp.curtailedKw()).isEqualTo(1500);

        Map<String, Integer> participating = resp.basis().stream()
                .collect(Collectors.toMap(b -> b.turbineCode(), b -> b.participatingKw()));
        assertThat(participating).containsEntry("T-01", 1500)
                .containsEntry("T-02", 1600)
                .containsEntry("T-03", 2400);
        int targetSum = resp.basis().stream().mapToInt(b -> b.targetPowerKw()).sum();
        assertThat(targetSum).isEqualTo(4000);
        // 最低保留：单机目标不低于技术下限 300/300/450。
        resp.basis().forEach(b ->
                assertThat(b.targetPowerKw()).isGreaterThanOrEqualTo(b.technicalMinKw()));

        // 指令与冻结基数证据落库，绑定版本 1。
        var row = jdbc.queryForMap(
                "SELECT plan_id, plan_date, segment_index FROM curtail_order WHERE id = ?",
                resp.orderId());
        assertThat(row.get("SEGMENT_INDEX")).isEqualTo(10);
        Integer basisCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_plan_basis WHERE order_id = ?",
                Integer.class, resp.orderId());
        assertThat(basisCount).isEqualTo(3);

        OrderDetailResponse detail = dispatchService.detail(resp.orderNo());
        assertThat(detail.planBinding()).isNotNull();
        assertThat(detail.planBinding().versionNo()).isEqualTo(1);
        assertThat(detail.frozenBasis()).hasSize(3);
    }

    @Test
    @DisplayName("绑定后发布新版本：已受理指令基数与绑定版本不变，不被重算")
    void laterPublishDoesNotRecomputeAcceptedOrder() {
        planService.publish(planV1("plan-o-2a", 0));
        Instant op = Instant.parse("2026-09-27T02:30:00Z");
        PlanOrderReceiveResponse order = planService.receivePlanOrder(
                new PlanOrderReceiveRequest("po-2", FARM, 4000, DATE, 10, 1, op));
        long boundPlanId = order.planId();

        // 之后发布版本 2（计划大幅变化）。
        planService.publish(PlanFixtures.uniformPlan("plan-o-2b", FARM, 1,
                Instant.parse("2026-09-26T03:00:00Z"),
                "T-01", "200", "T-02", "200", "T-03", "300"));

        // 已受理指令仍绑定版本 1，冻结基数不变。
        OrderDetailResponse detail = dispatchService.detail(order.orderNo());
        assertThat(detail.planBinding().planId()).isEqualTo(boundPlanId);
        assertThat(detail.planBinding().versionNo()).isEqualTo(1);
        int basisPlannedSum = detail.frozenBasis().stream()
                .mapToInt(OrderDetailResponse.FrozenBasisView::plannedPowerKw).sum();
        assertThat(basisPlannedSum).isEqualTo(1500 + 1800 + 2400);
        // 首轮分摊不变。
        int cycle1Sum = detail.cycles().get(0).allocations().stream()
                .mapToInt(a -> a.targetPowerKw()).sum();
        assertThat(cycle1Sum).isEqualTo(4000);
    }

    @Test
    @DisplayName("过期（历史）版本不允许新指令绑定：422")
    void cannotBindHistoricalVersion() {
        planService.publish(planV1("plan-o-3a", 0));
        planService.publish(PlanFixtures.uniformPlan("plan-o-3b", FARM, 1,
                Instant.parse("2026-09-26T03:00:00Z"),
                "T-01", "1400", "T-02", "1700", "T-03", "2300"));
        Instant op = Instant.parse("2026-09-27T02:30:00Z");
        // 当前为版本 2，仍请求绑定版本 1 → 422。
        assertThatThrownBy(() -> planService.receivePlanOrder(
                new PlanOrderReceiveRequest("po-3", FARM, 4000, DATE, 10, 1, op)))
                .isInstanceOf(ApiUnprocessableException.class);
    }

    @Test
    @DisplayName("无已发布计划即绑定：422；目标高于可参与出力或低于下限：422 整单拒绝不占键")
    void bindRejections422AndKeyNotOccupied() {
        Instant op = Instant.parse("2026-09-27T02:30:00Z");
        assertThatThrownBy(() -> planService.receivePlanOrder(
                new PlanOrderReceiveRequest("po-x", FARM, 4000, DATE, 10, 1, op)))
                .isInstanceOf(ApiUnprocessableException.class);

        planService.publish(planV1("plan-o-4", 0));
        // 可参与合计 5500：目标 5501 高于可参与 → 422。
        assertThatThrownBy(() -> planService.receivePlanOrder(
                new PlanOrderReceiveRequest("po-4a", FARM, 5501, DATE, 10, 1, op)))
                .isInstanceOf(ApiUnprocessableException.class);
        // 低于可实现下限（min(参与,技术下限)：300+300+450=1050）→ 422。
        assertThatThrownBy(() -> planService.receivePlanOrder(
                new PlanOrderReceiveRequest("po-4b", FARM, 1049, DATE, 10, 1, op)))
                .isInstanceOf(ApiUnprocessableException.class);

        // 整单拒绝：无指令、无冻结基数、无幂等占位。
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM curtail_order", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_plan_basis", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_record WHERE request_id='po-4a'", Integer.class)).isZero();

        // 不占键：同键改合法目标后成功。
        PlanOrderReceiveResponse ok = planService.receivePlanOrder(
                new PlanOrderReceiveRequest("po-4a", FARM, 4000, DATE, 10, 1, op));
        assertThat(ok.targetPowerKw()).isEqualTo(4000);
    }

    @Test
    @DisplayName("绑定指令同键同参重放稳定；异参（含精确时刻）409")
    void bindOrderIdempotentReplayAndConflict() {
        planService.publish(planV1("plan-o-5", 0));
        Instant op = Instant.parse("2026-09-27T02:30:00Z");
        PlanOrderReceiveRequest req =
                new PlanOrderReceiveRequest("po-5", FARM, 4000, DATE, 10, 1, op);
        PlanOrderReceiveResponse first = planService.receivePlanOrder(req);
        PlanOrderReceiveResponse replay = planService.receivePlanOrder(req);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.orderId()).isEqualTo(first.orderId());
        assertThat(replay.basis()).isEqualTo(first.basis());

        // 仅精确时刻不同 → 指纹不同 → 409。
        PlanOrderReceiveRequest changedTime = new PlanOrderReceiveRequest(
                "po-5", FARM, 4000, DATE, 10, 1,
                Instant.parse("2026-09-27T02:30:01Z"));
        assertThatThrownBy(() -> planService.receivePlanOrder(changedTime))
                .isInstanceOf(ApiConflictException.class);
    }

    @Test
    @DisplayName("超时重分摊引用原计划：仅以最新实时可用重取 min，新版本计划不影响已受理指令")
    void timeoutReallocationReferencesOriginalPlan() {
        // 版本 1 计划把 T-01 限到 1500；受理时 T-01 实时 2000 → 参与 1500。
        planService.publish(planV1("plan-o-6", 0));
        Instant op = Instant.parse("2026-09-27T02:30:00Z");
        PlanOrderReceiveResponse order = planService.receivePlanOrder(
                new PlanOrderReceiveRequest("po-6", FARM, 4000, DATE, 10, 1, op));

        // 发布版本 2，把 T-01 计划抬到 2000（若误引用新版会改变上限）。
        planService.publish(PlanFixtures.uniformPlan("plan-o-6b", FARM, 1,
                Instant.parse("2026-09-26T03:00:00Z"),
                "T-01", "2000", "T-02", "1800", "T-03", "2400"));

        // 在 due 之前先刷新 T-02/T-03 的实时可用出力（此时不告警，但可用值已更新）。
        Instant before = Instant.parse("2026-09-27T02:35:00Z");
        clock.pinAt(before);
        dispatchService.report(new ReportRequest("rp-6-2pre", "T-02", before, 2000));
        dispatchService.report(new ReportRequest("rp-6-3pre", "T-03", before, 3000));

        // 恰满 10 分钟（op+10m=02:40Z）回报 T-01：触发唯一一次告警重分摊，三台实时均已刷新。
        Instant due = Instant.parse("2026-09-27T02:40:00Z");
        clock.pinAt(due);
        dispatchService.report(new ReportRequest("rp-6-1", "T-01", due, 2000));

        OrderDetailResponse detail = dispatchService.detail(order.orderNo());
        // 触发了告警与第 2 轮重分摊。
        assertThat(detail.alerts()).hasSize(1);
        assertThat(detail.currentCycleNo()).isEqualTo(2);
        assertThat(detail.cycles()).hasSize(2);

        // 第 2 轮按“原计划上限”重取 min：
        // T-01 min(2000, 原计划1500)=1500；T-02 min(2000,1800)=1800；T-03 min(3000,2400)=2400；合计 5700。
        var cycle2 = detail.cycles().get(1).allocations();
        int cycle2AvailableSum = cycle2.stream().mapToInt(a -> a.availablePowerKw()).sum();
        assertThat(cycle2AvailableSum).isEqualTo(5700);
        int cycle2TargetSum = cycle2.stream().mapToInt(a -> a.targetPowerKw()).sum();
        assertThat(cycle2TargetSum).isEqualTo(4000);
        var t1 = cycle2.stream().filter(a -> a.turbineCode().equals("T-01")).findFirst().orElseThrow();
        // 关键：T-01 仍受原计划 1500 封顶，而非新版 2000 或纯实时 2000。
        assertThat(t1.availablePowerKw()).isEqualTo(1500);
    }

    @Test
    @DisplayName("未绑定计划的原指令行为不变：planBinding/frozenBasis 为 null")
    void unboundOrderUnchanged() {
        var resp = dispatchService.receive(
                new com.example.starter.dto.OrderReceiveRequest("raw-1", FARM, 5000));
        OrderDetailResponse detail = dispatchService.detail(resp.orderNo());
        assertThat(detail.planBinding()).isNull();
        assertThat(detail.frozenBasis()).isNull();
    }
}
