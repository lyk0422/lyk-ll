package com.example.starter.dispatch.support;

import com.example.starter.dispatch.api.dto.GateTarget;
import com.example.starter.dispatch.api.dto.IssueOrderRequest;
import com.example.starter.dispatch.api.dto.ReceiptRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 测试请求构造工具。 */
public final class Requests {

    private Requests() {
    }

    public static GateTarget gate(String code, double opening) {
        return new GateTarget(code, BigDecimal.valueOf(opening));
    }

    public static IssueOrderRequest issue(String requestId, Instant deadline, GateTarget... gates) {
        return new IssueOrderRequest(requestId, List.of(gates), deadline);
    }

    public static IssueOrderRequest issue(String requestId, Instant deadline, List<GateTarget> gates) {
        return new IssueOrderRequest(requestId, gates, deadline);
    }

    public static IssueOrderRequest withExtraGate(IssueOrderRequest origin, GateTarget extra) {
        List<GateTarget> gates = new ArrayList<>(origin.gates());
        gates.add(extra);
        return new IssueOrderRequest(origin.requestId(), gates, origin.deadline());
    }

    public static IssueOrderRequest withDeadline(IssueOrderRequest origin, Instant deadline) {
        return new IssueOrderRequest(origin.requestId(), origin.gates(), deadline);
    }

    public static ReceiptRequest receipt(String gateCode, double actualOpening) {
        return new ReceiptRequest(gateCode, BigDecimal.valueOf(actualOpening));
    }
}
