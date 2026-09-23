package com.example.starter.purge;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.domain.EvidenceStatus;
import com.example.starter.domain.PurgeBatch;
import com.example.starter.domain.PurgeManifestEntry;
import com.example.starter.service.ApiException;
import com.example.starter.service.HoldService;
import com.example.starter.service.PurgeService;
import com.example.starter.web.dto.CreateHoldRequest;
import com.example.starter.web.dto.MarkPurgeRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两阶段清除主流程与失败分支：到期边界、活动保全阻止、标记后救回、
 * 最终确认原子回滚、清单冻结不可变、标记请求指纹。
 */
class PurgeServiceTest extends AbstractIntegrationTest {

    @Autowired
    private PurgeService purgeService;

    @Autowired
    private HoldService holdService;

    private MarkPurgeRequest mark(String requestId, Instant cutoff, Long expectedVersion) {
        return new MarkPurgeRequest(requestId, cutoff, expectedVersion);
    }

    @Test
    void expiryBoundaryAtCutoffIsInclusiveAndNonExpiredExcluded() {
        // expires_at 恰好等于截止时间：边界上视为已到期。
        register("E-EXACT", "c1", T0.plusSeconds(600));
        // expires_at 晚于截止时间 1 秒：不清除。
        register("E-LATER", "c2", T0.plusSeconds(601));

        PurgeBatch batch = purgeService.mark(mark("M1", T0.plusSeconds(600), null));

        assertEquals(List.of("E-EXACT"), batch.items().stream().map(i -> i.evidenceId()).toList());
        assertEquals(EvidenceStatus.MARKED, evidenceService.get("E-EXACT").status());
        assertEquals(EvidenceStatus.RETAINED, evidenceService.get("E-LATER").status());
    }

    @Test
    void activeHoldBlocksMarking() {
        register("E1", "c1", T0.plusSeconds(100));
        var hold = holdService.create(new CreateHoldRequest("CASE-1", Set.of("E1"), "H1", 0L));

        PurgeBatch batch = purgeService.mark(mark("M1", T0.plusSeconds(600), null));

        assertTrue(batch.items().isEmpty(), "活动保全覆盖的证据不得进入清除批次");
        assertEquals(EvidenceStatus.RETAINED, evidenceService.get("E1").status());

        // 解除保全后再标记，同一证据可被清除。
        holdService.release(new com.example.starter.web.dto.ReleaseHoldRequest(
                hold.holdId(), "R1", hold.version()));
        PurgeBatch batch2 = purgeService.mark(mark("M2", T0.plusSeconds(600), null));
        assertEquals(List.of("E1"), batch2.items().stream().map(i -> i.evidenceId()).toList());
    }

    @Test
    void markWithWrongExpectedVersionReturns409() {
        register("E1", "c1", T0.plusSeconds(100));
        ApiException ex = assertThrows(ApiException.class,
                () -> purgeService.mark(mark("M1", T0.plusSeconds(600), 9L)));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM purge_batch", Integer.class));
        assertEquals(EvidenceStatus.RETAINED, evidenceService.get("E1").status());
    }

    @Test
    void markedEvidenceIsRescuedWhenHoldTakesEffectBeforeConfirm() {
        register("E1", "c1", T0.plusSeconds(100));
        PurgeBatch batch = purgeService.mark(mark("M1", T0.plusSeconds(600), null));
        assertEquals(EvidenceStatus.MARKED, evidenceService.get("E1").status());

        // 保全在标记后、最终确认前生效。
        var hold = holdService.create(new CreateHoldRequest("CASE-9", Set.of("E1"), "H1", 0L));
        assertEquals(EvidenceStatus.RETAINED, evidenceService.get("E1").status(), "救回复原为可保留");

        PurgeService.ConfirmResult result = purgeService.confirm(batch.batchId());

        assertEquals("PURGED", result.batch().status().name());
        assertTrue(result.manifest().isEmpty(), "被救回证据必须从最终清单排除");
        assertTrue(result.batch().items().get(0).rescued(), "候选标记为 rescued");
        assertEquals(EvidenceStatus.RETAINED, evidenceService.get("E1").status());
        assertTrue(hold.holdId() > 0);
        assertEquals("ACTIVE", holdService.get(hold.holdId()).status().name());
        // 保管链包含 MARKED、RESCUED、HELD，且不含 PURGED。
        List<String> types = jdbc.queryForList(
                "SELECT event_type FROM custody_event WHERE evidence_id='E1' ORDER BY id", String.class);
        assertTrue(types.contains("MARKED"));
        assertTrue(types.contains("RESCUED"));
        assertFalse(types.contains("PURGED"));
    }

    @Test
    void heldCandidateRescuedWhileOtherCandidatesPurgeInOneConfirm() {
        register("E1", "c1", T0.plusSeconds(100));
        register("E2", "c2", T0.plusSeconds(100));
        PurgeBatch batch = purgeService.mark(mark("M1", T0.plusSeconds(600), null));

        // 仅对 E1 在确认前追加保全；E2 仍应清除。
        holdService.create(new CreateHoldRequest("CASE-9", Set.of("E1"), "H1", 0L));

        PurgeService.ConfirmResult result = purgeService.confirm(batch.batchId());

        assertEquals(List.of("E2"), result.manifest().stream().map(PurgeManifestEntry::evidenceId).toList());
        assertEquals(EvidenceStatus.RETAINED, evidenceService.get("E1").status());
        assertEquals(EvidenceStatus.PURGED, evidenceService.get("E2").status());
    }

