package com.example.starter;

import java.util.ArrayList;
import java.util.List;

import com.example.starter.web.dto.BatchView;
import com.example.starter.web.dto.JudgmentRequest;
import com.example.starter.web.dto.ReceiptRequest;
import com.example.starter.web.dto.ShipmentProgressView;
import com.example.starter.web.dto.SplitEnableRequest;
import com.example.starter.web.dto.SplitEnableResponse;
import com.example.starter.web.dto.VehicleCreateRequest;
import com.example.starter.web.dto.VehicleDepartRequest;
import com.example.starter.web.dto.VehicleRevokeRequest;
import com.example.starter.web.dto.VehicleView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 分车出厂主流程与失败分支：四头分两车逐次核销、末车收口、跨批/病害头只拒绝、
 * 全量预占缺一项全单不占、未发车撤销释放、车单版本与整批入口门禁、幂等指纹。
 */
class ShipmentFlowTest extends BaseIntegrationTest {

    private static List<String> heads(int n) {
        List<String> list = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            list.add(String.format("H%02d", i));
        }
        return list;
    }

    private List<String> readyAllQualified(String batchNo, int n) throws Exception {
        List<String> hs = heads(n);
        List<String> categories = hs.stream().map(h -> "QUALIFIED").toList();
        return readyJudgedBatch(batchNo, hs, categories);
    }

    @Test
    @DisplayName("四头合格分两车：逐次核销，首车不收口，末车后批次才转最终出厂")
    void fourHeadsTwoVehiclesSequentialClose() throws Exception {
        readyAllQualified("B-SHIP", 4);
        SplitEnableResponse enabled = enableSplit("se-1", "B-SHIP");
        assertTrue(enabled.splitEnabled());
        assertEquals(4, enabled.qualifiedTotal());

        VehicleView v1 = createVehicle("vc-1", "B-SHIP", "京A-001", List.of("H01", "H02"), 0);
        VehicleView v2 = createVehicle("vc-2", "B-SHIP", "京A-002", List.of("H03", "H04"), 0);
        assertEquals(1, v1.version());
        assertEquals(1, v2.version());
        assertEquals("RESERVED", v1.status());
        assertEquals(List.of("H01", "H02"), v1.frozenHeads());

        ShipmentProgressView before = getShipment("B-SHIP");
        assertEquals(4, before.qualifiedTotal());
        assertEquals(0, before.departedCount());
        assertEquals(4, before.unshippedCount());
        assertEquals(4, before.reservedHeads().size());
        assertFalse(before.allShipped());
        assertEquals(2, before.vehicles().size());

        // 首车核销：仅 2 头出厂，批次仍 JUDGED，未把其余合格头只算出厂
        VehicleView d1 = departVehicle("vd-1", "B-SHIP", "京A-001", 1);
        assertEquals("DEPARTED", d1.status());
        assertEquals(2, d1.version());
        assertFalse(d1.batchReleased());
        assertEquals("JUDGED", d1.batchStatus());
        assertEquals("JUDGED", getView("B-SHIP").status());

        ShipmentProgressView mid = getShipment("B-SHIP");
        assertEquals(List.of("H01", "H02"), mid.departedHeads());
        assertEquals(List.of("H03", "H04"), mid.unshippedHeads());
        assertEquals(2, mid.departedCount());
        assertEquals(2, mid.unshippedCount());
        assertFalse(mid.allShipped());

        // 末车核销：全部合格头只恰核销完，批次转 RELEASED
        VehicleView d2 = departVehicle("vd-2", "B-SHIP", "京A-002", 1);
        assertTrue(d2.batchReleased());
        assertEquals("RELEASED", d2.batchStatus());
        ShipmentProgressView after = getShipment("B-SHIP");
        assertEquals(4, after.departedCount());
        assertEquals(0, after.unshippedCount());
        assertTrue(after.allShipped());
        assertEquals("RELEASED", getView("B-SHIP").status());
    }

    @Test
    @DisplayName("病害处置头只不能分车，且不计入未出厂头只数")
    void diseaseHeadsRejectedAndExcludedFromUnshipped() throws Exception {
        List<String> hs = List.of("H01", "H02", "H03", "H04");
        List<String> categories = List.of("QUALIFIED", "QUALIFIED", "RENDERING", "DESTRUCTION");
        readyJudgedBatch("B-DIS", hs, categories);
        // 处置门禁回执勾稽：化制、销毁各 1
        postOk("/api/batches/B-DIS/receipts",
                new ReceiptRequest("r-1", "RC-1", "RENDERING", 1, "合成熟处理厂"),
                com.example.starter.web.dto.ReceiptResponse.class);
        postOk("/api/batches/B-DIS/receipts",
                new ReceiptRequest("r-2", "RC-2", "DESTRUCTION", 1, "合成销毁场"),
                com.example.starter.web.dto.ReceiptResponse.class);
        enableSplit("se-1", "B-DIS");

        // 化制/销毁头只不属于合格集合：拒绝
        postJson("/api/batches/B-DIS/shipments/vehicles",
                new VehicleCreateRequest("vc-bad", "京A-999", List.of("H03"), 0,
                        clock.instant().toString()))
                .andExpect(status().isUnprocessableEntity());
        postJson("/api/batches/B-DIS/shipments/vehicles",
                new VehicleCreateRequest("vc-bad2", "京A-998", List.of("H01", "H04"), 0,
                        clock.instant().toString()))
                .andExpect(status().isUnprocessableEntity());
        // 失败不占键：同一 requestId 改为合法参数可成功
        VehicleView v = createVehicle("vc-bad", "B-DIS", "京A-100", List.of("H01", "H02"), 0);
        assertEquals(2, v.headCount());

        VehicleView d = departVehicle("vd-1", "B-DIS", "京A-100", 1);
        assertTrue(d.batchReleased());
        ShipmentProgressView view = getShipment("B-DIS");
        assertEquals(2, view.qualifiedTotal());
        assertEquals(2, view.departedCount());
        assertEquals(0, view.unshippedCount(), "病害头只不得计入未出厂人数");
        assertTrue(view.unshippedHeads().isEmpty());
    }

    @Test
    @DisplayName("跨批头只拒绝建单")
    void crossBatchHeadRejected() throws Exception {
        readyJudgedBatch("B-A", List.of("H01"), List.of("QUALIFIED"));
        readyJudgedBatch("B-B", List.of("X01"), List.of("QUALIFIED"));
        enableSplit("se-a", "B-A");
        enableSplit("se-b", "B-B");

        // B-A 车单引用 B-B 的头只 X01：跨批拒绝
        postJson("/api/batches/B-A/shipments/vehicles",
                new VehicleCreateRequest("vc-x", "京A-1", List.of("H01", "X01"), 0,
                        clock.instant().toString()))
                .andExpect(status().isUnprocessableEntity());
        assertEquals(0, countRows("shipment_head"));
        // 合法头只仍可建单
        createVehicle("vc-ok", "B-A", "京A-1", List.of("H01"), 0);
    }

    @Test
    @DisplayName("全量预占：清单含一个被占用头只则全单不占，其他头只不被部分占用")
    void reservationAllOrNothing() throws Exception {
        readyAllQualified("B-ALL", 4);
        enableSplit("se-1", "B-ALL");
        createVehicle("vc-1", "B-ALL", "京A-1", List.of("H01"), 0);

        // H01 已被占用：H02、H03 也不得部分落库
        postJson("/api/batches/B-ALL/shipments/vehicles",
                new VehicleCreateRequest("vc-2", "京A-2", List.of("H01", "H02", "H03"), 0,
                        clock.instant().toString()))
                .andExpect(status().isConflict());
        // 只有第一车的 H01 被占用
        Integer reservedHeads = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shipment_head WHERE status = 'RESERVED'", Integer.class);
        assertEquals(1, reservedHeads);
        // 车单主表也不得留下第二车
        assertEquals(1, countRows("shipment_vehicle"));
        // H02 未被部分占用：可被另一车成功预占
        createVehicle("vc-3", "B-ALL", "京A-3", List.of("H02"), 0);
    }

    @Test
    @DisplayName("未发车清单可整体撤销释放，头只随后可被其他车预占；撤销终态不可重复")
    void revokeReleasesHeadsAndIsTerminal() throws Exception {
        readyAllQualified("B-REV", 3);
        enableSplit("se-1", "B-REV");
        VehicleView v1 = createVehicle("vc-1", "B-REV", "京A-1", List.of("H01", "H02"), 0);

        // 旧版本撤销 409
        postJson("/api/batches/B-REV/shipments/vehicles/京A-1/revoke",
                new VehicleRevokeRequest("vr-old", 2, clock.instant().toString()))
                .andExpect(status().isConflict());

        VehicleView revoked = revokeVehicle("vr-1", "B-REV", "京A-1", 1);
        assertEquals("REVOKED", revoked.status());
        assertEquals(2, revoked.version());
        assertEquals(0, countRows("shipment_head"), "撤销后预占行全部释放");

        // 重复撤销拒绝
        postJson("/api/batches/B-REV/shipments/vehicles/京A-1/revoke",
                new VehicleRevokeRequest("vr-2", 2, clock.instant().toString()))
                .andExpect(status().isConflict());
        // 释放的头只可被其他车预占
        VehicleView v2 = createVehicle("vc-2", "B-REV", "京A-2", List.of("H01", "H02", "H03"), 0);
        assertEquals(3, v2.headCount());
    }

    @Test
    @DisplayName("已发车车辆不可撤销/重复核销，失败保留原预占")
    void departedVehicleFrozen() throws Exception {
        readyAllQualified("B-FRZ", 2);
        enableSplit("se-1", "B-FRZ");
        createVehicle("vc-1", "B-FRZ", "京A-1", List.of("H01", "H02"), 0);
        departVehicle("vd-1", "B-FRZ", "京A-1", 1);

        postJson("/api/batches/B-FRZ/shipments/vehicles/京A-1/revoke",
                new VehicleRevokeRequest("vr-1", 2, clock.instant().toString()))
                .andExpect(status().isConflict());
        postJson("/api/batches/B-FRZ/shipments/vehicles/京A-1/depart",
                new VehicleDepartRequest("vd-2", 2, clock.instant().toString(), "核销员甲"))
                .andExpect(status().isConflict());
        // 已出厂证据行保留
        Integer departedRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shipment_head WHERE status = 'DEPARTED'", Integer.class);
        assertEquals(2, departedRows);
    }

    @Test
    @DisplayName("车单版本从1起：错误版本发车409，整批出厂入口在启用分车后拒绝")
    void versionConflictAndWholeBatchEntryRejected() throws Exception {
        readyAllQualified("B-VER", 2);
        enableSplit("se-1", "B-VER");
        // 新建车单 expectedVersion 非 0 拒绝
        postJson("/api/batches/B-VER/shipments/vehicles",
                new VehicleCreateRequest("vc-bad", "京A-1", List.of("H01"), 1,
                        clock.instant().toString()))
                .andExpect(status().isConflict());
        createVehicle("vc-1", "B-VER", "京A-1", List.of("H01", "H02"), 0);

        // 错误期望版本发车 409，预占保留
        postJson("/api/batches/B-VER/shipments/vehicles/京A-1/depart",
                new VehicleDepartRequest("vd-bad", 7, clock.instant().toString(), "核销员甲"))
                .andExpect(status().isConflict());
        assertEquals("RESERVED", getShipment("B-VER").vehicles().get(0).status());

        // 启用分车后原整批出厂入口拒绝，不能绕过车辆清单
        postJson("/api/batches/B-VER/release",
                new com.example.starter.web.dto.ReleaseRequest("rel-1", "核销员甲"))
                .andExpect(status().isConflict());
        assertEquals("JUDGED", getView("B-VER").status());

        // 正确版本发车并收口
        departVehicle("vd-ok", "B-VER", "京A-1", 1);
        assertEquals("RELEASED", getView("B-VER").status());
    }

    @Test
    @DisplayName("无合格头只或终态后不能启用分车；启用幂等重放")
    void enablePreconditionsAndIdempotency() throws Exception {
        // 无合格头只（全部化制）不能启用
        readyJudgedBatch("B-NQ", List.of("H01"), List.of("RENDERING"));
        postJson("/api/batches/B-NQ/shipments/enable",
                new SplitEnableRequest("se-bad", "分车员甲", clock.instant().toString()))
                .andExpect(status().isConflict());

        // 合格批启用成功，同键同参重放返回相同结果
        readyAllQualified("B-EQ", 2);
        SplitEnableRequest req = new SplitEnableRequest("se-1", "分车员甲",
                clock.instant().toString());
        String first = postJson("/api/batches/B-EQ/shipments/enable", req)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String replay = postJson("/api/batches/B-EQ/shipments/enable", req)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(first, replay);
        // 同键异参 409
        postJson("/api/batches/B-EQ/shipments/enable",
                new SplitEnableRequest("se-1", "分车员乙", clock.instant().toString()))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("车单创建同键同参重放原响应，异参409")
    void vehicleCreateIdempotentReplayAndConflict() throws Exception {
        readyAllQualified("B-IDEM", 3);
        enableSplit("se-1", "B-IDEM");
        VehicleCreateRequest req = new VehicleCreateRequest("vc-1", "京A-1",
                List.of("H01", "H02"), 0, clock.instant().toString());
        String first = postJson("/api/batches/B-IDEM/shipments/vehicles", req)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String replay = postJson("/api/batches/B-IDEM/shipments/vehicles", req)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(first, replay);
        assertEquals(1, countRows("shipment_vehicle"));
        assertEquals(2, countRows("shipment_head"));

        // 同键异参（头只集合不同）409，不新增占用
        postJson("/api/batches/B-IDEM/shipments/vehicles",
                new VehicleCreateRequest("vc-1", "京A-1", List.of("H03"), 0,
                        clock.instant().toString()))
                .andExpect(status().isConflict());
        assertEquals(2, countRows("shipment_head"));
    }

    @Test
    @DisplayName("处置回执门禁不满足时逐车核销拒绝且保留预占，补齐后可发车")
    void departureBlockedByReceiptGateKeepsReservation() throws Exception {
        readyJudgedBatch("B-GATE", List.of("H01", "H02"),
                List.of("QUALIFIED", "RENDERING"));
        enableSplit("se-1", "B-GATE");
        createVehicle("vc-1", "B-GATE", "京A-1", List.of("H01"), 0);

        // 化制 1 头无回执：处置门禁拒绝，车单仍 RESERVED、头只仍 RESERVED
        postJson("/api/batches/B-GATE/shipments/vehicles/京A-1/depart",
                new VehicleDepartRequest("vd-bad", 1, clock.instant().toString(), "核销员甲"))
                .andExpect(status().isConflict());
        assertEquals("RESERVED", getShipment("B-GATE").vehicles().get(0).status());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM shipment_head WHERE status = 'RESERVED'", Integer.class));

        // 补齐回执后发车成功（此时全部合格头只恰核销完，收口）
        postOk("/api/batches/B-GATE/receipts",
                new ReceiptRequest("r-1", "RC-1", "RENDERING", 1, "合成熟处理厂"),
                com.example.starter.web.dto.ReceiptResponse.class);
        VehicleView d = departVehicle("vd-ok", "B-GATE", "京A-1", 1);
        assertTrue(d.batchReleased());
    }

    @Test
    @DisplayName("每车51头拒绝，头只标识重复拒绝")
    void overCapacityAndDuplicateHeadsRejected() throws Exception {
        List<String> many = new ArrayList<>();
        for (int i = 1; i <= 51; i++) {
            many.add(String.format("S%02d", i));
        }
        List<String> categories = many.stream().map(h -> "QUALIFIED").toList();
        readyJudgedBatch("B-CAP", many, categories);
        enableSplit("se-1", "B-CAP");

        postJson("/api/batches/B-CAP/shipments/vehicles",
                new VehicleCreateRequest("vc-51", "京A-1", many, 0, clock.instant().toString()))
                .andExpect(status().isBadRequest());
        postJson("/api/batches/B-CAP/shipments/vehicles",
                new VehicleCreateRequest("vc-dup", "京A-2", List.of("S01", "S01"), 0,
                        clock.instant().toString()))
                .andExpect(status().isBadRequest());
        assertEquals(0, countRows("shipment_vehicle"));
    }

    @Test
    @DisplayName("分车收口后迟到阳性只留告警不改终态；末车发车同键重放原响应")
    void lateRiskAfterReleaseOnlyAlertsAndDepartReplays() throws Exception {
        readyAllQualified("B-LATE", 2);
        // 受控批 NORMAL：2 头抽 H01，登记阴性后清单完成、门禁解除
        bindRisk("rb-1", "B-LATE", "NORMAL", "RULE-1", List.of("H01", "H02"), 0);
        com.example.starter.web.dto.RiskResultResponse neg =
                riskResult("rr-1", "B-LATE", "H01", "NEGATIVE", 1);
        assertTrue(neg.samplingCompleted());

        enableSplit("se-1", "B-LATE");
        createVehicle("vc-1", "B-LATE", "京A-1", List.of("H01", "H02"), 0);
        VehicleDepartRequest departReq = new VehicleDepartRequest(
                "vd-1", 1, clock.instant().toString(), "核销员甲");
        String firstDepart = postJson("/api/batches/B-LATE/shipments/vehicles/京A-1/depart", departReq)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals("RELEASED", getView("B-LATE").status());

        // 收口后迟到阳性：受理但仅留迟到事件，终态与已发车辆证据不变
        com.example.starter.web.dto.RiskResultResponse late =
                riskResult("rr-late", "B-LATE", "H01", "POSITIVE", 99);
        assertTrue(late.late());
        BatchView batch = getView("B-LATE");
        assertEquals("RELEASED", batch.status());
        assertEquals(1, batch.lateEvents().size());
        assertEquals("LATE_RISK_RESULT", batch.lateEvents().get(0).eventType());
        ShipmentProgressView view = getShipment("B-LATE");
        assertEquals(2, view.departedCount());
        assertTrue(view.vehicles().stream().allMatch(v -> "DEPARTED".equals(v.status())));

        // 末车发车同键同参重放：原样返回首次响应（RELEASED/batchReleased=true），不重复处理
        String replay = postJson("/api/batches/B-LATE/shipments/vehicles/京A-1/depart", departReq)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(firstDepart, replay);
        assertEquals(1, view.vehicles().size());
    }

    @Test
    @DisplayName("首车发车后提高风险等级：第二车被门禁阻断保留预占，补齐全阴后同请求标识重试收口")
    void riskChangeBetweenDeparturesBlocksSecondVehicleUntilCleared() throws Exception {
        readyAllQualified("B-RISK", 10);
        // NORMAL 抽 H01，阴性后首份清单完成、门禁解除（版本到 2）
        bindRisk("rb-1", "B-RISK", "NORMAL", "RULE-1", heads(10), 0);
        riskResult("rr-1", "B-RISK", "H01", "NEGATIVE", 1);

        enableSplit("se-1", "B-RISK");
        createVehicle("vc-1", "B-RISK", "京A-1", heads(5), 0);
        List<String> secondFive = List.of("H06", "H07", "H08", "H09", "H10");
        createVehicle("vc-2", "B-RISK", "京A-2", secondFive, 0);

        // 首车 5 头出厂，批次未收口
        VehicleView d1 = departVehicle("vd-1", "B-RISK", "京A-1", 1);
        assertFalse(d1.batchReleased());

        // 首车后风险提高到加严：首份清单已完成，另开补充清单（序号2，抽 H01,H02,H03），门禁重新未解除
        changeRisk("rc-1", "B-RISK", "TIGHTENED", "RULE-2", heads(10), 2);

        // 第二车逐车核销被风险门禁阻断：409，车单与头只保留 RESERVED，已发首车证据不变
        postJson("/api/batches/B-RISK/shipments/vehicles/京A-2/depart",
                new VehicleDepartRequest("vd-block", 1, clock.instant().toString(), "核销员甲"))
                .andExpect(status().isConflict());
        ShipmentProgressView blocked = getShipment("B-RISK");
        assertEquals(5, blocked.departedCount());
        assertEquals(5, blocked.unshippedCount());
        assertEquals("JUDGED", getView("B-RISK").status());
        VehicleView v2Blocked = blocked.vehicles().stream()
                .filter(v -> v.vehicleNo().equals("京A-2")).findFirst().orElseThrow();
        assertEquals("RESERVED", v2Blocked.status());

        // 补齐补充清单全部阴性（序号2 的 H01,H02,H03）
        riskResult("rr-2", "B-RISK", "H01", "NEGATIVE", 3);
        riskResult("rr-3", "B-RISK", "H02", "NEGATIVE", 4);
        riskResult("rr-4", "B-RISK", "H03", "NEGATIVE", 5);

        // 阻断失败不占幂等键：同一 requestId 在门禁解除后重试成功，末车收口
        VehicleView d2 = departVehicle("vd-block", "B-RISK", "京A-2", 1);
        assertTrue(d2.batchReleased());
        ShipmentProgressView done = getShipment("B-RISK");
        assertEquals(10, done.departedCount());
        assertEquals(0, done.unshippedCount());
        assertTrue(done.allShipped());
        assertEquals("RELEASED", getView("B-RISK").status());
    }
}
