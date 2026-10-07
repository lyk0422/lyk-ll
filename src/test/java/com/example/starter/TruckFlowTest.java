package com.example.starter;

import java.util.ArrayList;
import java.util.List;

import com.example.starter.web.dto.JudgmentRequest;
import com.example.starter.web.dto.JudgmentRequest.JudgmentItem;
import com.example.starter.web.dto.ReceiptRequest;
import com.example.starter.web.dto.ReleaseRequest;
import com.example.starter.web.dto.TruckCreateRequest;
import com.example.starter.web.dto.TruckManifestResponse;
import com.example.starter.web.dto.TruckProgressView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 分车出厂主流程与失败分支测试：四头两车逐次核销末车收口、跨批/病害/占用拒绝、
 * 缺一项全单不占、撤销释放与车号不可复用、版本与终态改单拒绝、原整批入口拒绝、
 * 幂等重放/异参 409/失败不占键、处置与风险门禁阻断、按头只勾稽的进度查询。
 */
class TruckFlowTest extends BaseIntegrationTest {

    private static JudgmentItem q(String headNo) {
        return new JudgmentItem(headNo, "QUALIFIED", null, null);
    }

    private static JudgmentItem r(String headNo) {
        return new JudgmentItem(headNo, "RENDERING", null, null);
    }

