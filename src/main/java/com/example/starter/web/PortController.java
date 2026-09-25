package com.example.starter.web;

import java.time.Instant;
import java.util.List;

import com.example.starter.port.EffectivenessRecord;
import com.example.starter.port.PortEvent;
import com.example.starter.port.PortFlow;
import com.example.starter.port.PortService;
import com.example.starter.port.PortStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 携号转网流程 API：申请、签码、携出方确认、生效广播、撤销与查询。
 */
@RestController
@RequestMapping("/api/port-requests")
public class PortController {

    private final PortService portService;

    public PortController(PortService portService) {
        this.portService = portService;
    }

    /**
     * 提交携转申请（幂等：同 requestId 同参返回首次结果，异参返回 409）。
     */
    @PostMapping
    public PortFlowResponse apply(@Valid @RequestBody ApplyRequest request) {
        PortFlow flow = portService.apply(request.requestId(), request.msisdn(),
                request.donorOperator(), request.recipientOperator());
        return PortFlowResponse.from(flow);
    }

    /**
     * 签发授权码（24 小时有效，UTC；已签发时重复调用返回原授权码）。
     */
    @PostMapping("/{id}/auth-code")
    public PortFlowResponse issueAuthCode(@PathVariable long id) {
        return PortFlowResponse.from(portService.issueAuthCode(id));
    }

    /**
     * 携出方确认。
     */
    @PostMapping("/{id}/confirm")
    public PortFlowResponse confirm(@PathVariable long id,
                                    @Valid @RequestBody ConfirmRequest request) {
        return PortFlowResponse.from(portService.confirm(id, request.authCode()));
    }

    /**
     * 生效广播（幂等；仅 02:00-04:00 UTC 窗口内受理，04:00 整点视为窗口外）。
     */
    @PostMapping("/{id}/broadcast")
    public EffectivenessResponse broadcast(@PathVariable long id,
                                           @Valid @RequestBody BroadcastRequest request) {
        EffectivenessRecord record = portService.broadcast(id, request.requestId(), request.channel());
        return EffectivenessResponse.from(record);
    }

    /**
     * 用户在生效前撤销（终态）。
     */
    @PostMapping("/{id}/cancel")
    public PortFlowResponse cancel(@PathVariable long id,
                                   @Valid @RequestBody CancelRequest request) {
        return PortFlowResponse.from(portService.cancel(id, request.reason()));
    }

    /**
     * 查询流程当前状态。
     */
    @GetMapping("/{id}")
    public PortFlowResponse get(@PathVariable long id) {
        return PortFlowResponse.from(portService.getFlow(id));
    }

    /**
     * 按发生顺序查询流程事件。
     */
    @GetMapping("/{id}/events")
    public List<EventResponse> events(@PathVariable long id) {
        return portService.listEvents(id).stream().map(EventResponse::from).toList();
    }

    /** 申请请求体；requestId 为幂等键，指纹覆盖全部业务字段。 */
    public record ApplyRequest(
            @NotBlank @Size(max = 64) String requestId,
            @NotBlank @Pattern(regexp = "\\d{5,20}", message = "msisdn 须为 5-20 位数字") String msisdn,
            @NotBlank @Size(max = 32) String donorOperator,
            @NotBlank @Size(max = 32) String recipientOperator) {
    }

    /** 携出方确认请求体。 */
    public record ConfirmRequest(@NotBlank String authCode) {
    }

    /** 生效广播请求体；requestId 为幂等键。 */
    public record BroadcastRequest(
            @NotBlank @Size(max = 64) String requestId,
            @NotBlank @Size(max = 32) String channel) {
    }

    /** 撤销请求体。 */
    public record CancelRequest(@NotBlank @Size(max = 255) String reason) {
    }

    /** 流程状态响应。 */
    public record PortFlowResponse(
            long id,
            String requestId,
            String msisdn,
            String donorOperator,
            String recipientOperator,
            PortStatus status,
            String authCode,
            Instant authCodeIssuedAt,
            Instant authCodeExpiresAt,
            Instant effectiveAt,
            Instant cancelledAt,
            String cancelReason) {

        static PortFlowResponse from(PortFlow flow) {
            return new PortFlowResponse(flow.id(), flow.requestId(), flow.msisdn(),
                    flow.donorOperator(), flow.recipientOperator(), flow.status(),
                    flow.authCode(), flow.authCodeIssuedAt(), flow.authCodeExpiresAt(),
                    flow.effectiveAt(), flow.cancelledAt(), flow.cancelReason());
        }
    }

    /** 生效记录响应。 */
    public record EffectivenessResponse(
            long id,
            long portRequestId,
            String requestId,
            String msisdn,
            Instant effectiveAt) {

        static EffectivenessResponse from(EffectivenessRecord record) {
            return new EffectivenessResponse(record.id(), record.portRequestId(),
                    record.requestId(), record.msisdn(), record.effectiveAt());
        }
    }

    /** 流程事件响应。 */
    public record EventResponse(
            long id,
            String eventType,
            String detail,
            Instant createdAt) {

        static EventResponse from(PortEvent event) {
            return new EventResponse(event.id(), event.eventType(), event.detail(), event.createdAt());
        }
    }
}
