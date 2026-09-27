package com.example.starter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 履约续派主流程与失败分支：
 * 到期前 24 小时窗口、双持证半开作业窗、开始复核、全清单双人确认、
 * 救援挂起与结案后续派、旧尝试迟到、手工入口结清、逾期续派不自动复梯、幂等与版本。
 */
class ObligationFlowTest extends AbstractIntegrationTest {

    private static final LocalDate COMMISSIONED = LocalDate.of(2026, 1, 1);
    private static final Instant DUE = Instant.parse("2026-01-16T00:00:00Z");
    private static final Instant EARLIEST_DISPATCH = DUE.minusSeconds(24 * 3600);

    @Test
    @DisplayName("主流程：恰在到期前24小时起点扫描生成义务，双人作业窗开始→全清单→双人确认完成，登记有效记录并释放资源")
    void fullFulfillmentFlow() throws Exception {
        long e1 = createElevator("OB-MAIN", COMMISSIONED);
        long s1 = createStaff("OB-M-1", true);
        long s2 = createStaff("OB-M-2", true);

        // 窗口未开不生成义务
        Resp tooEarly = scanObligations(e1, EARLIEST_DISPATCH.minusSeconds(1));
        assertEquals(200, tooEarly.status());
        assertEquals(0, tooEarly.body().size(), "到期前24小时之前不应生成义务");

        // 恰在起点允许
        Resp scanned = scanObligations(e1, EARLIEST_DISPATCH);
        assertEquals(1, scanned.body().size(), "恰在到期前24小时起点应生成义务");
        long obligationId = scanned.body().get(0).get("id").asLong();
        assertEquals("OPEN", scanned.body().get(0).get("status").asText());
        assertEquals(1, scanned.body().get(0).get("version").asInt(), "版本从 1 开始");
        assertEquals("2026-01-16T00:00:00Z", scanned.body().get(0).get("dueAt").asText());
        assertEquals(4, scanned.body().get(0).get("checklist").size());

        // 重复扫描不多派
        Resp again = scanObligations(e1, EARLIEST_DISPATCH.plusSeconds(60));
        assertEquals(1, again.body().size(), "重复扫描不得重复生成义务");

        Instant ws = EARLIEST_DISPATCH.plusSeconds(3600);
        Instant we = ws.plusSeconds(7200);
        Resp allocated = allocate(obligationId, "OB-MAIN-A1", 1, List.of(s2, s1), ws, we);
        assertEquals(200, allocated.status(), "人员顺序不影响，规范化后按序处理");
        assertEquals("IN_PROGRESS", allocated.text("status"));
        assertEquals(2, allocated.body().get("version").asInt(), "成功变更加 1");
        JsonNode attempt1 = latestAttempt(obligationId);
        long attemptId = attempt1.get("id").asLong();
        assertEquals(1, attempt1.get("seq").asInt());
        assertEquals("ALLOCATED", attempt1.get("status").asText());
        assertEquals(1, attempt1.get("version").asInt());

        // 版本不符 409
        Resp staleStart = startAttempt(attemptId, "OB-MAIN-stale", 99, ws);
        assertEquals(409, staleStart.status());
        assertEquals("VERSION_CONFLICT", staleStart.text("error"));

        Resp started = startAttempt(attemptId, "OB-MAIN-START", 1, ws);
        assertEquals(200, started.status());
        assertEquals(2, latestAttempt(obligationId).get("version").asInt());

        Resp items = reportItems(attemptId, "OB-MAIN-ITEMS-A", 2,
                List.of("CHK_CAR_DOOR", "CHK_EMERGENCY_ALARM"), ws.plusSeconds(600));
        assertEquals(200, items.status());
        assertEquals(3, latestAttempt(obligationId).get("version").asInt());

        // 清单不全不允许完成
        Resp incomplete = completeAttempt(attemptId, "OB-MAIN-INC", 3, List.of(s1, s2),
                List.of("CHK_TRACTION_BRAKE"), ws.plusSeconds(1200));
        assertEquals(422, incomplete.status());
        assertEquals("CHECKLIST_INCOMPLETE", incomplete.text("error"));
        assertEquals("IN_PROGRESS", obligation(obligationId).text("status"), "失败不终结义务");

        // 确认人不是占用的两人
        long s3 = createStaff("OB-M-3", true);
        Resp wrongConfirmers = completeAttempt(attemptId, "OB-MAIN-WC", 3, List.of(s1, s3),
                ALL_CHECKLIST, ws.plusSeconds(1200));
        assertEquals(422, wrongConfirmers.status());
        assertEquals("CONFIRMERS_MISMATCH", wrongConfirmers.text("error"));

        // 全部清单 + 双人共同确认
        Instant completeAt = ws.plusSeconds(1800);
        Resp completed = completeAttempt(attemptId, "OB-MAIN-DONE", 3, List.of(s1, s2),
                List.of("CHK_TRACTION_BRAKE", "CHK_LANDING_LOCK"), completeAt);
        assertEquals(200, completed.status());
        assertEquals("FULFILLED", completed.text("status"));
        assertEquals(3, completed.body().get("version").asInt(),
                "义务版本：建义务1→派发2→终结3；开始/清单回报是尝试级变更，记在尝试版本上");
        JsonNode finalAttempt = latestAttempt(obligationId);
        assertEquals("COMPLETED", finalAttempt.get("status").asText());
        assertEquals(4, finalAttempt.get("doneItems").size(), "完成回报与此前回报合并为全部清单");
        assertNotNull(completed.body().get("fulfilledRecordId").asLong() > 0
                ? completed.body().get("fulfilledRecordId") : null);

        // 登记一条有效维保记录
        Resp ledger = getJson("/api/elevators/" + e1 + "/maintenance");
        assertEquals(1, ledger.body().size());
        assertEquals("COMPLIANT", ledger.body().get(0).get("result").asText());
        assertEquals("2026-01-15", ledger.body().get(0).get("performedDay").asText());
        assertEquals("2026-01-16", ledger.body().get(0).get("dueDay").asText());
        assertEquals("2026-01-15", elevator(e1).text("lastMaintenanceDay"));
        assertEquals("RUNNING", elevator(e1).text("status"));

        // 资源已释放
        Integer occupied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_attempt_staff WHERE attempt_id=?", Integer.class, attemptId);
        assertEquals(0, occupied, "完成后应释放双人作业窗");

        // 义务终结后不得再派（携带当前版本 3，越过版本校验后由业务拒绝）
        Resp reAllocate = allocate(obligationId, "OB-MAIN-AGAIN", 3, List.of(s1, s2), ws, we);
        assertEquals(422, reAllocate.status());
        assertEquals("OBLIGATION_FULFILLED", reAllocate.text("error"));

        // 轨迹可查
        Resp events = obligationEvents(obligationId);
        assertTrue(events.body().size() >= 6);
        boolean sawFulfilled = false;
        for (JsonNode ev : events.body()) {
            if ("OBLIGATION_FULFILLED".equals(ev.get("event").asText())) {
                sawFulfilled = true;
            }
        }
        assertTrue(sawFulfilled, "轨迹应含义务终结事件");
    }

