package com.example.starter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.example.starter.service.Fingerprints;
import com.example.starter.web.dto.ApiDtos.MaintenanceItemInput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 新写操作幂等边界：同键同参重放首次冻结结果；同键异参（版本/集合/精确时刻/含分隔符文本）409；
 * 业务失败不占键。另验证结构化指纹对含分隔符合法文本无歧义。
 */
class MaintenancePlanIdempotencyTest extends AbstractIntegrationTest {

    private static final LocalDate COMMISSIONED = LocalDate.of(2026, 1, 1);
    private static final Instant DISPATCHABLE = Instant.parse("2026-01-15T00:00:00Z");
    private static final Instant W_START = Instant.parse("2026-01-15T00:00:00Z");
    private static final Instant W_END = Instant.parse("2026-01-16T12:00:00Z");

    @Test
    @DisplayName("派发与清单回报：同键同参重放冻结结果，异参409，失败不占键")
    void dispatchAndItemIdempotency() throws Exception {
        long e1 = createElevator("PI-A", COMMISSIONED);
        long s1 = createStaff("C-PI-1", true);
        long s2 = createStaff("C-PI-2", true);
        long s3 = createStaff("C-PI-3", true);
        planScan("PI-scan", DISPATCHABLE);
        long obId = firstObligationId(e1);

        Resp first = planDispatch("PI-d", e1, obId, List.of(s1, s2), twoItems(), W_START, W_END,
                DISPATCHABLE.plusSeconds(60));
        assertEquals(200, first.status());
        long attemptId = first.id();
        Resp replay = planDispatch("PI-d", e1, obId, List.of(s1, s2), twoItems(), W_START, W_END,
                DISPATCHABLE.plusSeconds(60));
        assertEquals(200, replay.status());
        assertEquals(om.writeValueAsString(first.body()), om.writeValueAsString(replay.body()),
                "同键同参须返回首次冻结结果");
        Integer attemptCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_attempt WHERE obligation_id=?", Integer.class, obId);
        assertEquals(1, attemptCount, "重放不得多派尝试");

        // 同键异参：不同精确时刻
        Resp diffTime = planDispatch("PI-d", e1, obId, List.of(s1, s2), twoItems(), W_START, W_END,
                DISPATCHABLE.plusSeconds(120));
        assertEquals(409, diffTime.status());
        assertEquals("IDEMPOTENCY_CONFLICT", diffTime.text("error"));
        // 同键同参：人员集合仅顺序不同，归一化后返回首次结果
        Resp reordered = planDispatch("PI-d", e1, obId, List.of(s2, s1), twoItems(), W_START, W_END,
                DISPATCHABLE.plusSeconds(60));
        assertEquals(200, reordered.status(), "集合顺序归一化后同参应重放");
        // 同键异参：真正换集合
        Resp diffStaff = planDispatch("PI-d", e1, obId, List.of(s1, s3), twoItems(), W_START, W_END,
                DISPATCHABLE.plusSeconds(60));
        assertEquals(409, diffStaff.status(), "不同人员集合应 409");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM maintenance_attempt WHERE obligation_id=?",
                Integer.class, obId), "异参重放不得新增尝试");

        // 清单回报：先以错误版本制造业务失败（不占键），再以正确版本同键成功
        Resp stale = reportItem(attemptId, "PI-item", "I-1", true, 99, DISPATCHABLE.plusSeconds(120));
        assertEquals(409, stale.status());
        Resp ok = reportItem(attemptId, "PI-item", "I-1", true, 1, DISPATCHABLE.plusSeconds(120));
        assertEquals(200, ok.status(), "业务失败不占键，同键合法请求应成功");
        Resp replayItem = reportItem(attemptId, "PI-item", "I-1", true, 1, DISPATCHABLE.plusSeconds(120));
        assertEquals(200, replayItem.status());
        assertEquals(om.writeValueAsString(ok.body()), om.writeValueAsString(replayItem.body()));
        // 同键异参：不同 expectedVersion
        Resp diffVersion = reportItem(attemptId, "PI-item", "I-1", true, 2, DISPATCHABLE.plusSeconds(120));
        assertEquals(409, diffVersion.status());
    }

    @Test
    @DisplayName("含分隔符的合法清单文本：结构化指纹须区分不同字段划分与取值")
    void structuredFingerprintDistinguishesDelimiterText() {
        // 名称含 '='、'|'、空格，两个看似拼接后相同但字段划分不同的输入
        String a = Fingerprints.structured("k1=a|b", "k2=c");
        String b = Fingerprints.structured("k1=a", "k2=b|k2=c");
        assertNotEquals(a, b, "含分隔符文本不得因拼接歧义而碰撞");

        String c1 = Fingerprints.structured("name=含=分隔|符 文本", "code=X");
        String c2 = Fingerprints.structured("name=含=分隔", "code=|符 文本X");
        assertNotEquals(c1, c2);

        // 集合编码：单元素 "a,b" 与两元素 a、b 必须不同
        assertNotEquals(Fingerprints.list(List.of("a,b")), Fingerprints.list(List.of("a", "b")));
        // 同值不同顺序在调用方排序归一化后等价（这里直接验证稳定性）
        assertEquals(Fingerprints.list(List.of("1", "2")), Fingerprints.list(List.of("1", "2")));
    }

    @Test
    @DisplayName("双人完成确认幂等：同键同参重放首次结果，不同完成时刻409")
    void completeIdempotency() throws Exception {
        long e1 = createElevator("PI-C", COMMISSIONED);
        long s1 = createStaff("C-PIC-1", true);
        long s2 = createStaff("C-PIC-2", true);
        planScan("PI-c-scan", DISPATCHABLE);
        long obId = firstObligationId(e1);
        long attemptId = planDispatch("PI-c-d", e1, obId, List.of(s1, s2), twoItems(), W_START, W_END,
                DISPATCHABLE.plusSeconds(60)).id();
        assertEquals(200, reportItem(attemptId, "PI-c-i1", "I-1", true, 1, DISPATCHABLE.plusSeconds(120)).status());
        assertEquals(200, reportItem(attemptId, "PI-c-i2", "I-2", true, 2, DISPATCHABLE.plusSeconds(180)).status());

        Instant completedAt = DISPATCHABLE.plusSeconds(240);
        Resp done = planComplete(attemptId, "PI-c-ok", List.of(s1, s2), 3, completedAt);
        assertEquals(200, done.status());
        Resp replay = planComplete(attemptId, "PI-c-ok", List.of(s1, s2), 3, completedAt);
        assertEquals(200, replay.status());
        assertEquals(om.writeValueAsString(done.body()), om.writeValueAsString(replay.body()));
        Resp diffAt = planComplete(attemptId, "PI-c-ok", List.of(s1, s2), 3, completedAt.plusSeconds(60));
        assertEquals(409, diffAt.status(), "同键不同精确时刻应 409");
    }

    @Test
    @DisplayName("非法参数400：空人员/空清单/空 requestId 由校验层拒绝")
    void invalidRequestShape400() throws Exception {
        long e1 = createElevator("PI-V", COMMISSIONED);
        Resp emptyStaff = postJson("/api/maintenance-plans/attempts",
                new com.example.starter.web.dto.ApiDtos.PlanAttemptRequest(
                        "PI-v-d", e1, null, List.of(), List.of(), W_START, W_END, DISPATCHABLE));
        assertEquals(400, emptyStaff.status());
        assertEquals("VALIDATION_FAILED", emptyStaff.text("error"));
        Resp blankKey = postJson("/api/maintenance-plans/attempts",
                new com.example.starter.web.dto.ApiDtos.PlanAttemptRequest(
                        " ", e1, null, List.of(1L, 2L), twoItems(), W_START, W_END, DISPATCHABLE));
        assertEquals(400, blankKey.status());
    }
}
