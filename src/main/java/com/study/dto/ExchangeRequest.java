package com.study.dto;

import lombok.Data;
import jakarta.validation.constraints.NotNull;

@Data
public class ExchangeRequest {
    @NotNull(message = "用户ID不能为空")
    private Long userId;

    @NotNull(message = "宝箱类型不能为空")
    private Integer boxType;
}
