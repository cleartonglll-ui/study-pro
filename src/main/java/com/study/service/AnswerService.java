package com.study.service;

import com.study.dto.AnswerDTO;
import com.study.entity.Answer;

public interface AnswerService {
    /**
     * 学生提交答案
     */
    void submitAnswer(AnswerDTO answerDTO);

    /**
     * 异步将答案保存到数据库
     */
    void saveAnswerToDbAsync(Answer answer);

    /**
     * 获取答题统计信息（从Redis获取）
     */
    AnswerStatistic getAnswerStatistic(Long questionId, Integer planId);

    /**
     * 获取答题统计信息（从数据库获取）
     */
    AnswerStatistic getAnswerStatisticByDB(Long questionId, Integer planId);

    /**
     * 学生提交答案到db
     */
    void submitAnswerByDB(AnswerDTO answerDTO);
}
