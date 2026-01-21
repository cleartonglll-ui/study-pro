package com.study.controller;

import com.study.dto.AnswerDTO;
import com.study.dto.ApiResponse;
import com.study.service.AnswerService;
import com.study.service.AnswerStatistic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/answer")
public class AnswerController {

    @Autowired
    private AnswerService answerService;

    /**
     * 学生提交答案
     */
    @PostMapping("/submit")
    public ApiResponse<String> submitAnswer(@Validated @RequestBody AnswerDTO answerDTO) {
        answerService.submitAnswer(answerDTO);
        return ApiResponse.success("Answer submitted successfully");
    }

    /**
     * 教师查询答题统计（从Redis获取）
     */
    @GetMapping("/statistic/{questionId}/{planId}")
    public ApiResponse<AnswerStatistic> getAnswerStatistic(@PathVariable Long questionId, @PathVariable Integer planId) {
        AnswerStatistic statistic = answerService.getAnswerStatistic(questionId, planId);
        return ApiResponse.success(statistic);
    }

    /**
     * 教师查询答题统计（从数据库获取）
     */
    @GetMapping("/statistic-db/{questionId}/{planId}")
    public ApiResponse<AnswerStatistic> getAnswerStatisticByDB(@PathVariable Long questionId, @PathVariable Integer planId) {
        AnswerStatistic statistic = answerService.getAnswerStatisticByDB(questionId, planId);
        return ApiResponse.success(statistic);
    }

    /**
     * 学生提交答案 直接落库
     */
    @PostMapping("/submit-db")
    public ApiResponse<String> submitAnswerByDB(@Validated @RequestBody AnswerDTO answerDTO) {
        answerService.submitAnswerByDB(answerDTO);
        return ApiResponse.success("Answer submitted successfully");
    }


}
