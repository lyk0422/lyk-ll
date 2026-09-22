package com.example.starter.web;

import com.example.starter.route.RouteService;
import com.example.starter.web.dto.RouteResponse;
import com.example.starter.web.dto.SubmitRouteRequest;
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
 * 路线请求提交（按 requestId 幂等）与查询入口。
 */
@RestController
@RequestMapping("/api/routes")
public class RouteController {

    private final RouteService service;

    public RouteController(RouteService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.OK)
    public RouteResponse submit(@Valid @RequestBody SubmitRouteRequest request) {
        return service.submit(request);
    }

    @GetMapping("/{requestId}")
    public RouteResponse get(@PathVariable String requestId) {
        return service.get(requestId);
    }
}
