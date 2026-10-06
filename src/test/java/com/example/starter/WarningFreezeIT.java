package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;

import com.example.starter.api.Requests.PermitApplyRequest;
import com.example.starter.api.Requests.SegmentRegisterRequest;
import com.example.starter.api.Requests.TreeRegisterRequest;
import com.example.starter.api.Requests.WarningCancelRequest;
import com.example.starter.api.Requests.WarningPublishRequest;
import com.example.starter.api.Responses.WarningResponse;
import com.example.starter.domain.WorkType;
import com.example.starter.repo.FreezeRepository;
import com.example.starter.repo.WarningRepository;
import com.example.starter.service.BusinessException;
import com.example.starter.service.PermitService;
import com.example.starter.service.SegmentService;
import com.example.starter.service.ServiceResult;
import com.example.starter.service.TreeService;
import com.example.starter.service.WarningService;

/**
 * 大风预警：发布后自动冻结高风险路段的新高空作业许可，冻结与预警状态在同一事务内生效。
 */
class WarningFreezeIT extends BaseIntegrationTest {

    @Autowired
    private SegmentService segmentService;
    @Autowired
    private TreeService treeService;
    @Autowired
    private WarningService warningService;
    @Autowired
    private PermitService permitService;
    @Autowired
    private FreezeRepository freezeRepository;
    @Autowired
    private WarningRepository warningRepository;

    private long highRiskSegmentId;
    private long lowRiskSegmentId;
    private long highRiskPlanId;

    @BeforeEach
    void setUpSegments() {
        segmentService.register(new SegmentRegisterRequest("seg-w1", "SEG-W1", "高风险路"));
        segmentService.register(new SegmentRegisterRequest("seg-w2", "SEG-W2", "低风险路"));
        // SEG-W1 一级风险，SEG-W2 四级风险
        treeService.register(new TreeRegisterRequest("tree-w1", "SEG-W1", "T-W1", 30.0, 2, 40, 2));
        treeService.register(new TreeRegisterRequest("tree-w2", "SEG-W2", "T-W2", 2.0, 0, 5, 0));
        highRiskSegmentId = jdbc.queryForObject(
                "SELECT id FROM road_segment WHERE code = 'SEG-W1'", Long.class);
        lowRiskSegmentId = jdbc.queryForObject(
                "SELECT id FROM road_segment WHERE code = 'SEG-W2'", Long.class);
        highRiskPlanId = jdbc.queryForObject(
                "SELECT id FROM pruning_plan WHERE segment_id = ?", Long.class, highRiskSegmentId);
    }

    @Test
    void publishFreezesHighRiskSegmentsOnly() {
        ServiceResult result = warningService.publish(new WarningPublishRequest("warn-1", "W-1", 3));
        WarningResponse body = (WarningResponse) result.body();
        assertEquals(java.util.List.of("SEG-W1"), body.frozenSegments());
        assertTrue(freezeRepository.existsBySegmentId(highRiskSegmentId));
        assertFalse(freezeRepository.existsBySegmentId(lowRiskSegmentId));
        assertEquals("ACTIVE", warningRepository.findByNo("W-1").orElseThrow().status());
    }

    @Test
    void frozenSegmentRejectsNewHighAltitudePermit() {
        warningService.publish(new WarningPublishRequest("warn-2", "W-2", 3));
        BusinessException e = assertThrows(BusinessException.class, () ->
                permitService.apply(new PermitApplyRequest(
                        "permit-w1", "SEG-W1", highRiskPlanId, WorkType.HIGH_ALTITUDE)));
        assertEquals(409, e.status());
        assertEquals("SEGMENT_FROZEN", e.code());
        // 地面作业不受高空冻结约束
        ServiceResult ground = permitService.apply(new PermitApplyRequest(
                "permit-w2", "SEG-W1", highRiskPlanId, WorkType.GROUND));
        assertEquals(200, ground.status());
    }

    @Test
    void freezeFailureRollsBackWarningInSameTransaction() {
        warningService.publish(new WarningPublishRequest("warn-3", "W-3", 3));
        assertEquals(1, freezeRepository.count());
        // 第二条预警触发冻结唯一约束冲突，整个事务回滚，预警不得残留
        assertThrows(DuplicateKeyException.class, () ->
                warningService.publish(new WarningPublishRequest("warn-4", "W-4", 4)));
        assertTrue(warningRepository.findByNo("W-4").isEmpty());
        assertEquals(1, warningRepository.count());
        assertEquals(1, freezeRepository.count());
    }

    @Test
    void cancelLiftsFreezeAndAllowsPermit() {
        warningService.publish(new WarningPublishRequest("warn-5", "W-5", 3));
        warningService.cancel(new WarningCancelRequest("warn-6", "W-5"));
        assertFalse(freezeRepository.existsBySegmentId(highRiskSegmentId));
        assertEquals("CANCELLED", warningRepository.findByNo("W-5").orElseThrow().status());
        ServiceResult permit = permitService.apply(new PermitApplyRequest(
                "permit-w3", "SEG-W1", highRiskPlanId, WorkType.HIGH_ALTITUDE));
        assertEquals(200, permit.status());
    }
}
