package com.example.starter.robot;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 机器人模型编译 HTTP 接口。
 */
@RestController
@RequestMapping("/api/robot")
public class CompileController {

    private final CompileService compileService;

    public CompileController(CompileService compileService) {
        this.compileService = compileService;
    }

    @PostMapping("/compile")
    @ResponseStatus(HttpStatus.OK)
    public CompileResponse compile(@Valid @RequestBody CompileRequest request) {
        return compileService.compile(request);
    }
}
