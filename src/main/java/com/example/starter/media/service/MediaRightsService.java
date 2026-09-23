package com.example.starter.media.service;

import com.example.starter.media.web.dto.CorrectGrantRequest;
import com.example.starter.media.web.dto.CreateGrantRequest;
import com.example.starter.media.web.dto.EvidenceItemView;
import com.example.starter.media.web.dto.GrantView;
import com.example.starter.media.web.dto.PlayReceiptRequest;
import com.example.starter.media.web.dto.PlayabilityCheckRequest;
import com.example.starter.media.web.dto.ReceiptView;
import com.example.starter.media.web.dto.RevokeGrantRequest;
import com.example.starter.media.web.dto.ScheduleView;
import com.example.starter.media.web.dto.SubmitScheduleRequest;

import java.util.List;

/**
 * 媒体版权窗口与播出授权校验服务。
 */
public interface MediaRightsService {

    ServiceResult<GrantView> createGrant(CreateGrantRequest request);

    ServiceResult<GrantView> correctGrant(String grantId, CorrectGrantRequest request);

    ServiceResult<GrantView> revokeGrant(String grantId, RevokeGrantRequest request);

    GrantView getGrant(String grantId);

    List<GrantView> listGrantHistory(String grantId);

    ServiceResult<ScheduleView> submitSchedule(SubmitScheduleRequest request);

    ScheduleView getSchedule(String scheduleId);

    ServiceResult<ReceiptView> markPlayed(PlayReceiptRequest request);

    boolean playable(PlayabilityCheckRequest request);

    List<EvidenceItemView> listScheduleEvidence(String scheduleId);

    List<EvidenceItemView> listGrantEvidence(String grantId);
}
