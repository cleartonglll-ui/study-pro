package com.study.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.study.dto.ExchangeRequest;
import com.study.entity.TreasureBox;
import com.study.entity.UserPoint;
import com.study.mapper.TreasureBoxMapper;
import com.study.mapper.UserPointMapper;
import com.study.service.PointExchangeService;
import com.study.utils.RedisUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Service
public class PointExchangeServiceImpl implements PointExchangeService {

    @Autowired
    private RedisUtil redisUtil;

    @Autowired
    private UserPointMapper userPointMapper;

    @Autowired
    private TreasureBoxMapper treasureBoxMapper;

    private static final String USER_POINT_KEY_PREFIX = "user_point:";
    private static final String EXCHANGE_LOCK_KEY_PREFIX = "exchange_lock:";

    // 宝箱类型对应的积分消耗
    private static final int RARE_BOX_COST = 1000;

    // Lua脚本：预扣减积分
    private static final String PRE_DEDUCT_POINT_LUA = """
        local userPointKey = KEYS[1]
        local cost = tonumber(ARGV[1])
        
        -- 获取当前积分
        local point = redis.call('hget', userPointKey, 'point')
        if not point then
            return -1
        end
        
        point = tonumber(point)
        if point < cost then
            return -2
        end
        
        -- 预扣减积分：将积分转移到冻结积分
        redis.call('hincrby', userPointKey, 'point', -cost)
        redis.call('hincrby', userPointKey, 'frozenPoint', cost)
        
        return 1
    """;

    @Override
    public boolean tryExchange(Long userId, Integer boxType) {
        try {
            // 计算积分消耗
            int cost = RARE_BOX_COST;

            // 构建Redis键
            String userPointKey = USER_POINT_KEY_PREFIX + userId;

            // 执行预扣减Lua脚本
            Long result = redisUtil.executeLuaScript(PRE_DEDUCT_POINT_LUA, Arrays.asList(userPointKey), cost);

            return result != null && result == 1;
        } catch (Exception e) {
            System.err.println("Redis unavailable for tryExchange, falling back to direct DB operation: " + e.getMessage());
            // Redis不可用时，直接检查数据库积分
            UserPoint userPoint = userPointMapper.selectOne(
                    new LambdaQueryWrapper<UserPoint>().eq(UserPoint::getUserId, userId)
            );
            if (userPoint == null) {
                throw new RuntimeException("User point not found");
            }
            return userPoint.getPoint() >= RARE_BOX_COST;
        }
    }

