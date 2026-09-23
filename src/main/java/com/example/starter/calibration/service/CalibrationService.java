package com.example.starter.calibration.service;

import com.example.starter.calibration.api.CanaryReleaseRequest;
import com.example.starter.calibration.api.CreateParamRequest;
import com.example.starter.calibration.api.ParamView;
import com.example.starter.calibration.api.ReleaseView;
import com.example.starter.calibration.api.ResolutionView;
import com.example.starter.calibration.api.ResolveRequest;
import com.example.starter.calibration.api.ResolveResponse;
import com.example.starter.calibration.api.RollbackRequest;
import com.example.starter.calibration.domain.CanaryRelease;

import java.util.List;

/**
 * 标定参数领域服务：校验、审批、范围解析、原子灰度、回滚与历史追踪。
 */
public interface CalibrationService {

    ParamView create(CreateParamRequest request);

    ParamView approve(String paramKey, String version, String operator);

    ParamView activate(String paramKey, String version, String operator);

    List<ParamView> listVersions(String paramKey);

    CanaryRelease canaryRelease(CanaryReleaseRequest request);

    CanaryRelease rollback(RollbackRequest request);

    CanaryRelease promote(String releaseId, String operator);

    ReleaseView getRelease(String releaseId);

    ResolveResponse resolve(ResolveRequest request);

    ResolutionView getResolution(String resolutionId);
}
