package com.example.starter.atm.web;

import org.springframework.http.HttpStatus;

/** 404：ATM、款箱、押运员、计划或挂账记录不存在。 */
public class NotFoundException extends ApiException {
    public NotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message);
    }
}
