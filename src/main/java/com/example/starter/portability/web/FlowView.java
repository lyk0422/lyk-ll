package com.example.starter.portability.web;

import java.time.Instant;

import com.example.starter.portability.FlowState;
import com.example.starter.portability.PortabilityFlow;

/**
 * 流程视图：申请、确认、撤销、查询的统一响应报文。
 */
public record FlowView(
        Long flowId,
        String requestId,
        String msisdn,
        String donorOperator,
        String recipientOperator,
        FlowState state,
        String authCode,
        Instant authIssuedAt,
        Instant authExpiresAt,
        Instant createdAt,
        Instant updatedAt) {

    public static FlowView from(PortabilityFlow flow) {
        return new FlowView(flow.id(), flow.requestId(), flow.msisdn(), flow.donorOperator(),
                flow.recipientOperator(), flow.state(), flow.authCode(), flow.authIssuedAt(),
                flow.authExpiresAt(), flow.createdAt(), flow.updatedAt());
    }
}
