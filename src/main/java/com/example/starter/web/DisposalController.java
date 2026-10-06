package com.example.starter.web;

import com.example.starter.service.DisposalService;
import com.example.starter.service.IdempotencyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 处置指令回执接口。
 */
@RestController
public class DisposalController {

    /**
     * 回执确认请求；confirmedAt 缺省取服务器当前时间。
     */
    public record ReceiptRequest(
            @NotBlank String requestId,
            LocalDateTime confirmedAt
    ) {
    }

    private final DisposalService disposalService;
    private final IdempotencyService idempotencyService;
    private final JsonSupport jsonSupport;
    private final Clock clock;

    public DisposalController(DisposalService disposalService, IdempotencyService idempotencyService,
                              JsonSupport jsonSupport, Clock clock) {
        this.disposalService = disposalService;
        this.idempotencyService = idempotencyService;
        this.jsonSupport = jsonSupport;
        this.clock = clock;
    }

    @PostMapping("/api/disposals/{commandId}/receipt")
    public ResponseEntity<String> confirm(@PathVariable long commandId,
                                          @Valid @RequestBody ReceiptRequest request) {
        LocalDateTime confirmedAt = request.confirmedAt() != null ? request.confirmedAt()
                : LocalDateTime.now(clock);
        String canonical = "commandId=" + commandId + "&confirmedAt=" + confirmedAt;
        String body = idempotencyService.execute(request.requestId(), "receipt", canonical,
                () -> jsonSupport.toJson(disposalService.confirmReceipt(commandId, confirmedAt)));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }

    @GetMapping("/api/zones/{zoneCode}/disposals")
    public ResponseEntity<String> listByZone(@PathVariable String zoneCode) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(jsonSupport.toJson(disposalService.listByZone(zoneCode)));
    }
}
