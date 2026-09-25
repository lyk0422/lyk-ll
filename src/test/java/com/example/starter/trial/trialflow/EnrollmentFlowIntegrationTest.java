package com.example.starter.trial.trialflow;

import com.example.starter.trial.api.AuditEventResponse;
import com.example.starter.trial.api.EnrollmentRequest;
import com.example.starter.trial.api.EnrollmentResponse;
import com.example.starter.trial.api.UnblindingConfirmRequest;
import com.example.starter.trial.api.UnblindingResponse;
import com.example.starter.trial.api.VisitRequest;
import com.example.starter.trial.api.VisitResponse;
import com.example.starter.trial.service.BusinessRuleException;
import com.example.starter.trial.service.ConflictException;
import com.example.starter.trial.service.ForbiddenException;
import com.example.starter.trial.service.NotFoundException;
import com.example.starter.trial.service.RandomNumberSequencer;
import com.example.starter.trial.service.TrialService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 随机入组、幂等、区组比例与随机号不重发的 H2 事务测试。
 */
class EnrollmentFlowIntegrationTest extends AbstractTrialIntegrationTest {

    private static final String TRIAL = "CT-2026-001";
    private static final String SITE_01 = "SITE-01";
    private static final String SITE_02 = "SITE-02";
    private static final Instant CONSENT = Instant.parse("2026-03-01T05:30:00Z");

    @Autowired
    private TrialService trialService;