    @Test
    @DisplayName("失败分支：未持证/单人/窗倒置/未知检查项/不存在对象，且任一不足整批不占资源")
    void allocationFailuresDoNotOccupyResources() throws Exception {
        long e1 = createElevator("OB-FAIL", COMMISSIONED);
        long s1 = createStaff("OB-F-1", true);
        long s2 = createStaff("OB-F-2", true);
        long s3 = createStaff("OB-F-3", false);
        scanObligations(e1, EARLIEST_DISPATCH);
        long obligationId = getJson("/api/obligations?elevatorId=" + e1).body().get(0).get("id").asLong();
        Instant ws = EARLIEST_DISPATCH;
        Instant we = ws.plusSeconds(3600);

        Resp uncertified = allocate(obligationId, "OB-FAIL-U", 1, List.of(s1, s3), ws, we);
        assertEquals(422, uncertified.status());
        assertEquals("UNQUALIFIED_STAFF", uncertified.text("error"));
        assertEquals(0, countAttempts(obligationId), "不足时整批不占资源：无尝试行");
        assertEquals(0, countAttemptStaff(obligationId), "不足时整批不占资源：无作业窗占用");
        assertEquals("OPEN", obligation(obligationId).text("status"));
        assertEquals(1, obligation(obligationId).body().get("version").asInt(), "失败不得变更版本");

        Resp one = allocate(obligationId, "OB-FAIL-1", 1, List.of(s1), ws, we);
        assertEquals(422, one.status());
        assertEquals("TWO_STAFF_REQUIRED", one.text("error"));

        Resp badWindow = allocate(obligationId, "OB-FAIL-W", 1, List.of(s1, s2), we, ws);
        assertEquals(400, badWindow.status());
        assertEquals("INVALID_WINDOW", badWindow.text("error"));

        Resp missingStaff = allocate(obligationId, "OB-FAIL-M", 1, List.of(s1, 999999L), ws, we);
        assertEquals(404, missingStaff.status());

        Resp missingObligation = allocate(999999L, "OB-FAIL-O", 1, List.of(s1, s2), ws, we);
        assertEquals(404, missingObligation.status());
        assertEquals(404, obligation(999999L).status());

        // 合法派发后回报未知检查项 → 400
        Resp ok = allocate(obligationId, "OB-FAIL-OK", 1, List.of(s1, s2), ws, we);
        assertEquals(200, ok.status());
        long attemptId = latestAttempt(obligationId).get("id").asLong();
        assertEquals(200, startAttempt(attemptId, "OB-FAIL-S", 1, ws).status());
        Resp unknown = reportItems(attemptId, "OB-FAIL-I", 2, List.of("CHK_NOT_EXIST"), ws);
        assertEquals(400, unknown.status());
        assertEquals("UNKNOWN_CHECKLIST_ITEM", unknown.text("error"));
    }

