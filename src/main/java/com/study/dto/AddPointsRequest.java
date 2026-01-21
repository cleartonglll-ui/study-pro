package com.study.dto;

import lombok.Data;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Data
public class AddPointsRequest {
    @NotNull(message = "用户ID不能为空")
    private Long userId;

    @NotNull(message = "积分数量不能为空")
    @Min(value = 1, message = "积分数量必须大于0")
    private Integer points;
}
