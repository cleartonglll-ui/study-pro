package com.study.service.impl;

import com.study.dto.RandomBoxRequest;
import com.study.entity.RandomTreasureBox;
import com.study.mapper.RandomTreasureBoxMapper;
import com.study.service.RandomTreasureBoxService;
import com.study.utils.RedisUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Random;
import java.util.concurrent.TimeUnit;

@Service
public class RandomTreasureBoxServiceImpl implements RandomTreasureBoxService {

    @Autowired
    private RedisUtil redisUtil;

    @Autowired
    private RandomTreasureBoxMapper randomTreasureBoxMapper;

    private static final String RANDOM_BOX_LIST_KEY_PREFIX = "random_box_list:";
    private static final String USER_GRAB_KEY_PREFIX = "user_grab:";
    private static final String RANDOM_BOX_ACTIVITY_KEY_PREFIX = "random_box_activity:";

    private final Random random = new Random();

    @Override
    public String generateRandomBoxes(RandomBoxRequest request) {
        try {
            Long activityId = request.getActivityId();
            Integer studentCount = request.getStudentCount();
            Integer minGold = request.getMinGold();
            Integer maxGold = request.getMaxGold();

            // 构建Redis键
            String boxListKey = RANDOM_BOX_LIST_KEY_PREFIX + activityId;
            String activityKey = RANDOM_BOX_ACTIVITY_KEY_PREFIX + activityId;

            // 清理之前的宝箱列表
            redisUtil.delete(boxListKey);

            // 生成随机金币数并放入Redis list
            for (int i = 0; i < studentCount; i++) {
                int gold = random.nextInt(maxGold - minGold + 1) + minGold;
                redisUtil.rPush(boxListKey, gold);
            }

            // 保存活动信息
            redisUtil.hSet(activityKey, "studentCount", studentCount);
            redisUtil.hSet(activityKey, "minGold", minGold);
            redisUtil.hSet(activityKey, "maxGold", maxGold);
            redisUtil.hSet(activityKey, "createTime", LocalDateTime.now().toString());
            redisUtil.expire(activityKey, 24, TimeUnit.HOURS);
            redisUtil.expire(boxListKey, 24, TimeUnit.HOURS);

            return "随机宝箱生成成功，共" + studentCount + "个宝箱";
        } catch (Exception e) {
            System.err.println("Redis unavailable for generateRandomBoxes, operation failed: " + e.getMessage());
            return "随机宝箱生成失败，Redis服务不可用";
        }
    }

    @Override
    public String grabRandomBox(Long activityId, Long userId) {
        try {
            // 构建Redis键
            String boxListKey = RANDOM_BOX_LIST_KEY_PREFIX + activityId;
            String userGrabKey = USER_GRAB_KEY_PREFIX + activityId + ":" + userId;

            // 检查是否已经抢过宝箱
            Boolean hasGrabbed = redisUtil.hasKey(userGrabKey);
            if (hasGrabbed != null && hasGrabbed) {
                return "您已经抢过宝箱了";
            }

            // 从Redis list中弹出一个随机金币数
            Integer goldAmount = (Integer) redisUtil.lPop(boxListKey);
            if (goldAmount == null) {
                return "宝箱已抢完";
            }

            // 标记用户已抢宝箱
            redisUtil.set(userGrabKey, "1", 24, TimeUnit.HOURS);

            // 异步保存到数据库
            saveGrabRecordAsync(activityId, userId, goldAmount);

            return "恭喜您抢到了" + goldAmount + "金币";
        } catch (Exception e) {
            System.err.println("Redis unavailable for grabRandomBox, operation failed: " + e.getMessage());
            return "抢宝箱失败，Redis服务不可用";
        }
    }

    @Async
    private void saveGrabRecordAsync(Long activityId, Long userId, Integer goldAmount) {
        RandomTreasureBox box = new RandomTreasureBox();
        box.setActivityId(activityId);
        box.setUserId(userId);
        box.setGoldAmount(goldAmount);
        box.setStatus(1); // 已领取
        box.setCreateTime(LocalDateTime.now());
        box.setReceiveTime(LocalDateTime.now());
        randomTreasureBoxMapper.insert(box);
    }
}
