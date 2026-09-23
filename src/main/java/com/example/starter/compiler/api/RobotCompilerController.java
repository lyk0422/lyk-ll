package com.example.starter.compiler.api;

import com.example.starter.compiler.service.RobotCompilerService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 机器人模型编译接口。
 */
@RestController
@RequestMapping("/api/robot-models")
public class RobotCompilerController {

    private final RobotCompilerService service;

    public RobotCompilerController(RobotCompilerService service) {
        this.service = service;
    }

    @PostMapping("/compilations")
    @ResponseStatus(HttpStatus.OK)
    public CompileResult compile(@Valid @RequestBody CompileRequest request) {
        return service.compile(request);
    }
}
