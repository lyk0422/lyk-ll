package com.example.starter.ili.api;

import com.example.starter.ili.AlignmentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * ILI 缺陷对齐 REST 入口。
 */
@RestController
@RequestMapping("/api/alignment-batches")
public class IliController {

    private final AlignmentService service;

    public IliController(AlignmentService service) {
        this.service = service;
    }

    /**
     * 导入双回合缺陷清单并对齐分级；按 requestId 幂等。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BatchView importBatch(@Valid @RequestBody ImportBatchRequest req) {
        return service.importBatch(req);
    }

    /**
     * 查询批次详情。
     */
    @GetMapping("/{id}")
    public BatchView getBatch(@PathVariable long id) {
        return service.getBatch(id);
    }

    /**
     * 提交复核结论（仅 OPEN 批次）。
     */
    @PostMapping("/{id}/reviews")
    public BatchView review(@PathVariable long id, @Valid @RequestBody ReviewRequest req) {
        return service.review(id, req);
    }

    /**
     * 封板（终态，重复调用幂等成功）。
     */
    @PostMapping("/{id}/seal")
    public BatchView seal(@PathVariable long id) {
        return service.seal(id);
    }
}
