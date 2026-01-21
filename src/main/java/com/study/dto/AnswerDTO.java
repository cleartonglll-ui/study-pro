package com.study.dto;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Data
public class AnswerDTO {
    @NotNull(message = "题目ID不能为空")
    private Long questionId;

    @NotNull(message = "学生ID不能为空")
    private Long studentId;

    @NotBlank(message = "答案不能为空")
    private String answer;

    @NotNull(message = "课次ID不能为空")
    private Integer planId;
}