    @Test
    @DisplayName("人员同窗半开区间重叠整批拒绝；恰在终点接入不重叠")
    void staffWindowOverlapRejected() throws Exception {
        long e1 = createElevator("OB-W1", COMMISSIONED);
        long e2 = createElevator("OB-W2", COMMISSIONED);
        long s1 = createStaff("OB-W-1", true);
        long s2 = createStaff("OB-W-2", true);
        long s3 = createStaff("OB-W-3", true);
        scanObligations(e1, EARLIEST_DISPATCH);
        scanObligations(e2, EARLIEST_DISPATCH);
        long ob1 = getJson("/api/obligations?elevatorId=" + e1).body().get(0).get("id").asLong();
        long ob2 = getJson("/api/obligations?elevatorId=" + e2).body().get(0).get("id").asLong();

        Instant ws = EARLIEST_DISPATCH;
        Instant we = ws.plusSeconds(3600);
        assertEquals(200, allocate(ob1, "OB-W-A", 1, List.of(s1, s2), ws, we).status());

        // s1 在重叠窗 [we-1, we+1) 被另一义务占用 → 整批拒绝
        Resp overlap = allocate(ob2, "OB-W-B", 1, List.of(s1, s3), we.minusSeconds(1), we.plusSeconds(1));
        assertEquals(422, overlap.status());
        assertEquals("STAFF_WINDOW_CONFLICT", overlap.text("error"));
        assertEquals(0, countAttempts(ob2), "整批不占资源");

        // 半开：恰在 we 起点接入不重叠
        Resp touching = allocate(ob2, "OB-W-C", 1, List.of(s1, s3), we, we.plusSeconds(600));
        assertEquals(200, touching.status());
    }

