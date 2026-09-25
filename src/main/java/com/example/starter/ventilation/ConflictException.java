package com.example.starter.ventilation;

import org.springframework.http.HttpStatus;

/**
 * 409 冲突：同键异参、分区激活互斥、复位完成前再激活、共享风阀目标冲突等。
 */
public class ConflictException extends ServiceException {

    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
