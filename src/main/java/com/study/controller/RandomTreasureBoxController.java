package com.study.controller;

import com.study.dto.RandomBoxRequest;
import com.study.service.RandomTreasureBoxService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/random-box")
public class RandomTreasureBoxController {

    @Autowired
    private RandomTreasureBoxService randomTreasureBoxService;

    /**
     * 生成随机宝箱
     */
    @PostMapping("/generate")
    public String generateRandomBoxes(@Validated @RequestBody RandomBoxRequest request) {
        return randomTreasureBoxService.generateRandomBoxes(request);
    }

    /**
     * 学生抢宝箱
     */
    @PostMapping("/grab/{activityId}/{userId}")
    public String grabRandomBox(@PathVariable Long activityId, @PathVariable Long userId) {
        return randomTreasureBoxService.grabRandomBox(activityId, userId);
    }
}
