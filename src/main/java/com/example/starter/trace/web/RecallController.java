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
 * 来源召回接口：创建、撤回与详情查询。
 */
@RestController
@RequestMapping("/api/recalls")
public class RecallController {

    private final RecallService recallService;

    public RecallController(RecallService recallService) {
        this.recallService = recallService;
    }

    /**
     * 创建来源召回，冻结当时所有含该来源正贡献的采收批及其被引用加工批清单。
     */
    @PostMapping
    public RecallResponse createRecall(@Valid @RequestBody CreateRecallRequest request) {
        return recallService.createRecall(request);
    }

    /**
     * 撤回来源召回，须两名不同质检员确认与独立凭据，携带期望版本。
     */
    @PostMapping("/{recallId}/withdraw")
    public RecallResponse withdrawRecall(@PathVariable long recallId,
                                         @Valid @RequestBody WithdrawRecallRequest request) {
        return recallService.withdrawRecall(recallId, request);
    }

    /**
     * 查询召回详情，展示首次冻结清单与后来追加关联。
     */
    @GetMapping("/{recallId}")
    public RecallResponse getRecall(@PathVariable long recallId) {
        return recallService.getRecall(recallId);
    }
}
