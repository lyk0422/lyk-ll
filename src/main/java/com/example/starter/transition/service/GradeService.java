package com.example.starter.transition.service;

import com.example.starter.transition.api.GradeUpsertRequest;
import com.example.starter.transition.po.Grade;
import com.example.starter.transition.repo.GradeRepository;
import com.example.starter.transition.web.NotFoundException;
import com.example.starter.transition.web.UnprocessableException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 品种与工艺窗口服务；窗口边界恰达视为合格。
 */
@Service
public class GradeService {

    private final GradeRepository grades;

    public GradeService(GradeRepository grades) {
        this.grades = grades;
    }

    /**
     * 登记或更新合成品种；已存在同编码品种时按新参数更新（窗口校验不通过则 422）。
     */
    public Grade upsert(GradeUpsertRequest request) {
        if (request.speedMin().compareTo(request.speedMax()) > 0) {
            throw new UnprocessableException("车速窗口下限不能大于上限");
        }
        if (request.consistencyMin().compareTo(request.consistencyMax()) > 0) {
            throw new UnprocessableException("上料浓度窗口下限不能大于上限");
        }
        Grade grade = new Grade();
        grade.gradeCode = request.gradeCode();
        grade.basisWeight = request.basisWeight();
        grade.ashLevel = request.ashLevel();
        grade.speedMin = request.speedMin();
        grade.speedMax = request.speedMax();
        grade.consistencyMin = request.consistencyMin();
        grade.consistencyMax = request.consistencyMax();
        grade.tensileMin = request.tensileMin();
        if (grades.findByCode(grade.gradeCode).isPresent()) {
            grades.update(grade);
        } else {
            grades.insert(grade);
        }
        return grades.findByCode(grade.gradeCode).orElseThrow();
    }

    public Grade require(String gradeCode) {
        return grades.findByCode(gradeCode)
                .orElseThrow(() -> new NotFoundException("品种不存在: " + gradeCode));
    }

    public List<Grade> list() {
        return grades.findAll();
    }
}
