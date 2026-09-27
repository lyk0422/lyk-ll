package com.example.starter.fermentation.api;

import com.example.starter.fermentation.api.YeastDtos.RecycleRequest;
import com.example.starter.fermentation.api.YeastDtos.RegisterYeastRequest;
import com.example.starter.fermentation.service.IdempotencyService.StoredResponse;
import com.example.starter.fermentation.service.YeastService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 酵母代次限用与投酵回收追溯 API。写接口以 requestId 幂等：
 * 同键同参返回首次结果，同键异参返回 409 且不改变业务状态；
 * 修改操作携带 expectedVersion 乐观锁版本。
 */
@RestController
@RequestMapping("/api/yeast-units")
public class YeastController {

    private final YeastService yeastService;

    public YeastController(YeastService yeastService) {
        this.yeastService = yeastService;
    }

    @PostMapping
    public ResponseEntity<String> register(@Valid @RequestBody RegisterYeastRequest request) {
        return respond(yeastService.register(request));
    }

    @PostMapping("/{yeastCode}/recycle")
    public ResponseEntity<String> recycle(@PathVariable String yeastCode,
                                          @Valid @RequestBody RecycleRequest request) {
        return respond(yeastService.recycle(yeastCode, request));
    }

    @GetMapping("/{yeastCode}")
    public Object get(@PathVariable String yeastCode) {
        return yeastService.getUnit(yeastCode);
    }

    @GetMapping("/{yeastCode}/recycles")
    public Object recycles(@PathVariable String yeastCode) {
        return yeastService.getRecycles(yeastCode);
    }

    private ResponseEntity<String> respond(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
