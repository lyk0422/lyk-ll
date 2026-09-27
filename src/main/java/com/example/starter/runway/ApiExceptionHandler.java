package com.example.starter.runway;

import com.example.starter.runway.dto.ErrorResponse;
import com.example.starter.runway.dto.PlanConflictView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * 全局异常处理：业务异常与参数校验异常转为统一错误报文。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(new ErrorResponse(ex.getCode(), ex.getMessage()));
    }

    /**
     * 例行计划航班冲突：422 报文之外在响应头 X-Conflict-Count 给出冲突数量，
     * 结构化证据通过计划冻结证据与日历影响查询获取，避免错误报文格式与既有契约分叉。
     */
    @ExceptionHandler(PlanConflictException.class)
    public ResponseEntity<ErrorResponse> handlePlanConflict(PlanConflictException ex) {
        List<PlanConflictView> conflicts = ex.getConflicts();
        return ResponseEntity.status(ex.getStatus())
                .header("X-Conflict-Calendar-Version", String.valueOf(ex.getCalendarVersion()))
                .header("X-Conflict-Count", String.valueOf(conflicts.size()))
                .body(new ErrorResponse(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .findFirst()
                .orElse("请求参数不合法");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("VALIDATION_FAILED", message));
    }
}
