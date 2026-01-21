package com.study.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 统一API响应封装类
 * @param <T> 响应数据类型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * 响应码
     */
    private Integer code;
    
    /**
     * 响应消息
     */
    private String message;
    
    /**
     * 响应数据
     */
    private T data;
    
    /**
     * 成功响应（无数据）
     */
    public static <T> ApiResponse<T> success() {
        return new ApiResponse<>(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getMessage(), null);
    }
    
    /**
     * 成功响应（带数据）
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getMessage(), data);
    }
    
    /**
     * 成功响应（自定义消息）
     */
    public static <T> ApiResponse<T> success(String message) {
        return new ApiResponse<>(ResponseCode.SUCCESS.getCode(), message, null);
    }
    
    /**
     * 成功响应（自定义消息和数据）
     */
    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(ResponseCode.SUCCESS.getCode(), message, data);
    }
    
    /**
     * 失败响应
     */
    public static <T> ApiResponse<T> error() {
        return new ApiResponse<>(ResponseCode.ERROR.getCode(), ResponseCode.ERROR.getMessage(), null);
    }
    
    /**
     * 失败响应（自定义消息）
     */
    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(ResponseCode.ERROR.getCode(), message, null);
    }
    
    /**
     * 失败响应（自定义响应码和消息）
     */
    public static <T> ApiResponse<T> error(Integer code, String message) {
        return new ApiResponse<>(code, message, null);
    }
    
    /**
     * 失败响应（使用响应码枚举）
     */
    public static <T> ApiResponse<T> error(ResponseCode responseCode) {
        return new ApiResponse<>(responseCode.getCode(), responseCode.getMessage(), null);
    }
    
    /**
     * 失败响应（使用响应码枚举，自定义消息）
     */
    public static <T> ApiResponse<T> error(ResponseCode responseCode, String message) {
        return new ApiResponse<>(responseCode.getCode(), message, null);
    }
}
