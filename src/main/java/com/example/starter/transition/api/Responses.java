package com.example.starter.transition.api;

import com.example.starter.transition.po.Grade;
import com.example.starter.transition.po.RollJudgement;
import com.example.starter.transition.po.TransitionOrder;
import com.example.starter.transition.po.TransitionRoll;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * API 响应记录类型集合。
 */
public final class Responses {

    private Responses() {
    }

    /** 品种响应。 */
    public record GradeResponse(String gradeCode, BigDecimal basisWeight, Integer ashLevel,
                                BigDecimal speedMin, BigDecimal speedMax,
                                BigDecimal consistencyMin, BigDecimal consistencyMax,
                                BigDecimal tensileMin) {
        public static GradeResponse from(Grade g) {
            return new GradeResponse(g.gradeCode, g.basisWeight, g.ashLevel,
                    g.speedMin, g.speedMax, g.consistencyMin, g.consistencyMax, g.tensileMin);
        }
    }

    /** 改品种单响应。 */
    public record OrderResponse(String orderNo, String machineId,
                                String fromGradeCode, String toGradeCode,
                                BigDecimal basisWeightFrom, BigDecimal basisWeightTo,
                                Integer ashFrom, Integer ashTo,
                                String status, String switchMode, Boolean wireFlush,
                                Integer switchSeconds, BigDecimal machineSpeed,
                                BigDecimal consistency, BigDecimal transitionLengthM) {
        public static OrderResponse from(TransitionOrder o) {
            return new OrderResponse(o.orderNo, o.machineId, o.fromGradeCode, o.toGradeCode,
                    o.basisWeightFrom, o.basisWeightTo, o.ashFrom, o.ashTo,
                    o.status, o.switchMode, o.wireFlush, o.switchSeconds, o.machineSpeed,
                    o.consistency, o.transitionLengthM);
        }
    }

    /** 过渡纸卷响应。 */
    public record RollResponse(String rollNo, String orderNo, String machineId,
                               BigDecimal lengthM, String finalGrade, String inspectionStatus,
                               Boolean gatePassed, BigDecimal measuredBasisWeight,
                               Integer measuredAshLevel, BigDecimal measuredTensile,
                               Integer appealCount) {
        public static RollResponse from(TransitionRoll r) {
            return new RollResponse(r.rollNo, r.orderNo, r.machineId, r.lengthM,
                    r.finalGrade, r.inspectionStatus, r.gatePassed, r.measuredBasisWeight,
                    r.measuredAshLevel, r.measuredTensile, r.appealCount);
        }
    }

    /** 判定链明细响应。 */
    public record JudgementResponse(Integer roundNo, String result, Boolean gatePassed,
                                    String reason, String requestId, LocalDateTime createdAt) {
        public static JudgementResponse from(RollJudgement j) {
            return new JudgementResponse(j.roundNo, j.result, j.gatePassed,
                    j.reason, j.requestId, j.createdAt);
        }
    }

    /** 检验提交响应；late=true 表示归档后迟到，仅登记迟到事件。 */
    public record InspectionResponse(String rollNo, boolean late, String inspectionStatus,
                                     Boolean gatePassed, String finalGrade,
                                     JudgementResponse firstJudgement) {
    }

    /** 过渡卷详情：卷状态 + 完整改判链（可复算）。 */
    public record RollDetailResponse(RollResponse roll, List<JudgementResponse> judgementChain) {
    }

    /**
     * 改品种单详情：含过渡料长度复算值、方式判定复核与全部过渡卷明细。
     */
    public record OrderDetailResponse(OrderResponse order,
                                      BigDecimal recomputedTransitionLengthM,
                                      String recomputedSwitchMode,
                                      Boolean recomputedWireFlush,
                                      List<RollResponse> rolls,
                                      Integer lateEventCount) {
    }
}
