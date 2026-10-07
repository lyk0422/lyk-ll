package com.example.starter.batch;

import com.example.starter.batch.BatchRedFlushService.BatchView;
import com.example.starter.batch.BatchRedFlushService.ConfirmBatchRequest;
import com.example.starter.batch.BatchRedFlushService.ConfirmBatchResponse;
import com.example.starter.batch.BatchRedFlushService.CreateBatchRequest;
import com.example.starter.batch.BatchRedFlushService.CreateBatchResponse;
import com.example.starter.batch.BatchRedFlushService.WithdrawBatchRequest;
import com.example.starter.batch.BatchRedFlushService.WithdrawBatchResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 整批红冲接口：整批受理、购销双方确认、撤回与详情查询（子单/预占/意见/额度勾稽）。
 */
@RestController
@RequestMapping("/api/red-flush/batches")
public class BatchRedFlushController {

    private final BatchRedFlushService batchRedFlushService;

    public BatchRedFlushController(BatchRedFlushService batchRedFlushService) {
        this.batchRedFlushService = batchRedFlushService;
    }

    @PostMapping
    public CreateBatchResponse createBatch(@RequestBody CreateBatchRequest request) {
        return batchRedFlushService.createBatch(request);
    }

    @PostMapping("/{batchNo}/confirm")
    public ConfirmBatchResponse confirmBatch(@PathVariable String batchNo,
                                             @RequestBody ConfirmBatchRequest request) {
        return batchRedFlushService.confirmBatch(batchNo, request);
    }

    @PostMapping("/{batchNo}/withdraw")
    public WithdrawBatchResponse withdrawBatch(@PathVariable String batchNo,
                                               @RequestBody WithdrawBatchRequest request) {
        return batchRedFlushService.withdrawBatch(batchNo, request);
    }

    @GetMapping("/{batchNo}")
    public BatchView getBatch(@PathVariable String batchNo) {
        return batchRedFlushService.getBatch(batchNo);
    }
}
