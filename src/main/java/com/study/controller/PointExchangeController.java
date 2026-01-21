package com.study.controller;

import com.study.dto.AddPointsRequest;
import com.study.dto.ExchangeRequest;
import com.study.service.PointExchangeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/exchange")
public class PointExchangeController {

    @Autowired
    private PointExchangeService pointExchangeService;

    /**
     * 积分兑换稀有宝箱
     */
    @PostMapping("/treasure-box")
    public String exchangeTreasureBox(@Validated @RequestBody ExchangeRequest request) {
        return pointExchangeService.exchangeTreasureBox(request);
    }

    /**
     * 给用户添加积分
     */
    @PostMapping("/add-points")
    public String addPoints(@Validated @RequestBody AddPointsRequest request) {
        boolean success = pointExchangeService.addPoints(request.getUserId(), request.getPoints());
        return success ? "添加积分成功" : "添加积分失败";
    }
}