    @Test
    @DisplayName("开始时重查证书：派发后证书失效，开始被拒绝且不占用进行中状态")
    void startRechecksCertificates() throws Exception {
        long e1 = createElevator("OB-CERT", COMMISSIONED);
        long s1 = createStaff("OB-C-1", true);
        long s2 = createStaff("OB-C-2", true);
        scanObligations(e1, EARLIEST_DISPATCH);
        long ob = getJson("/api/obligations?elevatorId=" + e1).body().get(0).get("id").asLong();
        Instant ws = EARLIEST_DISPATCH;
        assertEquals(200, allocate(ob, "OB-CERT-A", 1, List.of(s1, s2), ws, ws.plusSeconds(3600)).status());
        long attemptId = latestAttempt(ob).get("id").asLong();

        jdbc.update("UPDATE staff SET certified=FALSE WHERE id=?", s2);
        Resp started = startAttempt(attemptId, "OB-CERT-S", 1, ws);
        assertEquals(422, started.status());
        assertEquals("UNQUALIFIED_STAFF", started.text("error"));
        assertEquals("ALLOCATED", latestAttempt(ob).get("status").asText(), "开始失败不得推进尝试状态");
    }

    @Test
    @DisplayName("救援原子挂起尝试并释放人员、保留清单；旧尝试迟到不算完成；结案后续派新尝试全清单完成")
    void rescueSuspendsAndContinuationFulfills() throws Exception {
        long e1 = createElevator("OB-RC", COMMISSIONED);
        long s1 = createStaff("OB-R-1", true);
        long s2 = createStaff("OB-R-2", true);
        scanObligations(e1, EARLIEST_DISPATCH);
        long ob = getJson("/api/obligations?elevatorId=" + e1).body().get(0).get("id").asLong();
        Instant ws = EARLIEST_DISPATCH;
        Instant rescueAt = ws.plusSeconds(1800);
        assertEquals(200, allocate(ob, "OB-RC-A1", 1, List.of(s1, s2), ws, ws.plusSeconds(7200)).status());
        long attempt1 = latestAttempt(ob).get("id").asLong();
        assertEquals(200, startAttempt(attempt1, "OB-RC-S1", 1, ws).status());
        assertEquals(200, reportItems(attempt1, "OB-RC-I1", 2,
                List.of("CHK_CAR_DOOR", "CHK_EMERGENCY_ALARM"), ws.plusSeconds(600)).status());

        // 接报救援：原子挂起
        Resp rep = report("OB-RC-REPORT", e1, rescueAt);
        assertEquals(200, rep.status());
        long rescueId = rep.id();
        JsonNode suspended = latestAttempt(ob);
        assertEquals("SUSPENDED", suspended.get("status").asText());
        assertEquals(rescueId, suspended.get("suspendRescueId").asLong());
        assertEquals("OPEN", obligation(ob).text("status"), "义务仍存在，回到可续派状态");
        assertNull(obligation(ob).body().get("activeAttemptId").isNull() ? null
                : obligation(ob).body().get("activeAttemptId"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_attempt_staff WHERE attempt_id=?", Integer.class, attempt1),
                "挂起应释放人员");
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_attempt_item WHERE attempt_id=?", Integer.class, attempt1),
                "已做清单保留");
        assertTrue(eventTypes(ob).contains("ATTEMPT_SUSPENDED"));

        // 旧尝试回报：只记迟到，不算完成
        Resp late = completeAttempt(attempt1, "OB-RC-LATE", 3, List.of(s1, s2),
                ALL_CHECKLIST, rescueAt.plusSeconds(300));
        assertEquals(200, late.status());
        assertEquals("LATE", latestAttempt(ob).get("status").asText());
        assertEquals("OPEN", obligation(ob).text("status"), "迟到回报不得终结义务");
        Resp ledgerAfterLate = getJson("/api/elevators/" + e1 + "/maintenance");
        assertEquals(1, ledgerAfterLate.body().size());
        assertEquals("LATE_EVENT", ledgerAfterLate.body().get(0).get("result").asText());
        assertNull(elevator(e1).text("lastMaintenanceDay"), "迟到记录不是有效维保");

        // 救援走完流程结案（复检复梯）
        assertEquals(200, dispatch("OB-RC-D", rescueId, List.of(s1, s2)).status());
        assertEquals(200, arrive(rescueId, rescueAt.plusSeconds(600)).status());
        assertEquals(200, rescue(rescueId, List.of(s1, s2)).status());
        assertEquals(200, recheck(rescueId, true).status());
        assertEquals(200, close("OB-RC-CLOSE", rescueId).status());

        // 结案后续派同一义务的新尝试
        Instant ws2 = rescueAt.plusSeconds(24 * 3600);
        Resp again = allocate(ob, "OB-RC-A2", 3, List.of(s1, s2), ws2, ws2.plusSeconds(7200));
        assertEquals(200, again.status(), "结案后应可续派");
        JsonNode attempt2Node = latestAttempt(ob);
        assertEquals(2, attempt2Node.get("seq").asInt());
        assertEquals(0, attempt2Node.get("doneItems").size(), "新尝试不继承旧清单，须重新完成全部");
        long attempt2 = attempt2Node.get("id").asLong();
        assertEquals(200, startAttempt(attempt2, "OB-RC-S2", 1, ws2).status());

        // 只补旧尝试做过的两项仍不足：新尝试须完成全部清单
        Resp partial = completeAttempt(attempt2, "OB-RC-P2", 2, List.of(s1, s2),
                List.of("CHK_CAR_DOOR", "CHK_EMERGENCY_ALARM"), ws2.plusSeconds(600));
        assertEquals(422, partial.status());
        assertEquals("CHECKLIST_INCOMPLETE", partial.text("error"));

        Resp done = completeAttempt(attempt2, "OB-RC-DONE", 2, List.of(s1, s2),
                ALL_CHECKLIST, ws2.plusSeconds(1200));
        assertEquals(200, done.status());
        assertEquals("FULFILLED", done.text("status"));

        Resp ledger = getJson("/api/elevators/" + e1 + "/maintenance");
        long valid = ledger.body().findValuesAsText("result").stream().filter("COMPLIANT"::equals).count();
        long lateCount = ledger.body().findValuesAsText("result").stream().filter("LATE_EVENT"::equals).count();
        assertEquals(1, valid, "新尝试完成仅登记一条有效维保记录");
        assertEquals(1, lateCount, "旧尝试回报仅一条迟到事件");
    }

