package com.study.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 响应码枚举
 */
@Getter
@AllArgsConstructor
public enum ResponseCode {
    
    /**
     * 成功
     */
    SUCCESS(200, "操作成功"),
    
    /**
     * 通用错误
     */
    ERROR(500, "操作失败"),
    
    /**
     * 参数错误
     */
    BAD_REQUEST(400, "请求参数错误"),
    
    /**
     * 未授权
     */
    UNAUTHORIZED(401, "未授权"),
    
    /**
     * 禁止访问
     */
    FORBIDDEN(403, "禁止访问"),
    
    /**
     * 资源未找到
     */
    NOT_FOUND(404, "资源未找到"),
    
    /**
     * 服务器内部错误
     */
    INTERNAL_SERVER_ERROR(500, "服务器内部错误"),
    
    /**
     * 业务错误
     */
    BUSINESS_ERROR(1000, "业务处理失败"),
    
    /**
     * 数据验证失败
     */
    VALIDATION_ERROR(1001, "数据验证失败"),
    
    /**
     * 数据不存在
     */
    DATA_NOT_FOUND(1002, "数据不存在"),
    
    /**
     * 数据已存在
     */
    DATA_ALREADY_EXISTS(1003, "数据已存在");
    
    /**
     * 响应码
     */
    private final Integer code;
    
    /**
     * 响应消息
     */
    private final String message;
}
