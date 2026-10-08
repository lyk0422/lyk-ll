package com.example.starter.trace.web;

import com.example.starter.trace.dto.CreateRecallRequest;
import com.example.starter.trace.dto.RecallResponse;
import com.example.starter.trace.dto.WithdrawRecallRequest;
import com.example.starter.trace.service.RecallService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 来源召回接口：按蜂场加精确花期召回，同蜂场同花期最多一条 ACTIVE。
 */
@RestController
@RequestMapping("/api/recalls")
public class RecallController {

    private final RecallService recallService;

    public RecallController(RecallService recallService) {
        this.recallService = recallService;
    }

    /**
     * 创建来源召回，原子冻结当时全部相关采收批并标记被引用加工批。
     */
    @PostMapping
    public RecallResponse createRecall(@Valid @RequestBody CreateRecallRequest request) {
        return recallService.createRecall(request);
    }

    /**
     * 查询召回详情，含首次冻结清单、后来追加关联与加工批隔离标记。
     */
    @GetMapping("/{recallId}")
    public RecallResponse getRecall(@PathVariable long recallId) {
        return recallService.getRecall(recallId);
    }

    /**
     * 撤回来源召回，须两名不同质检员独立凭据与 expectedVersion。
     */
    @PostMapping("/{recallId}/withdraw")
    public RecallResponse withdrawRecall(@PathVariable long recallId,
                                         @Valid @RequestBody WithdrawRecallRequest request) {
        return recallService.withdrawRecall(recallId, request);
    }
}
