package com.example.starter.web;

import com.example.starter.domain.ZoneAuthorization;
import com.example.starter.service.AuthorizationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

/**
 * 重叠区授权接口：申请（幂等）、释放、查询。
 */
@RestController
@RequestMapping("/api")
public class AuthorizationController {

    private final AuthorizationService authorizationService;

    public AuthorizationController(AuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    /**
     * 申请重叠区授权。同 requestId 同参重放返回首次结果（200），异参返回 409。
     */
    @PostMapping("/zones/{zoneId}/authorizations")
    public ResponseEntity<AuthorizationDtos.Response> request(
            @PathVariable String zoneId, @RequestBody AuthorizationDtos.Request body) {
        AuthorizationService.AuthorizationResult result = authorizationService.request(
                zoneId, body.requestId(), body.craneId(), body.startTime(), body.endTime(), body.loadTonnes());
        AuthorizationDtos.Response response = AuthorizationDtos.Response.of(result.authorization(), result.replayed());
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(response);
    }

    /**
     * 释放授权。action=COMPLETE（作业完成）或 CANCEL（取消）；重复释放幂等返回当前状态。
     */
    @PostMapping("/authorizations/{id}/release")
    public AuthorizationDtos.Response release(@PathVariable long id,
                                              @RequestBody AuthorizationDtos.ReleaseRequest body) {
        AuthorizationService.ReleaseAction action = AuthorizationService.ReleaseAction.valueOf(
                body.action() == null ? "" : body.action().trim().toUpperCase(Locale.ROOT)
                        .replace("COMPLETED", "COMPLETE").replace("CANCELLED", "CANCEL"));
        ZoneAuthorization auth = authorizationService.release(id, action);
        return AuthorizationDtos.Response.of(auth, false);
    }
}