    @Autowired
    private RandomNumberSequencer sequencer;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(transactionManager);
    }

    private EnrollmentRequest enrollment(String requestId, String screenNo, String site) {
        return new EnrollmentRequest(requestId, TRIAL, screenNo, site, CONSENT);
    }

    @Test
    void blockOfFourKeepsDesignRatioAndRandomNumbersAreSequential() {
        List<EnrollmentResponse> responses = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            responses.add(trialService.enroll(
                    enrollment("REQ-" + i, "S-" + (100 + i), SITE_01)));
        }
        long a = responses.stream().filter(r -> r.blindedGroupCode().equals("A")).count();
        long b = responses.stream().filter(r -> r.blindedGroupCode().equals("B")).count();
        assertEquals(2, a, "区组内试验组编码 A 必须为 2");
        assertEquals(2, b, "区组内对照组编码 B 必须为 2");
        for (int i = 0; i < 4; i++) {
            assertEquals(i + 1L, responses.get(i).randomizationNo(), "随机号按中心内递增发放");
            assertFalse(responses.get(i).replayed());
        }
    }

    @Test
    void multipleBlocksEachKeepRatioAndNoFourConsecutiveSameGroup() {
        List<String> codes = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            EnrollmentResponse r = trialService.enroll(
                    enrollment("REQ-M" + i, "S-" + (200 + i), SITE_01));
            codes.add(r.blindedGroupCode());
            assertEquals(i, r.randomizationNo());
        }
        // 每个 4 例区组严格 2:2
        for (int blockStart = 0; blockStart < 12; blockStart += 4) {
            long a = codes.subList(blockStart, blockStart + 4).stream().filter("A"::equals).count();
            assertEquals(2, a, "区组 " + blockStart + " 起 4 例必须 2A2B");
        }
        // 任意连续 4 例窗口不可能同组
        for (int i = 0; i + 4 <= codes.size(); i++) {
            long distinct = codes.subList(i, i + 4).stream().distinct().count();
            assertEquals(2, distinct, "出现连续 4 例同组，位置 " + i);
        }
    }

    @Test
    void randomNumberSequencesAreIndependentPerSite() {
        EnrollmentResponse r1 = trialService.enroll(enrollment("REQ-S1A", "S-301", SITE_01));
        EnrollmentResponse r2 = trialService.enroll(enrollment("REQ-S1B", "S-302", SITE_01));
        EnrollmentResponse r3 = trialService.enroll(enrollment("REQ-S2A", "S-401", SITE_02));
        assertEquals(1L, r1.randomizationNo());
        assertEquals(2L, r2.randomizationNo());
        assertEquals(1L, r3.randomizationNo(), "另一中心独立从 1 开始");
    }

    @Test
    void sameKeySameParametersReplaysFirstAllocationWithoutNewNumber() {
        EnrollmentRequest request = enrollment("REQ-IDEM-1", "S-501", SITE_01);
        EnrollmentResponse first = trialService.enroll(request);
        long sequenceAfterFirst = currentSequenceNo();

        EnrollmentResponse replay = trialService.enroll(request);
        assertEquals(first.randomizationNo(), replay.randomizationNo());
        assertEquals(first.blindedGroupCode(), replay.blindedGroupCode());
        assertTrue(replay.replayed());
        assertEquals(sequenceAfterFirst, currentSequenceNo(), "回放不得再发新随机号");
        assertEquals(1L, countSubjects());
    }

    @Test
    void sameKeyDifferentParametersConflictsAndIssuesNoNumber() {
        EnrollmentRequest first = enrollment("REQ-IDEM-2", "S-502", SITE_01);
        trialService.enroll(first);
        long sequenceAfterFirst = currentSequenceNo();

        EnrollmentRequest tampered = new EnrollmentRequest("REQ-IDEM-2", TRIAL, "S-502",
                SITE_01, CONSENT.plusSeconds(1));
        ConflictException ex = assertThrows(ConflictException.class,
                () -> trialService.enroll(tampered));
        assertTrue(ex.getMessage().contains("different parameters"));
        assertEquals(sequenceAfterFirst, currentSequenceNo(), "指纹冲突在发号前拦截，不得发号");
    }

    @Test
    void duplicateSubjectRejectedBeforeANewNumberIsIssued() {
        trialService.enroll(enrollment("REQ-DUP-1", "S-601", SITE_01));
        assertEquals(1L, currentSequenceNo());

        // 同筛选号再次入组（不同 requestId）→ 409，且在发号前拦截
        assertThrows(ConflictException.class,
                () -> trialService.enroll(enrollment("REQ-DUP-2", "S-601", SITE_01)));

        assertEquals(1L, currentSequenceNo(), "顺序重复入组在发号前被唯一检查拦截，序列不推进");
        assertEquals(1L, countSubjects(), "失败不得产生受试者");

        // 下一例成功入组拿到 2 号
        EnrollmentResponse next = trialService.enroll(enrollment("REQ-DUP-3", "S-602", SITE_01));
        assertEquals(2L, next.randomizationNo());
    }

    @Test
    void blindedQueryNeverRevealsActualGroup() {
        trialService.enroll(enrollment("REQ-Q-1", "S-701", SITE_01));
        var blinded = trialService.getBlindedSubject(TRIAL, "S-701");
        assertEquals("SITE-01", blinded.siteCode());
        assertEquals(1L, blinded.randomizationNo());
        assertTrue(blinded.blindedGroupCode().equals("A") || blinded.blindedGroupCode().equals("B"));
        assertEquals("ENROLLED", blinded.status());
        assertThrows(NotFoundException.class, () -> trialService.getBlindedSubject(TRIAL, "NO-SUCH"));
    }

    @Test
    void numberIssuedInARolledBackBusinessTransactionIsNeverReused() {
        // 第 1 号正常发出
        long first = sequencer.nextRandomNumber(TRIAL, "SITE-BURN");
        assertEquals(1L, first);

        // 模拟业务事务失败回滚：外层回滚，但发号在 REQUIRES_NEW 独立事务中已提交
        assertThrows(IllegalStateException.class, () -> transactionTemplate().execute(status -> {
            long burned = sequencer.nextRandomNumber(TRIAL, "SITE-BURN");
            assertEquals(2L, burned);
            throw new IllegalStateException("simulated business failure after issuing number");
        }));

        // 回滚后再次发号必须得到 3 号，2 号已发出、绝不重用
        long afterRollback = sequencer.nextRandomNumber(TRIAL, "SITE-BURN");
        assertEquals(3L, afterRollback);
        assertEquals(3L, jdbc.queryForObject(
                "SELECT current_no FROM site_random_sequence WHERE trial_id = 'CT-2026-001' AND site_code = 'SITE-BURN'",
                Long.class));
    }

    private long currentSequenceNo() {
        return jdbc.queryForObject(
                "SELECT current_no FROM site_random_sequence WHERE trial_id = ? AND site_code = ?",
                Long.class, TRIAL, SITE_01);
    }

    private long countSubjects() {
        return jdbc.queryForObject("SELECT COUNT(1) FROM trial_subject WHERE trial_id = ?",
                Long.class, TRIAL);
    }
}
