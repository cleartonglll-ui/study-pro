package com.study.dto;

import lombok.Data;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Data
public class RandomBoxRequest {
    @NotNull(message = "活动ID不能为空")
    private Long activityId;

    @NotNull(message = "学生人数不能为空")
    @Min(value = 1, message = "学生人数至少为1")
    private Integer studentCount;

    @NotNull(message = "最小金币数不能为空")
    @Min(value = 1, message = "最小金币数至少为1")
    private Integer minGold;

    @NotNull(message = "最大金币数不能为空")
    @Min(value = 1, message = "最大金币数至少为1")
    private Integer maxGold;
}
