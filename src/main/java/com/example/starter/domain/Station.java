package com.example.starter.domain;

/**
 * 换热站。
 *
 * @param id   主键
 * @param code 换热站编码，全局唯一
 * @param name 换热站名称
 */
public record Station(long id, String code, String name) {
}