    private static List<String> heads(int n) {
        List<String> list = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            list.add(String.format("H%02d", i));
        }
        return list;
    }

    private List<JudgmentItem> qualifiedItems(int n) {
        return heads(n).stream().map(TruckFlowTest::q).toList();
    }

    @Test
    @DisplayName("四头合格分两车逐次核销：首车不代销第二车，末车恰核销完批次转终态")
    void fourHeadsTwoTrucksSequentialDispatchClosesBatch() throws Exception {
        judgeAll("B-4T", "4t", List.of(q("H1"), q("H2"), q("H3"), q("H4")));

        TruckManifestResponse t1 = createTruck("tc-1", "B-4T", "T-1", List.of("H1", "H2"));
        assertEquals(1, t1.version());
        assertEquals("RESERVED", t1.status());
        assertEquals(List.of("H1", "H2"), t1.frozenHeads());
        assertEquals(4, t1.progress().qualifiedTotal());
        assertEquals(0, t1.progress().shippedCount());
        assertEquals(4, t1.progress().unshippedCount());
        assertFalse(t1.progress().batchReleased());

        // 首车核销：只出厂本车两头，第二车头只不得被代销
        TruckManifestResponse d1 = dispatchTruck("td-1", "B-4T", "T-1", 1);
        assertEquals(2, d1.version());
        assertEquals("RELEASED", d1.status());
        assertEquals(2, d1.progress().shippedCount());
        assertEquals(2, d1.progress().unshippedCount());
        assertFalse(d1.progress().batchReleased());
        assertEquals("JUDGED", getView("B-4T").status());

        // 第二车装载剩余两头并核销：恰全部合格头只核销完，批次转最终出厂
        createTruck("tc-2", "B-4T", "T-2", List.of("H3", "H4"));
        TruckManifestResponse d2 = dispatchTruck("td-2", "B-4T", "T-2", 1);
        assertEquals(2, d2.version());
        assertEquals(4, d2.progress().shippedCount());
        assertEquals(0, d2.progress().unshippedCount());
        assertTrue(d2.progress().batchReleased());
        assertEquals("RELEASED", getView("B-4T").status());

        TruckProgressView progress = getTrucks("B-4T");
        assertTrue(progress.enabled());
        assertEquals(List.of("H1", "H2", "H3", "H4"), progress.shipped());
        assertEquals(List.of(), progress.unshipped());
        assertEquals(2, progress.manifests().size());
    }

    @Test
    @DisplayName("跨批头只、病害处置头只与已占用头只拒绝，缺一项全单不占")
    void crossBatchDiseasedOccupiedRejectedAndAllOrNothing() throws Exception {
        judgeAll("B-A", "a", List.of(q("H1"), q("H2")));
        judgeAll("B-B", "b", List.of(q("H3"), q("H4")));

        // 含跨批/台账不存在头只：422，整车单与占用均不落库
        postJson("/api/batches/B-A/trucks",
                new TruckCreateRequest("tc-x", "T-X", List.of("H1", "H3"), clock.instant().toString()))
                .andExpect(status().isUnprocessableEntity());
        assertEquals(0, countRows("truck_manifest"));
        assertEquals(0, countRows("truck_manifest_head"));

        // B 批车单引用 A 批头只 H2：对 B 而言属跨批，拒绝
        postJson("/api/batches/B-B/trucks",
                new TruckCreateRequest("tc-y", "T-Y", List.of("H2", "H3"), clock.instant().toString()))
                .andExpect(status().isUnprocessableEntity());

        // 病害处置（化制）头只不得装车
        judgeAll("B-DIS", "dis", List.of(q("D1"), r("D2")));
        postJson("/api/batches/B-DIS/trucks",
                new TruckCreateRequest("tc-d", "T-D", List.of("D1", "D2"), clock.instant().toString()))
                .andExpect(status().isUnprocessableEntity());
        assertEquals(0, countRows("truck_manifest"));

        // 合法车单预占成功
        createTruck("tc-a1", "B-A", "T-A1", List.of("H1", "H2"));

        // 已被其他未撤销车辆占用的头只拒绝
        postJson("/api/batches/B-A/trucks",
                new TruckCreateRequest("tc-a2", "T-A2", List.of("H2"), clock.instant().toString()))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("车单头只数量与去重校验：空集/超50拒绝，重复编号400")
    void manifestSizeAndDistinctValidation() throws Exception {
        judgeAll("B-V", "v", List.of(q("H1"), q("H2")));

        postJson("/api/batches/B-V/trucks",
                new TruckCreateRequest("tc-empty", "T-E", List.of(), clock.instant().toString()))
                .andExpect(status().isBadRequest());

        List<String> tooMany = new ArrayList<>();
        for (int i = 1; i <= 51; i++) {
            tooMany.add(String.format("X%02d", i));
        }
        postJson("/api/batches/B-V/trucks",
                new TruckCreateRequest("tc-51", "T-51", tooMany, clock.instant().toString()))
                .andExpect(status().isBadRequest());

        postJson("/api/batches/B-V/trucks",
                new TruckCreateRequest("tc-dup", "T-DUP", List.of("H1", "H1"), clock.instant().toString()))
                .andExpect(status().isBadRequest());
        assertEquals(0, countRows("truck_manifest"));
    }

    @Test
    @DisplayName("未发车清单可整体撤销释放，头只释放后可重装，车号不可复用")
    void revokeReleasesHeadsAndTruckNoNotReusable() throws Exception {
        judgeAll("B-RV", "rv", List.of(q("H1"), q("H2"), q("H3")));

        TruckManifestResponse t1 = createTruck("tc-1", "B-RV", "T-1", List.of("H1", "H2"));
        TruckManifestResponse revoked = revokeTruck("tr-1", "B-RV", "T-1", t1.version());
        assertEquals(2, revoked.version());
        assertEquals("REVOKED", revoked.status());
        assertNull(revoked.dispatchedAt());
        assertEquals(0, countRows("truck_manifest_head"), "撤销须删除全部活跃占用行");

        // 释放后的头只可被新车单装载
        createTruck("tc-2", "B-RV", "T-2", List.of("H1", "H2", "H3"));
        // 已撤销车号不可复用
        postJson("/api/batches/B-RV/trucks",
                new TruckCreateRequest("tc-3", "T-1", List.of("H1"), clock.instant().toString()))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("版本自1起旧版409；已发车不可撤销、已撤销不可发车，不能事后改单")
    void versionConflictAndTerminalManifestsNotModifiable() throws Exception {
        judgeAll("B-MOD", "mod", List.of(q("H1"), q("H2")));
        createTruck("tc-1", "B-MOD", "T-1", List.of("H1"));

        // 旧版本发车/撤销均 409
        postJson("/api/batches/B-MOD/trucks/T-1/dispatch", action("td-bad", 5))
                .andExpect(status().isConflict());
        postJson("/api/batches/B-MOD/trucks/T-1/revoke", action("tr-bad", 5))
                .andExpect(status().isConflict());

        // 正确版本发车成功，版本加 1
        TruckManifestResponse dispatched = dispatchTruck("td-1", "B-MOD", "T-1", 1);
        assertEquals(2, dispatched.version());
        // 已发车车辆不可撤销
        postJson("/api/batches/B-MOD/trucks/T-1/revoke", action("tr-2", 2))
                .andExpect(status().isConflict());

        // 另一车撤销后不可再发车
        createTruck("tc-2", "B-MOD", "T-2", List.of("H2"));
        revokeTruck("tr-3", "B-MOD", "T-2", 1);
        postJson("/api/batches/B-MOD/trucks/T-2/dispatch", action("td-3", 2))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("已启用分车批原整批入口拒绝，未启用批兼容原入口")
    void wholeBatchReleaseRejectedWhenSplitEnabled() throws Exception {
        judgeAll("B-SPLIT", "split", List.of(q("H1")));
        createTruck("tc-1", "B-SPLIT", "T-1", List.of("H1"));
        postJson("/api/batches/B-SPLIT/release", new ReleaseRequest("rel-1", "核销员甲"))
                .andExpect(status().isConflict());
        assertEquals("JUDGED", getView("B-SPLIT").status());

        // 从未建过车单的批次仍走原整批核销（无病害头只，勾稽天然恰等）
        judgeAll("B-PLAIN", "plain", List.of(q("P1"), q("P2")));
        postJson("/api/batches/B-PLAIN/release", new ReleaseRequest("rel-2", "核销员甲"))
                .andExpect(status().isOk());
        assertEquals("RELEASED", getView("B-PLAIN").status());
    }

    @Test
    @DisplayName("分车幂等：同参重放原响应、异参409、失败不占键")
    void idempotentReplayConflictAndFailureDoesNotOccupyKey() throws Exception {
        judgeAll("B-IDEM", "idem", List.of(q("H1"), q("H2"), q("H9")));

        TruckCreateRequest req = new TruckCreateRequest("tc-same", "T-1", List.of("H1", "H2"),
                clock.instant().toString());
        String first = postJson("/api/batches/B-IDEM/trucks", req)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String replay = postJson("/api/batches/B-IDEM/trucks", req)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(first, replay);
        assertEquals(1, countRows("truck_manifest"));

        // 同键异参 409
        postJson("/api/batches/B-IDEM/trucks",
                new TruckCreateRequest("tc-same", "T-9", List.of("H1"), clock.instant().toString()))
                .andExpect(status().isConflict());

        // 失败不占键：同一 requestId 先携带非法头只失败，再以合法参数成功
        postJson("/api/batches/B-IDEM/trucks",
                new TruckCreateRequest("tc-reuse", "T-BAD", List.of("H1", "HX"),
                        clock.instant().toString()))
                .andExpect(status().isUnprocessableEntity());
        TruckManifestResponse reused = postOk("/api/batches/B-IDEM/trucks",
                new TruckCreateRequest("tc-reuse", "T-OK", List.of("H9"), clock.instant().toString()),
                TruckManifestResponse.class);
        assertEquals("T-OK", reused.truckNo());

        // 发车同键同参重放；新 requestId 重复核销按业务幂等返回当前已发车车单
        String d1 = postJson("/api/batches/B-IDEM/trucks/T-OK/dispatch", action("td-same", 1))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String d1Replay = postJson("/api/batches/B-IDEM/trucks/T-OK/dispatch", action("td-same", 1))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(d1, d1Replay);
        TruckManifestResponse again = dispatchTruck("td-again", "B-IDEM", "T-OK", 2);
        assertEquals("RELEASED", again.status());
        assertEquals(2, again.version());
    }

    @Test
    @DisplayName("同requestId同体作用于不同车单判异参409，不跨车误重放")
    void sameRequestIdDifferentTruckConflicts() throws Exception {
        judgeAll("B-XFP", "xfp", List.of(q("H1"), q("H2")));
        createTruck("tc-a", "B-XFP", "T-A", List.of("H1"));
        createTruck("tc-b", "B-XFP", "T-B", List.of("H2"));

        String first = postJson("/api/batches/B-XFP/trucks/T-A/dispatch", action("td-x", 1))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        // 相同 requestId 与请求体但作用于另一车单：指纹含车号，须 409
        postJson("/api/batches/B-XFP/trucks/T-B/dispatch", action("td-x", 1))
                .andExpect(status().isConflict());
        // T-B 未被误核销，换 requestId 后正常发车
        TruckManifestResponse b = dispatchTruck("td-b", "B-XFP", "T-B", 1);
        assertEquals("RELEASED", b.status());
        assertTrue(b.progress().batchReleased(), "两车均核销后批次收口");
    }

    @Test
    @DisplayName("无害化处置回执不勾稽时逐车核销拒绝并保留预占，补足后放行且末车收口")
    void receiptGateBlocksDispatchUntilMatched() throws Exception {
        judgeAll("B-GATE", "gate", List.of(q("G1"), r("G2")));
        createTruck("tc-1", "B-GATE", "T-1", List.of("G1"));

        // 化制判定 1 头但无回执：处置门禁不满足，拒绝发车，车单与预占保留
        postJson("/api/batches/B-GATE/trucks/T-1/dispatch", action("td-1", 1))
                .andExpect(status().isConflict());
        assertEquals("RESERVED", getTrucks("B-GATE").manifests().get(0).status());
        assertEquals(1, countRows("truck_manifest_head"));
        assertEquals("JUDGED", getView("B-GATE").status());

        // 补足恰等回执后放行；病害头只不计入未出厂，合格头只恰核销完批次收口
        postOk("/api/batches/B-GATE/receipts",
                new ReceiptRequest("rc-1", "RC-1", "RENDERING", 1, "合成熟处理厂"),
                com.example.starter.web.dto.ReceiptResponse.class);
        TruckManifestResponse dispatched = dispatchTruck("td-2", "B-GATE", "T-1", 1);
        assertEquals("RELEASED", dispatched.status());
        assertTrue(dispatched.progress().batchReleased());
        assertEquals(1, dispatched.progress().qualifiedTotal());
        assertEquals(0, dispatched.progress().unshippedCount());
        assertEquals("RELEASED", getView("B-GATE").status());
    }

    @Test
    @DisplayName("风险抽检未完成或阳性时逐车核销阻断，全阴完成后放行")
    void riskGateBlocksDispatchUntilCompletedNegative() throws Exception {
        judgeAll("B-RISK", "risk", qualifiedItems(10));
        bindRisk("rb-1", "B-RISK", "NORMAL", "RULE-1", heads(10), 0);
        createTruck("tc-1", "B-RISK", "T-1", heads(5));

        // 抽检清单（H01）尚无结果：门禁未解除，拒绝发车，预占保留
        postJson("/api/batches/B-RISK/trucks/T-1/dispatch", action("td-0", 1))
                .andExpect(status().isConflict());
        assertEquals("RESERVED", getTrucks("B-RISK").manifests().get(0).status());

        // 全阴且完成：放行首车（未全部合格头只核销完，批次不到终态）
        riskResult("rr-1", "B-RISK", "H01", "NEGATIVE", 1);
        TruckManifestResponse dispatched = dispatchTruck("td-1", "B-RISK", "T-1", 1);
        assertEquals("RELEASED", dispatched.status());
        assertFalse(dispatched.progress().batchReleased());
        assertEquals(5, dispatched.progress().unshippedCount());

        // 另一受控批检出阳性：专项隔离，逐车核销拒绝
        judgeAll("B-POS", "pos", qualifiedItems(10));
        bindRisk("rb-2", "B-POS", "NORMAL", "RULE-1", heads(10), 0);
        createTruck("tc-p", "B-POS", "T-P", heads(10));
        riskResult("rr-p", "B-POS", "H01", "POSITIVE", 1);
        postJson("/api/batches/B-POS/trucks/T-P/dispatch", action("td-p", 1))
                .andExpect(status().isConflict());
        assertEquals("SPECIAL_QUARANTINE", getView("B-POS").status());
        assertEquals("RESERVED", getTrucks("B-POS").manifests().get(0).status());
    }

    @Test
    @DisplayName("专项隔离批有合格头只且未出厂：可建清单预占与撤销，但发车被风险门禁阻断")
    void quarantineBatchCanReserveAndRevokeButCannotDispatch() throws Exception {
        // 判定完成后绑定抽检并检出阳性，批次进入专项隔离（四类判定保留）
        judgeAll("B-SQ", "sq", qualifiedItems(6));
        bindRisk("rb-1", "B-SQ", "NORMAL", "RULE-1", heads(6), 0);
        riskResult("rr-1", "B-SQ", "H01", "POSITIVE", 1);
        assertEquals("SPECIAL_QUARANTINE", getView("B-SQ").status());

        // 隔离态仍可建立清单并全量预占合格头只
        TruckManifestResponse created = createTruck("tc-1", "B-SQ", "T-1", heads(6));
        assertEquals("RESERVED", created.status());
        assertEquals(6, countRows("truck_manifest_head"));

        // 发车被风险门禁阻断，预占保留
        postJson("/api/batches/B-SQ/trucks/T-1/dispatch", action("td-1", 1))
                .andExpect(status().isConflict());
        assertEquals("RESERVED", getTrucks("B-SQ").manifests().get(0).status());
        assertEquals(6, countRows("truck_manifest_head"));

        // 未发车清单仍可整体撤销释放
        TruckManifestResponse revoked = revokeTruck("tr-1", "B-SQ", "T-1", 1);
        assertEquals("REVOKED", revoked.status());
        assertEquals(0, countRows("truck_manifest_head"));
        assertEquals("SPECIAL_QUARANTINE", getView("B-SQ").status(), "撤销不改变隔离状态");
    }

    @Test
    @DisplayName("进度查询按头只勾稽：病害头只不计入未出厂，首车后集合精确")
    void progressTracksByHeadNotByTruck() throws Exception {
        judgeAll("B-PG", "pg", List.of(q("P1"), q("P2"), q("P3"), r("P4")));
        postOk("/api/batches/B-PG/receipts",
                new ReceiptRequest("rc-1", "RC-1", "RENDERING", 1, "合成熟处理厂"),
                com.example.starter.web.dto.ReceiptResponse.class);

        createTruck("tc-1", "B-PG", "T-1", List.of("P1", "P3"));
        TruckProgressView before = getTrucks("B-PG");
        assertEquals(List.of("P1", "P2", "P3"), before.qualified());
        assertEquals(List.of(), before.shipped());
        assertEquals(List.of("P1", "P2", "P3"), before.unshipped());
        assertEquals(3, before.progress().qualifiedTotal(), "病害头只 P4 不计入合格/未出厂");

        dispatchTruck("td-1", "B-PG", "T-1", 1);
        TruckProgressView afterFirst = getTrucks("B-PG");
        assertEquals(List.of("P1", "P3"), afterFirst.shipped());
        assertEquals(List.of("P2"), afterFirst.unshipped());
        assertEquals(2, afterFirst.progress().shippedCount());
        assertEquals(1, afterFirst.progress().unshippedCount());
    }

    private com.example.starter.web.dto.TruckManifestActionRequest action(String requestId,
                                                                          int expectedVersion) {
        return new com.example.starter.web.dto.TruckManifestActionRequest(requestId, expectedVersion,
                clock.instant().toString());
    }
}
