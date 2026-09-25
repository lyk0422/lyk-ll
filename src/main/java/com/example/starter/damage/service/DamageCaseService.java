package com.example.starter.damage.service;

import com.example.starter.damage.dto.Requests.ApprovalRequest;
import com.example.starter.damage.dto.Requests.CloseRequest;
import com.example.starter.damage.dto.Requests.CompletionRequest;
import com.example.starter.damage.dto.Requests.EstimateRequest;
import com.example.starter.damage.dto.Requests.PhotoRequest;
import com.example.starter.damage.dto.Requests.RegisterRequest;
import com.example.starter.damage.dto.Requests.ReinspectionRequest;
import com.example.starter.damage.dto.Requests.SurveyRequest;
import com.example.starter.damage.dto.Views.ApprovalView;
import com.example.starter.damage.dto.Views.CaseDetailView;
import com.example.starter.damage.dto.Views.CaseView;
import com.example.starter.damage.dto.Views.CompletionView;
import com.example.starter.damage.dto.Views.EstimateView;
import com.example.starter.damage.dto.Views.PhotoResult;
import org.springframework.stereotype.Service;

/**
 * 残损案门面服务：检验、估价与放行等写操作均以 requestId 幂等，
 * 指纹覆盖 requestId 与全部业务字段，同键同参返回首次结果，同键异参返回 409。
 */
@Service
public class DamageCaseService {

    private final DamageCaseTxService txService;
    private final IdempotencyExecutor idempotency;

    public DamageCaseService(DamageCaseTxService txService, IdempotencyExecutor idempotency) {
        this.txService = txService;
        this.idempotency = idempotency;
    }

    /** 到港登记（幂等） */
    public CaseView register(RegisterRequest request) {
        return idempotency.execute("REGISTER", request.requestId(), request, CaseView.class,
                () -> txService.register(request));
    }

    /** 检验登记（幂等） */
    public CaseDetailView survey(long caseId, SurveyRequest request) {
        return idempotency.execute("SURVEY#" + caseId, request.requestId(), request, CaseDetailView.class,
                () -> txService.survey(caseId, request));
    }

    /** 估价（幂等；同键异参 409 且不产生新估价版本） */
    public EstimateView estimate(long caseId, EstimateRequest request) {
        return idempotency.execute("ESTIMATE#" + caseId, request.requestId(), request, EstimateView.class,
                () -> txService.estimate(caseId, request));
    }

    /** 修理批复（幂等） */
    public ApprovalView approve(long caseId, ApprovalRequest request) {
        return idempotency.execute("APPROVE#" + caseId, request.requestId(), request, ApprovalView.class,
                () -> txService.approve(caseId, request));
    }

    /** 修理完工（幂等） */
    public CompletionView completeRepair(long caseId, CompletionRequest request) {
        return idempotency.execute("COMPLETE#" + caseId, request.requestId(), request, CompletionView.class,
                () -> txService.completeRepair(caseId, request));
    }

    /** 复检（幂等） */
    public CaseView reinspect(long caseId, ReinspectionRequest request) {
        return idempotency.execute("REINSPECT#" + caseId, request.requestId(), request, CaseView.class,
                () -> txService.reinspect(caseId, request));
    }

    /** 结案放行（幂等；重复结案返回幂等成功） */
    public CaseView close(long caseId, CloseRequest request) {
        return idempotency.execute("CLOSE#" + caseId, request.requestId(), request, CaseView.class,
                () -> txService.close(caseId, request));
    }

    /** 检验照片登记；结案后迟到照片只记迟到事件 */
    public PhotoResult photo(long caseId, PhotoRequest request) {
        return txService.photo(caseId, request);
    }

    /** 案件全量明细 */
    public CaseDetailView detail(long caseId) {
        return txService.detail(caseId);
    }
}
