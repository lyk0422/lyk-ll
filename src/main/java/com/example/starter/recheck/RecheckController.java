package com.example.starter.recheck;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 定量复检接口。
 */
@RestController
@RequestMapping("/api/batches/{batchId}")
public class RecheckController {

    private final RecheckService recheckService;

    public RecheckController(RecheckService recheckService) {
        this.recheckService = recheckService;
    }

    /**
     * 提交定量复检结果（幂等）：合格放行，不合格转监督处置并留样 48 小时。
     */
    @PostMapping("/rechecks")
    public RecheckResponse recheck(@PathVariable long batchId, @Valid @RequestBody RecheckRequest request) {
        return recheckService.recheck(batchId, request);
    }
}
