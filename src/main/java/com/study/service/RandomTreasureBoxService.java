package com.study.service;

import com.study.dto.RandomBoxRequest;

public interface RandomTreasureBoxService {
    /**
     * 生成随机宝箱
     */
    String generateRandomBoxes(RandomBoxRequest request);
    
    /**
     * 学生抢宝箱
     */
    String grabRandomBox(Long activityId, Long userId);
}
