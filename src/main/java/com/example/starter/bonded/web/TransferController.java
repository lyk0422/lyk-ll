package com.example.starter.bonded.web;

import com.example.starter.bonded.dto.ApplyTransferRequest;
import com.example.starter.bonded.dto.TransferView;
import com.example.starter.bonded.dto.VerifyRequest;
import com.example.starter.bonded.service.TransferService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 保税转关 API。
 */
@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private final TransferService service;

    public TransferController(TransferService service) {
        this.service = service;
    }

    /**
     * 转关申请（幂等）。同键同参返回首次结果，同键异参返回 409。
     */
    @PostMapping
    public ResponseEntity<String> apply(@Valid @RequestBody ApplyTransferRequest req) {
        TransferService.IdempotentResult result = service.apply(req);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.bodyJson());
    }

    /**
     * 起运地核注：库存冻结扣减与状态迁移同事务。
     */
    @PostMapping("/{requestId}/endorse")
    public TransferView endorse(@PathVariable String requestId) {
        return service.endorse(requestId);
    }

    /**
     * 起运离港，进入在途。
     */
    @PostMapping("/{requestId}/depart")
    public TransferView depart(@PathVariable String requestId) {
        return service.depart(requestId);
    }

    /**
     * 目的地分批核销（按 batchRequestId 幂等）。
     */
    @PostMapping("/{requestId}/verify")
    public ResponseEntity<String> verify(@PathVariable String requestId,
                                         @Valid @RequestBody VerifyRequest req) {
        TransferService.IdempotentResult result = service.verify(requestId, req);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.bodyJson());
    }

    /**
     * 撤销（仅在途前），原子恢复起运仓库存。
     */
    @PostMapping("/{requestId}/cancel")
    public TransferView cancel(@PathVariable String requestId) {
        return service.cancel(requestId);
    }

    /**
     * 海关复核确认解除异常。
     */
    @PostMapping("/{requestId}/resolve")
    public TransferView resolve(@PathVariable String requestId,
                                @Valid @RequestBody ResolveRequest req) {
        return service.resolveAbnormal(requestId, req.reviewer());
    }

    /**
     * 查询单据（含商品项与状态留痕）。
     */
    @GetMapping("/{requestId}")
    public TransferView get(@PathVariable String requestId) {
        return service.get(requestId);
    }

    /**
     * 手动触发超时扫描（定时任务之外的管理入口），返回本次置为异常的 requestId 列表。
     */
    @PostMapping("/sweep-expired")
    public List<String> sweepExpired() {
        return service.sweepExpired();
    }

    /**
     * 复核解除请求。
     *
     * @param reviewer 海关复核人标识
     */
    public record ResolveRequest(@NotBlank(message = "复核人不能为空") String reviewer) {
    }
}
