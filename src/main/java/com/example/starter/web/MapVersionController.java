package com.example.starter.web;

import com.example.starter.map.MapVersionService;
import com.example.starter.map.MapVersionView;
import com.example.starter.web.dto.PublishMapVersionRequest;
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
 * 地图版本发布、查询与废弃入口。
 */
@RestController
@RequestMapping("/api/map-versions")
public class MapVersionController {

    private final MapVersionService service;

    public MapVersionController(MapVersionService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MapVersionView publish(@Valid @RequestBody PublishMapVersionRequest request) {
        return service.publish(request);
    }

    @GetMapping("/{versionId}")
    public MapVersionView get(@PathVariable String versionId) {
        return service.get(versionId);
    }

    @PostMapping("/{versionId}/deprecate")
    public MapVersionView deprecate(@PathVariable String versionId) {
        return service.deprecate(versionId);
    }
}
