package com.study.controller;

import com.study.dto.AnswerDTO;
import com.study.dto.ApiResponse;
import com.study.service.AnswerService;
import com.study.service.AnswerStatistic;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/answer")
@Slf4j
public class AnswerController {

    @Autowired
    private AnswerService answerService;

    /**
     * 学生提交答案（先走redis，再落库）
     */
    @PostMapping("/submit-redis")
    public ApiResponse<String> submitAnswer(@Validated @RequestBody AnswerDTO answerDTO) {
        long startTime = System.currentTimeMillis();
        try {
            log.info("开始处理答案提交请求，planId: {}, questionId: {}, studentId: {}, answer: {}", 
                    answerDTO.getPlanId(), answerDTO.getQuestionId(), answerDTO.getStudentId(), answerDTO.getAnswer());
            
            answerService.submitAnswer(answerDTO);
            
            long endTime = System.currentTimeMillis();
            log.info("答案提交请求处理完成，耗时: {}ms, planId: {}, questionId: {}, studentId: {}", 
                    (endTime - startTime), answerDTO.getPlanId(), answerDTO.getQuestionId(), answerDTO.getStudentId());
            return ApiResponse.success("Answer submitted successfully");
        } catch (Exception e) {
            long endTime = System.currentTimeMillis();
            log.error("答案提交请求处理失败，耗时: {}ms, planId: {}, questionId: {}, studentId: {}", 
                    (endTime - startTime), answerDTO.getPlanId(), answerDTO.getQuestionId(), answerDTO.getStudentId(), e);
            return ApiResponse.error("Failed to submit answer: " + e.getMessage());
        }
    }

    /**
     * 教师查询答题统计（先从Redis获取，再从数据库库获取）
     */
    @GetMapping("/statistic-redis/{questionId}/{planId}")
    public ApiResponse<AnswerStatistic> getAnswerStatistic(@PathVariable Long questionId, @PathVariable Integer planId) {
        AnswerStatistic statistic = answerService.getAnswerStatistic(questionId, planId);
        return ApiResponse.success(statistic);
    }

    /**
     * 教师查询答题统计（直接从数据库获取）
     */
    @GetMapping("/statistic-db/{questionId}/{planId}")
    public ApiResponse<AnswerStatistic> getAnswerStatisticByDB(@PathVariable Long questionId, @PathVariable Integer planId) {
        AnswerStatistic statistic = answerService.getAnswerStatisticByDB(questionId, planId);
        return ApiResponse.success(statistic);
    }

    /**
     * 学生提交答案（先判断后修改，一个学生只有一条回答记录）
     */
    @PostMapping("/submit-db")
    public ApiResponse<String> submitAnswerByDB(@Validated @RequestBody AnswerDTO answerDTO) {
        answerService.submitAnswerByDB(answerDTO);
        return ApiResponse.success("Answer submitted successfully");
    }

    /**
     * 学生提交答案（仅做插入，一个学生插入多条回答记录）
     */
    @PostMapping("/submit-db-multi")
    public ApiResponse<String> submitAnswerByDBMulti(@Validated @RequestBody AnswerDTO answerDTO) {
        answerService.submitAnswerByDBMulti(answerDTO);
        return ApiResponse.success("Answer submitted successfully");
    }

}
