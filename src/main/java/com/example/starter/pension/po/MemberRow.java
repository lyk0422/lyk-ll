package com.example.starter.pension.po;

import com.example.starter.pension.domain.MemberStatus;

import java.time.LocalDate;

/**
 * member 表行对象。
 *
 * @param id       主键
 * @param memberNo 成员业务编号
 * @param name     成员姓名
 * @param hireDate 入职日期（UTC 日历日）
 * @param status   成员状态
 */
public record MemberRow(
        long id,
        String memberNo,
        String name,
        LocalDate hireDate,
        MemberStatus status) {
}