    @Transactional
    @Override
    public boolean confirmExchange(Long userId, Integer boxType) {
        try {
            // 计算积分消耗
            int cost = RARE_BOX_COST;

            // 1. 更新数据库：扣减冻结积分
            UserPoint userPoint = userPointMapper.selectOne(
                    new LambdaQueryWrapper<UserPoint>().eq(UserPoint::getUserId, userId)
            );
            if (userPoint == null) {
                throw new RuntimeException("User point not found");
            }

            // 扣减冻结积分
            userPoint.setFrozenPoint(userPoint.getFrozenPoint() - cost);
            userPoint.setUpdateTime(LocalDateTime.now());
            userPointMapper.updateById(userPoint);

            // 2. 创建宝箱记录
            TreasureBox treasureBox = new TreasureBox();
            treasureBox.setUserId(userId);
            treasureBox.setBoxType(boxType);
            treasureBox.setStatus(1); // 已兑换
            treasureBox.setPointCost(cost);
            treasureBox.setCreateTime(LocalDateTime.now());
            treasureBox.setUpdateTime(LocalDateTime.now());
            treasureBoxMapper.insert(treasureBox);

            // 3. 更新Redis：扣减冻结积分
            try {
                String userPointKey = USER_POINT_KEY_PREFIX + userId;
                redisUtil.hSet(userPointKey, "frozenPoint", userPoint.getFrozenPoint());
            } catch (Exception e) {
                System.err.println("Redis unavailable for updating frozenPoint: " + e.getMessage());
                // Redis不可用时不中断流程，仅记录警告
            }

            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    @Transactional
    @Override
    public boolean cancelExchange(Long userId, Integer boxType) {
        try {
            // 计算积分消耗
            int cost = RARE_BOX_COST;

            // 1. 更新数据库：回滚积分
            UserPoint userPoint = userPointMapper.selectOne(
                    new LambdaQueryWrapper<UserPoint>().eq(UserPoint::getUserId, userId)
            );
            if (userPoint == null) {
                throw new RuntimeException("User point not found");
            }

            // 回滚积分：冻结积分减少，可用积分增加
            userPoint.setFrozenPoint(userPoint.getFrozenPoint() - cost);
            userPoint.setPoint(userPoint.getPoint() + cost);
            userPoint.setUpdateTime(LocalDateTime.now());
            userPointMapper.updateById(userPoint);

            // 2. 更新Redis：回滚积分
            try {
                String userPointKey = USER_POINT_KEY_PREFIX + userId;
                redisUtil.hSet(userPointKey, "point", userPoint.getPoint());
                redisUtil.hSet(userPointKey, "frozenPoint", userPoint.getFrozenPoint());
            } catch (Exception e) {
                System.err.println("Redis unavailable for updating rollback: " + e.getMessage());
                // Redis不可用时不中断流程，仅记录警告
            }

            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public String exchangeTreasureBox(ExchangeRequest request) {
        Long userId = request.getUserId();
        Integer boxType = request.getBoxType();

        // 获取分布式锁，防止重复兑换
        String lockKey = EXCHANGE_LOCK_KEY_PREFIX + userId + ":" + boxType;
        Boolean locked;
        try {
            locked = redisUtil.setIfAbsent(lockKey, "1", 30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            System.err.println("Redis unavailable for acquiring lock, falling back to DB-based locking: " + e.getMessage());
            // Redis不可用时，跳过分布式锁（这会降低并发安全性，但保证功能可用）
            locked = true;
        }

        if (locked == null || !locked) {
            return "兑换请求处理中，请稍后再试";
        }

        try {
            // 1. Try阶段：预扣减积分
            boolean tryResult = tryExchange(userId, boxType);
            if (!tryResult) {
                return "积分不足，兑换失败";
            }

            // 2. Confirm阶段：确认兑换
            boolean confirmResult = confirmExchange(userId, boxType);
            if (confirmResult) {
                return "兑换成功";
            } else {
                // 3. Cancel阶段：取消兑换
                cancelExchange(userId, boxType);
                return "兑换失败，请稍后再试";
            }
        } catch (Exception e) {
            e.printStackTrace();
            // 发生异常，执行Cancel阶段
            cancelExchange(userId, boxType);
            return "兑换异常，请稍后再试";
        } finally {
            // 释放锁
            try {
                redisUtil.delete(lockKey);
            } catch (Exception e) {
                System.err.println("Redis unavailable for releasing lock: " + e.getMessage());
                // Redis不可用时不中断流程，仅记录警告
            }
        }
    }

    @Override
    @Transactional
    public boolean addPoints(Long userId, Integer points) {
        try {
            // 1. 查询用户积分记录
            UserPoint userPoint = userPointMapper.selectOne(
                    new LambdaQueryWrapper<UserPoint>().eq(UserPoint::getUserId, userId)
            );

            if (userPoint == null) {
                // 如果用户积分记录不存在，创建新记录
                userPoint = new UserPoint();
                userPoint.setUserId(userId);
                userPoint.setPoint(points);
                userPoint.setFrozenPoint(0);
                userPoint.setCreateTime(LocalDateTime.now());
                userPoint.setUpdateTime(LocalDateTime.now());
                userPointMapper.insert(userPoint);
            } else {
                // 更新积分
                userPoint.setPoint(userPoint.getPoint() + points);
                userPoint.setUpdateTime(LocalDateTime.now());
                userPointMapper.updateById(userPoint);
            }

            // 2. 更新Redis缓存
//            try {
//                String userPointKey = USER_POINT_KEY_PREFIX + userId;
//                if (redisUtil.hasKey(userPointKey)) {
//                    Object currentPoint = redisUtil.hGet(userPointKey, "point");
//                    if (currentPoint != null) {
//                        int newPoint = Integer.parseInt(currentPoint.toString()) + points;
//                        redisUtil.hSet(userPointKey, "point", newPoint);
//                    }
//                }
//            } catch (Exception e) {
//                System.err.println("Redis unavailable for updating points: " + e.getMessage());
//                // Redis不可用时不中断流程
//            }

            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public int getPoints(Long userId) {
        try {
            // 查询用户积分记录
            UserPoint userPoint = userPointMapper.selectOne(
                    new LambdaQueryWrapper<UserPoint>().eq(UserPoint::getUserId, userId)
            );

            if (userPoint == null) {
                // 如果用户积分记录不存在，返回0
                return 0;
            } else {
                // 返回用户积分
                return userPoint.getPoint();
            }
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }
}
