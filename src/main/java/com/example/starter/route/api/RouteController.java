package com.example.starter.route.api;

import com.example.starter.route.domain.ErrorCode;
import com.example.starter.route.domain.RequestState;
import com.example.starter.route.service.RouteService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本地路线规划前置 API：地图版本发布/查询/废弃、路线匹配提交与查询。
 */
@RestController
@RequestMapping("/api")
public class RouteController {

    private final RouteService routeService;

    public RouteController(RouteService routeService) {
        this.routeService = routeService;
    }

    @PostMapping("/maps")
    @ResponseStatus(HttpStatus.CREATED)
    public MapVersionResponse publish(@Valid @RequestBody PublishMapRequest request) {
        return routeService.publishMap(request);
    }

    @GetMapping("/maps/{version}")
    public MapVersionResponse getMap(@PathVariable String version) {
        return routeService.getMap(version);
    }

    @PostMapping("/maps/{version}/deprecate")
    public MapVersionResponse deprecate(@PathVariable String version) {
        return routeService.deprecateMap(version);
    }

    @PostMapping("/routes")
    public ResponseEntity<RouteResponse> submit(@Valid @RequestBody RouteSubmitRequest request) {
        RouteResponse response = routeService.submitRoute(request);
        if (RequestState.FAILED.name().equals(response.state())) {
            // 失败结果已持久化并可重放；HTTP 层返回与稳定错误码对应的状态码，响应体仍含完整候选解释
            return ResponseEntity.status(ErrorCode.valueOf(response.errorCode()).httpStatus())
                    .body(response);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/routes/{requestId}")
    public RouteResponse getRoute(@PathVariable String requestId) {
        return routeService.getRoute(requestId);
    }
}
