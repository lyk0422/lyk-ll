package com.example.starter.payment.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.payment.dto.DiffResolveRequest;
import com.example.starter.payment.dto.DiffResolveResponse;
import com.example.starter.payment.dto.DiffView;
import com.example.starter.payment.dto.ReconRequest;
import com.example.starter.payment.dto.ReconResult;
import com.example.starter.payment.service.ReconService;

import jakarta.validation.Valid;

/**
 * 渠道对账与差异核销接口。
 */
@RestController
@RequestMapping("/api/recon")
public class ReconController {

    private final ReconService reconService;

    public ReconController(ReconService reconService) {
        this.reconService = reconService;
    }

    /** 提交渠道对账文件（合成数据）执行逐笔勾对。 */
    @PostMapping
    public ReconResult reconcile(@Valid @RequestBody ReconRequest request) {
        return reconService.reconcile(request);
    }

    /** 查询批次对账差异列表。 */
    @GetMapping("/batches/{batchDate}/diffs")
    public List<DiffView> listDiffs(@PathVariable String batchDate) {
        return reconService.listDiffs(batchDate);
    }

    /** 核销/补记/退款一条差异；同一差异不可重复核销。 */
    @PostMapping("/diffs/{diffId}/resolve")
    public DiffResolveResponse resolve(@PathVariable long diffId,
            @Valid @RequestBody DiffResolveRequest request) {
        return reconService.resolve(diffId, request);
    }
}