    @Test
    @DisplayName("逾期续派完成：登记OVERDUE、停梯并重点监察，不自动复梯，须原复检恢复")
    void overdueContinuationDoesNotAutoResume() throws Exception {
        long e1 = createElevator("OB-OD", COMMISSIONED);
        long s1 = createStaff("OB-O-1", true);
        long s2 = createStaff("OB-O-2", true);
        scanObligations(e1, EARLIEST_DISPATCH);
        long ob = getJson("/api/obligations?elevatorId=" + e1).body().get(0).get("id").asLong();
        Instant rescueAt = EARLIEST_DISPATCH.plusSeconds(1800);
        assertEquals(200, allocate(ob, "OB-OD-A1", 1, List.of(s1, s2),
                EARLIEST_DISPATCH, EARLIEST_DISPATCH.plusSeconds(3600)).status());
        long a1 = latestAttempt(ob).get("id").asLong();
        assertEquals(200, startAttempt(a1, "OB-OD-S1", 1, EARLIEST_DISPATCH).status());
        long rescueId = report("OB-OD-R", e1, rescueAt).id();
        assertEquals("SUSPENDED", latestAttempt(ob).get("status").asText());
        assertEquals(200, dispatch("OB-OD-D", rescueId, List.of(s1, s2)).status());
        assertEquals(200, arrive(rescueId, rescueAt.plusSeconds(600)).status());
        assertEquals(200, rescue(rescueId, List.of(s1, s2)).status());
        assertEquals(200, recheck(rescueId, true).status());
        assertEquals(200, close("OB-OD-C", rescueId).status());

        // 续派完成时刻已晚于到期日 2026-01-16
        Instant ws2 = Instant.parse("2026-01-17T01:00:00Z");
        assertEquals(200, allocate(ob, "OB-OD-A2", 3, List.of(s1, s2), ws2, ws2.plusSeconds(3600)).status());
        long a2 = latestAttempt(ob).get("id").asLong();
        assertEquals(200, startAttempt(a2, "OB-OD-S2", 1, ws2).status());
        Resp done = completeAttempt(a2, "OB-OD-DONE", 2, List.of(s1, s2), ALL_CHECKLIST, ws2.plusSeconds(600));
        assertEquals(200, done.status());
        assertEquals("FULFILLED", done.text("status"));
        assertEquals("STOPPED", elevator(e1).text("status"), "逾期续派不自动复梯");
        assertTrue(elevator(e1).bool("keySupervision"));
        assertTrue(chainHas(e1, "STOP", "MAINTENANCE_OVERDUE"));
        // 此前救援复检产生过复梯链；逾期完成后链的最后动作必须再次是逾期停梯，且未再复梯
        JsonNode chainArr = chain(e1).body();
        JsonNode last = chainArr.get(chainArr.size() - 1);
        assertEquals("STOP", last.get("action").asText());
        assertEquals("MAINTENANCE_OVERDUE", last.get("reason").asText(), "逾期完成后不得自动复梯");

        Resp ledger = getJson("/api/elevators/" + e1 + "/maintenance");
        assertEquals("OVERDUE", ledger.body().get(ledger.body().size() - 1).get("result").asText());

        Resp resumed = elevatorRecheck(e1, true);
        assertEquals(200, resumed.status());
        assertEquals("RUNNING", resumed.text("status"), "原复检恢复流程复梯");
    }

