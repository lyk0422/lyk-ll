package com.example.starter.cosmetic.service;

import com.example.starter.cosmetic.web.dto.DestroyLabelsRequest;
import com.example.starter.cosmetic.web.dto.IssueLabelsRequest;
import com.example.starter.cosmetic.web.dto.LabelDestructionResponse;
import com.example.starter.cosmetic.web.dto.LabelDestructionResultResponse;
import com.example.starter.cosmetic.web.dto.LabelIssueResponse;
import com.example.starter.cosmetic.web.dto.LabelIssueResultResponse;
import com.example.starter.cosmetic.web.dto.PrintRunResponse;
import com.example.starter.cosmetic.web.dto.PrintRunResultResponse;
import com.example.starter.cosmetic.web.dto.RegisterPrintRunRequest;

import java.util.List;

/**
 * 包装标签印次台账服务：登记、领用、销毁、查询。
 *
 * <p>印次绑定已生效备案的具体修订；备案实质变更生效或注销时，
 * 旧印次剩余标签在同事务内原子禁用，已领凭据仅追加风险关联。</p>
 */
public interface LabelPrintService {

    /** 印次登记：引用当前有效备案与修订号，冻结标签文本摘要（儿童逐档一致）。 */
    PrintRunResultResponse register(RegisterPrintRunRequest request);

    /** 标签领用：不超未领余额，恰领完允许；领用前重查当前备案，旧版生效后不再出凭据。 */
    LabelIssueResultResponse issue(String printRunNo, IssueLabelsRequest request);

    /** 未领标签销毁登记：仅未领部分可销毁，留证；禁用印次不允许复活。 */
    LabelDestructionResultResponse destroy(String printRunNo, DestroyLabelsRequest request);

    /** 印次台账查询：可核对 印刷总数=已领+已毁+剩余，并区分禁用剩余。 */
    PrintRunResponse getPrintRun(String printRunNo);

    /** 印次的全部领用凭据（可追溯）。 */
    List<LabelIssueResponse> listIssues(String printRunNo);

    /** 印次的全部销毁登记。 */
    List<LabelDestructionResponse> listDestructions(String printRunNo);

    /** 包装批的全部领用凭据（跨印次追溯）。 */
    List<LabelIssueResponse> listBatchIssues(String batchNo);
}
