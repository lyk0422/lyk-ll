package com.example.starter.flood.web;

import com.example.starter.flood.service.AdviceService;
import com.example.starter.flood.web.dto.AdviceConfirmRequest;
import com.example.starter.flood.web.dto.AdviceView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 替代建议入口：在途指令与新方案版本冲突时生成，人工确认留痕可查。
 */
@RestController
@RequestMapping("/api/advices")
public class AdviceController {

    private final AdviceService adviceService;

    public AdviceController(AdviceService adviceService) {
        this.adviceService = adviceService;
    }

    @GetMapping("/{adviceNo}")
    public AdviceView get(@PathVariable String adviceNo) {
        return adviceService.get(adviceNo);
    }

    @PostMapping("/{adviceNo}/confirm")
    public AdviceView confirm(@PathVariable String adviceNo,
                              @Valid @RequestBody AdviceConfirmRequest request) {
        return adviceService.confirm(adviceNo, request);
    }
}