    @Test
    @DisplayName("旧手工维保入口将对应未完成义务一次性结清，进行中尝试释放并置迟到")
    void manualRecordSettlesOpenObligation() throws Exception {
        long e1 = createElevator("OB-MAN", COMMISSIONED);
        long s1 = createStaff("OB-N-1", true);
        long s2 = createStaff("OB-N-2", true);
        scanObligations(e1, EARLIEST_DISPATCH);
        long ob = getJson("/api/obligations?elevatorId=" + e1).body().get(0).get("id").asLong();
        Instant ws = EARLIEST_DISPATCH;
        assertEquals(200, allocate(ob, "OB-MAN-A", 1, List.of(s1, s2), ws, ws.plusSeconds(3600)).status());
        long a1 = latestAttempt(ob).get("id").asLong();
        assertEquals(200, startAttempt(a1, "OB-MAN-S", 1, ws).status());

        Resp manual = maintenance("OB-MAN-M1", e1, LocalDate.of(2026, 1, 15), null);
        assertEquals(200, manual.status());
        assertEquals("COMPLIANT", manual.text("result"));
        assertEquals("FULFILLED", obligation(ob).text("status"));
        assertEquals("LATE", latestAttempt(ob).get("status").asText(), "进行中尝试不再作为履约完成");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_attempt_staff WHERE attempt_id=?", Integer.class, a1),
                "结清时释放人员");
        assertTrue(eventTypes(ob).contains("OBLIGATION_SETTLED"));
    }

    @Test
    @DisplayName("写操作幂等：同键同参重放首次冻结结果，异参409，失败不占键")
    void idempotencyAndVersioning() throws Exception {
        long e1 = createElevator("OB-ID", COMMISSIONED);
        long s1 = createStaff("OB-I-1", true);
        long s2 = createStaff("OB-I-2", true);
        long s3 = createStaff("OB-I-3", false);
        scanObligations(e1, EARLIEST_DISPATCH);
        long ob = getJson("/api/obligations?elevatorId=" + e1).body().get(0).get("id").asLong();
        Instant ws = EARLIEST_DISPATCH;
        Instant we = ws.plusSeconds(3600);

        // 失败不占键：同一 requestId 先以非法组合失败，再以合法参数成功
        Resp failed = allocate(ob, "OB-ID-K1", 1, List.of(s1, s3), ws, we);
        assertEquals(422, failed.status());
        Integer stored = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id='OB-ID-K1'", Integer.class);
        assertEquals(0, stored, "失败不得占用幂等键");
        Resp first = allocate(ob, "OB-ID-K1", 1, List.of(s1, s2), ws, we);
        assertEquals(200, first.status());
        Resp replay = allocate(ob, "OB-ID-K1", 1, List.of(s2, s1), we, we);
        // 注意：换了不同窗参数必须 409；同参（顺序无关规范化）才重放
        assertEquals(409, replay.status());
        Resp same = allocate(ob, "OB-ID-K1", 1, List.of(s2, s1), ws, we);
        assertEquals(200, same.status(), "同键同参（人员顺序不同但集合相同）重放首次结果");
        assertEquals(om.writeValueAsString(first.body()), om.writeValueAsString(same.body()));

        long a1 = latestAttempt(ob).get("id").asLong();
        Resp startFirst = startAttempt(a1, "OB-ID-K2", 1, ws);
        assertEquals(200, startFirst.status());
        Resp startReplay = startAttempt(a1, "OB-ID-K2", 1, ws);
        assertEquals(200, startReplay.status());
        assertEquals(om.writeValueAsString(startFirst.body()), om.writeValueAsString(startReplay.body()));
        Resp startConflict = startAttempt(a1, "OB-ID-K2", 1, ws.plusSeconds(1));
        assertEquals(409, startConflict.status());
        assertEquals("IDEMPOTENCY_CONFLICT", startConflict.text("error"));

        // 非法参数体 → 400（缺 requestId）
        Resp bad = postJson("/api/obligations/" + ob + "/attempts",
                new java.util.HashMap<String, Object>() {{
                    put("expectedVersion", 2);
                    put("staffIds", List.of(s1, s2));
                    put("windowStart", ws.toString());
                    put("windowEnd", we.toString());
                }});
        assertEquals(400, bad.status());
    }

    @Test
    @DisplayName("结清后扫描生成下一周期义务：绑定上次有效维保记录，同周期仍唯一")
    void nextPeriodObligationBindsLastValidRecord() throws Exception {
        long e1 = createElevator("OB-NEXT", COMMISSIONED);
        long s1 = createStaff("OB-X-1", true);
        long s2 = createStaff("OB-X-2", true);
        scanObligations(e1, EARLIEST_DISPATCH);
        long ob1 = getJson("/api/obligations?elevatorId=" + e1).body().get(0).get("id").asLong();
        Instant ws = EARLIEST_DISPATCH.plusSeconds(60);
        assertEquals(200, allocate(ob1, "OB-NEXT-A", 1, List.of(s1, s2), ws, ws.plusSeconds(3600)).status());
        long a1 = latestAttempt(ob1).get("id").asLong();
        assertEquals(200, startAttempt(a1, "OB-NEXT-S", 1, ws).status());
        Resp done = completeAttempt(a1, "OB-NEXT-D", 2, List.of(s1, s2), ALL_CHECKLIST, ws.plusSeconds(900));
        assertEquals(200, done.status());
        long recordId = done.body().get("fulfilledRecordId").asLong();

        // 第 2 周期：基准日 2026-01-15，到期时刻 2026-01-30T00:00Z，窗口起点 2026-01-29T00:00Z
        Resp next = scanObligations(e1, Instant.parse("2026-01-29T00:00:00Z"));
        assertEquals(2, next.body().size());
        JsonNode ob2Node = next.body().get(1);
        assertEquals(2, ob2Node.get("periodIndex").asInt());
        assertEquals("2026-01-30T00:00:00Z", ob2Node.get("dueAt").asText());
        assertEquals(recordId, ob2Node.get("lastValidRecordId").asLong(), "新义务须绑定上次有效维保记录");
        assertEquals("OPEN", ob2Node.get("status").asText());
        // 重复扫描不多派
        assertEquals(2, scanObligations(e1, Instant.parse("2026-01-29T01:00:00Z")).body().size());
    }

    private int countAttempts(long obligationId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM maintenance_attempt WHERE obligation_id=?",
                Integer.class, obligationId);
    }

    private int countAttemptStaff(long obligationId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_attempt_staff w JOIN maintenance_attempt a ON a.id=w.attempt_id "
                        + "WHERE a.obligation_id=?", Integer.class, obligationId);
    }

    private java.util.List<String> eventTypes(long obligationId) throws Exception {
        java.util.List<String> types = new java.util.ArrayList<>();
        for (JsonNode n : obligationEvents(obligationId).body()) {
            types.add(n.get("event").asText());
        }
        return types;
    }
}
