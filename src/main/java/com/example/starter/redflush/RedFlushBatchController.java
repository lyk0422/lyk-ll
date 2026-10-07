package com.example.starter.redflush;

import com.example.starter.redflush.RedFlushBatchService.BatchConfirmRequest;
import com.example.starter.redflush.RedFlushBatchService.BatchConfirmResponse;
import com.example.starter.redflush.RedFlushBatchService.BatchView;
import com.example.starter.redflush.RedFlushBatchService.BatchWithdrawRequest;
import com.example.starter.redflush.RedFlushBatchService.BatchWithdrawResponse;
import com.example.starter.redflush.RedFlushBatchService.CreateBatchRequest;
import com.example.starter.redflush.RedFlushBatchService.CreateBatchResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 整批红冲接口：整批受理、购销双方确认、撤回与详情查询（子单、预占、意见与额度勾稽）。
 */
@RestController
@RequestMapping("/api/red-flush/batches")
public class RedFlushBatchController {

    private final RedFlushBatchService redFlushBatchService;

    public RedFlushBatchController(RedFlushBatchService redFlushBatchService) {
        this.redFlushBatchService = redFlushBatchService;
    }

    @PostMapping
    public CreateBatchResponse createBatch(@RequestBody CreateBatchRequest request) {
        return redFlushBatchService.createBatch(request);
    }

    @PostMapping("/{batchNo}/confirm")
    public BatchConfirmResponse confirm(@PathVariable String batchNo,
                                        @RequestBody BatchConfirmRequest request) {
        return redFlushBatchService.confirm(batchNo, request);
    }

    @PostMapping("/{batchNo}/withdraw")
    public BatchWithdrawResponse withdraw(@PathVariable String batchNo,
                                          @RequestBody BatchWithdrawRequest request) {
        return redFlushBatchService.withdraw(batchNo, request);
    }

    @GetMapping("/{batchNo}")
    public BatchView getBatch(@PathVariable String batchNo) {
        return redFlushBatchService.getBatch(batchNo);
    }
}