    @Test
    void confirmRollsBackAtomicallyWhenManifestWriteFails() {
        register("E1", "c1", T0.plusSeconds(100));
        PurgeBatch batch = purgeService.mark(mark("M1", T0.plusSeconds(600), null));

        // 人为制造清单唯一键冲突：确认中途写清单失败，整笔事务必须回滚。
        jdbc.update("INSERT INTO purge_manifest (batch_id, evidence_id, sha256, purged_at) "
                + "VALUES (?, 'E1', '00', ?)", batch.batchId(), java.sql.Timestamp.from(T0));

        assertThrows(Exception.class, () -> purgeService.confirm(batch.batchId()));

        assertEquals(EvidenceStatus.MARKED, evidenceService.get("E1").status(), "回滚后证据仍为 MARKED");
        assertEquals("MARKED",
                jdbc.queryForObject("SELECT status FROM purge_batch WHERE batch_id=?",
                        String.class, batch.batchId()));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM purge_manifest WHERE batch_id=?",
                Integer.class, batch.batchId()), "仅保留预置的冲突行，确认写入全部回滚");

        // 移除冲突后可重新确认成功。
        jdbc.update("DELETE FROM purge_manifest WHERE batch_id=?", batch.batchId());
        PurgeService.ConfirmResult retried = purgeService.confirm(batch.batchId());
        assertEquals(List.of("E1"), retried.manifest().stream().map(PurgeManifestEntry::evidenceId).toList());
        assertEquals(EvidenceStatus.PURGED, evidenceService.get("E1").status());
    }

    @Test
    void repeatedConfirmReturnsFirstFrozenManifest() {
        register("E1", "c1", T0.plusSeconds(100));
        PurgeBatch batch = purgeService.mark(mark("M1", T0.plusSeconds(600), null));

        PurgeService.ConfirmResult first = purgeService.confirm(batch.batchId());
        PurgeService.ConfirmResult second = purgeService.confirm(batch.batchId());
        PurgeService.ConfirmResult third = purgeService.confirm(batch.batchId());

        assertEquals(1, first.manifest().size());
        assertEquals(first.manifest().get(0).sha256(), second.manifest().get(0).sha256());
        assertEquals(first.manifest().get(0).purgedAt(), second.manifest().get(0).purgedAt());
        assertEquals(second.manifest().get(0).evidenceId(), third.manifest().get(0).evidenceId());
        assertEquals(first.batch().finalizedAt(), third.batch().finalizedAt());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM purge_manifest", Integer.class));
        assertEquals("PURGED",
                jdbc.queryForObject("SELECT status FROM purge_batch WHERE batch_id=?",
                        String.class, batch.batchId()));
    }

    @Test
    void markReplayWithSameParametersReturnsSameBatch() {
        register("E1", "c1", T0.plusSeconds(100));
        MarkPurgeRequest req = mark("M1", T0.plusSeconds(600), 0L);

        PurgeBatch first = purgeService.mark(req);
        PurgeBatch replay = purgeService.mark(req);

        assertEquals(first.batchId(), replay.batchId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM purge_batch", Integer.class));
    }

    @Test
    void sameMarkRequestKeyDifferentCutoffReturns409() {
        register("E1", "c1", T0.plusSeconds(100));
        purgeService.mark(mark("M1", T0.plusSeconds(600), null));

        ApiException ex = assertThrows(ApiException.class,
                () -> purgeService.mark(mark("M1", T0.plusSeconds(900), null)));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM purge_batch", Integer.class));
    }

    @Test
    void sameMarkRequestKeyDifferentExpectedVersionReturns409() {
        register("E1", "c1", T0.plusSeconds(100));
        purgeService.mark(mark("M1", T0.plusSeconds(600), null));

        ApiException ex = assertThrows(ApiException.class,
                () -> purgeService.mark(mark("M1", T0.plusSeconds(600), 7L)));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    void confirmUnknownBatchReturns404() {
        ApiException ex = assertThrows(ApiException.class, () -> purgeService.confirm("B-GHOST"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    void purgedEvidenceCanBeQueriedWithManifestAndCustody() {
        register("E1", "c1", T0.plusSeconds(100));
        String sha = evidenceService.get("E1").sha256();
        PurgeBatch batch = purgeService.mark(mark("M1", T0.plusSeconds(600), null));
        purgeService.confirm(batch.batchId());

        assertEquals(EvidenceStatus.PURGED, evidenceService.get("E1").status());
        PurgeManifestEntry entry = purgeService.getManifest(batch.batchId()).get(0);
        assertEquals(sha, entry.sha256());
        List<String> types = jdbc.queryForList(
                "SELECT event_type FROM custody_event WHERE evidence_id='E1' ORDER BY id", String.class);
        assertEquals(List.of("REGISTERED", "MARKED", "PURGED"), types);
    }
}
