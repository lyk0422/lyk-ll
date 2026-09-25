package com.example.starter.nuclide;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 合成核素登记与查询接口。
 */
@RestController
@RequestMapping("/api/nuclides")
public class NuclideController {

    private final NuclideService service;

    public NuclideController(NuclideService service) {
        this.service = service;
    }

    /**
     * 登记核素请求。
     *
     * @param name            核素名称
     * @param halfLifeSeconds 半衰期，单位秒
     */
    public record RegisterRequest(@NotBlank String name, @Positive long halfLifeSeconds) {
    }

    /**
     * 登记合成核素。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public NuclideService.NuclideView register(@Valid @RequestBody RegisterRequest request) {
        return service.register(request.name(), request.halfLifeSeconds());
    }

    /**
     * 查询全部核素。
     */
    @GetMapping
    public List<NuclideService.NuclideView> list() {
        return service.list();
    }
}
