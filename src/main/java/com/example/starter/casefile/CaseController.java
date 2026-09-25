package com.example.starter.casefile;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 案件接口。
 */
@RestController
@RequestMapping("/api/cases")
public class CaseController {

    private final CaseService service;

    public CaseController(CaseService service) {
        this.service = service;
    }

    @PostMapping("/{id}/close")
    public CaseResponse close(@PathVariable long id) {
        return service.close(id);
    }

    @GetMapping("/{id}")
    public CaseResponse get(@PathVariable long id) {
        return service.get(id);
    }
}
