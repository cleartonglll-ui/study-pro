package com.study.service;

import com.study.dto.ExchangeRequest;

public interface PointExchangeService {
    /**
     * TCC - Try阶段：预扣减积分
     */
    boolean tryExchange(Long userId, Integer boxType);
    
    /**
     * TCC - Confirm阶段：确认兑换
     */
    boolean confirmExchange(Long userId, Integer boxType);
    
    /**
     * TCC - Cancel阶段：取消兑换
     */
    boolean cancelExchange(Long userId, Integer boxType);
    
    /**
     * 完整的兑换流程
     */
    String exchangeTreasureBox(ExchangeRequest request);

    /**
     * 给用户添加积分
     * @param userId 用户ID
     * @param points 要添加的积分数量
     * @return 是否成功
     */
    boolean addPoints(Long userId, Integer points);

    /**
     * 查询用户积分
     * @param userId 用户ID
     * @return 用户积分
     */
    int getPoints(Long userId);
}
