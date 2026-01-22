package com.study.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.study.dto.AnswerDTO;
import com.study.entity.Answer;
import com.study.mapper.AnswerMapper;
import com.study.service.AnswerService;
import com.study.service.AnswerStatistic;
import com.study.utils.RedisUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Service
@Primary  // 添加primary注解确保只有一个实现被注入
@Slf4j
public class AnswerServiceImpl implements AnswerService {

    @Autowired(required = false)  // 设置为非必需，避免Redis不可用时的依赖注入问题
    private RedisUtil redisUtil;

    @Autowired
    private AnswerMapper answerMapper;

    private static final String STUDENT_ANSWER_KEY_PREFIX = "student_answer:";

    // 延迟队列，用于存储延迟检测任务
    private final DelayQueue<DelayCheckTask> delayQueue = new DelayQueue<>();

    // 线程池，用于执行延迟检测任务（优化线程池配置以应对高并发）
    private final ExecutorService executorService = new ThreadPoolExecutor(
            50, // 核心线程数
            150, // 最大线程数
            60L, // 线程存活时间
            TimeUnit.SECONDS, // 时间单位
            new LinkedBlockingQueue<>(5000), // 工作队列
            new ThreadPoolExecutor.CallerRunsPolicy() // 拒绝策略
    );

    // 批量更新队列，用于收集待批量更新的答案
    private final BlockingQueue<Answer> batchUpdateQueue = new LinkedBlockingQueue<>(20000);

    // 批量更新阈值和间隔
    private static final int BATCH_UPDATE_THRESHOLD = 20;
    private static final long BATCH_UPDATE_INTERVAL = 5000; // 5秒

    // 初始化批量更新线程
    {
        executorService.submit(() -> {
            List<Answer> batchList = new ArrayList<>(BATCH_UPDATE_THRESHOLD);
            long lastUpdateTime = System.currentTimeMillis();

            log.info("批量更新线程已启动，阈值: {}, 间隔: {}ms", BATCH_UPDATE_THRESHOLD, BATCH_UPDATE_INTERVAL);

            while (true) {
                try {
                    // 尝试从队列中获取答案
                    Answer answer = batchUpdateQueue.poll(100, TimeUnit.MILLISECONDS);

                    if (answer != null) {
                        batchList.add(answer);
                        // log.info("添加到批量更新队列，planId: {}, questionId: {}, studentId: {}, 当前批量大小: {}",
                        //         answer.getPlanId(), answer.getQuestionId(), answer.getStudentId(), batchList.size());
                    }

                    // 检查是否达到批量更新条件
                    long currentTime = System.currentTimeMillis();
                    // log.info("检查批量更新条件 - 当前批量大小: {}, 时间差: {}ms, 队列大小: {}",
                    //         batchList.size(), currentTime - lastUpdateTime, batchUpdateQueue.size());

                    if (batchList.size() >= BATCH_UPDATE_THRESHOLD ||
                        (batchList.size() > 0 && currentTime - lastUpdateTime >= BATCH_UPDATE_INTERVAL)) {

                        if (!batchList.isEmpty()) {
                            log.info("执行批量更新，更新条数: {}", batchList.size());
                            // 执行批量更新
                            batchUpdateAnswers(batchList);
                            batchList.clear();
                            lastUpdateTime = currentTime;
                            log.info("批量更新完成");
                        }
                    }
                } catch (InterruptedException e) {
                    log.error("批量更新线程被中断", e);
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("批量更新线程执行失败", e);
                    // 重置状态，避免异常后批量更新停止
                    batchList.clear();
                    lastUpdateTime = System.currentTimeMillis();
                }
            }
        });
    }

    // 初始化延迟队列处理线程
    {
        executorService.submit(() -> {
            while (true) {
                try {
                    // 从延迟队列中获取到期的任务
                    DelayCheckTask task = delayQueue.take();
                    // 执行任务
                    task.execute();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    // 批量更新答案的方法
    private void batchUpdateAnswers(List<Answer> answerList) {
        if (answerList == null || answerList.isEmpty()) {
            return;
        }

        try {
            log.info("开始批量更新，总条数: {}", answerList.size());

            // 按planId+questionId+studentId分组，确保每个学生的每个问题只有一条最新记录
            Map<String, Answer> uniqueAnswers = new HashMap<>();

            for (Answer answer : answerList) {
                String key = answer.getPlanId() + ":" + answer.getQuestionId() + ":" + answer.getStudentId();
                // 只保留最新的记录
                Answer existing = uniqueAnswers.get(key);
                if (existing == null || answer.getUpdateTime().isAfter(existing.getUpdateTime())) {
                    uniqueAnswers.put(key, answer);
                }
            }

            // 执行批量更新/插入
            List<Answer> uniqueList = new ArrayList<>(uniqueAnswers.values());
            log.info("去重后条数: {}", uniqueList.size());

            for (Answer answer : uniqueList) {
                try {
                    // 使用Redis分布式锁确保同一时间只有一个线程能够处理同一个学生的同一个问题的答案
                    String lockKey = "lock:answer:" + answer.getPlanId() + ":" + answer.getQuestionId() + ":" + answer.getStudentId();
                    boolean locked = false;
                    try {
                        // 尝试获取锁，过期时间10秒
                        locked = redisUtil.setIfAbsent(lockKey, "1", 10, TimeUnit.SECONDS);
                        if (locked) {
                            // 检查数据库中是否已存在该学生的答题记录
                            LambdaQueryWrapper<Answer> wrapper = new LambdaQueryWrapper<Answer>()
                                    .eq(Answer::getPlanId, answer.getPlanId())
                                    .eq(Answer::getQuestionId, answer.getQuestionId())
                                    .eq(Answer::getStudentId, answer.getStudentId());

                            Answer existingAnswer = answerMapper.selectOne(wrapper);
                            if (existingAnswer != null) {
                                // 已存在，更新记录
                                existingAnswer.setAnswer(answer.getAnswer());
                                existingAnswer.setUpdateTime(answer.getUpdateTime());
                                int updateResult = answerMapper.updateById(existingAnswer);
                                log.debug("更新答案成功，planId: {}, questionId: {}, studentId: {}, 影响行数: {}",
                                        answer.getPlanId(), answer.getQuestionId(), answer.getStudentId(), updateResult);
                            } else {
                                // 不存在，创建新记录
                                int insertResult = answerMapper.insert(answer);
                                log.debug("插入答案成功，planId: {}, questionId: {}, studentId: {}, 影响行数: {}",
                                        answer.getPlanId(), answer.getQuestionId(), answer.getStudentId(), insertResult);
                            }
                        } else {
                            log.debug("获取锁失败，跳过处理，planId: {}, questionId: {}, studentId: {}",
                                    answer.getPlanId(), answer.getQuestionId(), answer.getStudentId());
                        }
                    } finally {
                        // 释放锁
                        if (locked) {
                            redisUtil.delete(lockKey);
                        }
                    }
                } catch (Exception e) {
                    log.error("单个答案更新失败，planId: {}, questionId: {}, studentId: {}",
                            answer.getPlanId(), answer.getQuestionId(), answer.getStudentId(), e);
                }
            }

            log.info("批量更新完成");
        } catch (Exception e) {
            log.error("批量更新答案失败", e);
        }
    }

    // 延迟检测任务类
    private class DelayCheckTask implements Delayed {
        private final long executeTime; // 执行时间
        private final Integer planId;
        private final Long questionId;
        private final Long studentId;
        private final String submittedAnswer;
        private final String studentAnswerKey;

        public DelayCheckTask(Integer planId, Long questionId, Long studentId, String submittedAnswer, String studentAnswerKey) {
            // 随机延迟1-10秒
            int randomDelay = (int) (Math.random() * 9000) + 10000; // 10000+毫秒
            this.executeTime = System.currentTimeMillis() + randomDelay;
            this.planId = planId;
            this.questionId = questionId;
            this.studentId = studentId;
            this.submittedAnswer = submittedAnswer;
            this.studentAnswerKey = studentAnswerKey;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            long remaining = executeTime - System.currentTimeMillis();
            return unit.convert(remaining, TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(this.executeTime, ((DelayCheckTask) other).executeTime);
        }

        public void execute() {
            try {
                // 检测Redis中的最新答案
                log.info("延迟检测任务执行，键: {}, 学生ID: {}", studentAnswerKey, studentId);
                String latestAnswer = redisUtil.hGetString(studentAnswerKey, studentId.toString());
                log.info("延迟检测任务执行，Redis答案: {}, 提交答案: {}, 是否相同: {}", latestAnswer, submittedAnswer, latestAnswer != null && latestAnswer.equals(submittedAnswer));

                if (latestAnswer != null) {
                    if (latestAnswer.equals(submittedAnswer)) {
                        // 任务快照与Redis答案一致，说明十几秒未修改，可能是最终记录
                        // 触发数据库入库
                        Answer answerEntity = new Answer();
                        answerEntity.setPlanId(planId);
                        answerEntity.setQuestionId(questionId);
                        answerEntity.setStudentId(studentId);
                        answerEntity.setAnswer(latestAnswer);
                        answerEntity.setCreateTime(LocalDateTime.now());
                        answerEntity.setUpdateTime(LocalDateTime.now());

                        // 将答案加入批量更新队列
                        boolean offerResult = batchUpdateQueue.offer(answerEntity);
                        log.info("任务快照与Redis答案一致，加入批量队列结果: {}, planId: {}, questionId: {}, studentId: {}",
                                offerResult, planId, questionId, studentId);
                    } else {
                        // 任务快照与Redis答案不一致，说明用户继续修改了答案
                        // 新增task，使用Redis的最新作答作为新task的快照
                        log.info("任务快照与Redis答案不一致，创建新任务，planId: {}, questionId: {}, studentId: {}",
                                planId, questionId, studentId);
                        
                        // 创建新的延迟检测任务
                        DelayCheckTask newTask = new DelayCheckTask(
                                planId,
                                questionId,
                                studentId,
                                latestAnswer, // 使用最新答案作为新任务的快照
                                studentAnswerKey
                        );
                        // 将新任务放入延迟队列，而不是直接执行
                        boolean offerResult = delayQueue.offer(newTask);
                        log.info("创建新延迟检测任务并加入队列，结果: {}, planId: {}, questionId: {}, studentId: {}, 当前队列大小: {}",
                                offerResult, planId, questionId, studentId, delayQueue.size());
                    }
                } else {
                    // Redis中没有数据，说明数据已经丢失
                    // 此时不应该重复入库，因为批量更新队列已经处理了这种情况
                    log.info("Redis无数据，延迟任务不处理: planId: {}, questionId: {}, studentId: {}",
                            planId, questionId, studentId);
                }
            } catch (Exception e) {
                log.error("延迟检测任务执行失败", e);
                // 异常时也将提交的数据加入队列
                try {
                    Answer answerEntity = new Answer();
                    answerEntity.setPlanId(planId);
                    answerEntity.setQuestionId(questionId);
                    answerEntity.setStudentId(studentId);
                    answerEntity.setAnswer(submittedAnswer);
                    answerEntity.setCreateTime(LocalDateTime.now());
                    answerEntity.setUpdateTime(LocalDateTime.now());

                    boolean offerResult = batchUpdateQueue.offer(answerEntity);
                    log.info("异常时加入队列结果: {}, planId: {}, questionId: {}, studentId: {}",
                            offerResult, planId, questionId, studentId);
                } catch (Exception ex) {
                    log.error("异常时加入队列失败", ex);
                }
            }
        }
    }

    @Override
    public void submitAnswer(AnswerDTO answerDTO) {
        long methodStartTime = System.currentTimeMillis();
        Integer planId = answerDTO.getPlanId();
        Long questionId = answerDTO.getQuestionId();
        Long studentId = answerDTO.getStudentId();
        String newAnswer = answerDTO.getAnswer().toUpperCase().trim();

        try {
            if (redisUtil == null) {
                // 如果Redis不可用，使用批量更新队列
                long redisNullStartTime = System.currentTimeMillis();
                Answer answerEntity = new Answer();
                BeanUtils.copyProperties(answerDTO, answerEntity);
                answerEntity.setAnswer(newAnswer);
                answerEntity.setCreateTime(LocalDateTime.now());
                answerEntity.setUpdateTime(LocalDateTime.now());
                boolean offerResult = batchUpdateQueue.offer(answerEntity);
                long redisNullEndTime = System.currentTimeMillis();
                log.info("Redis不可用时加入批量队列结果: {}, 耗时: {}ms, planId: {}, questionId: {}, studentId: {}",
                        offerResult, (redisNullEndTime - redisNullStartTime), planId, questionId, studentId);
                return;
            }

            // 判断并修改Redis里的作答记录（hash结构存储学生答题记录，key为student_answer:planId:questionId，field为studentId）
            String studentAnswerKey = STUDENT_ANSWER_KEY_PREFIX + planId + ":" + questionId;

            // 内嵌Lua脚本进行原子操作
            String luaScript = "local key = KEYS[1]\n" +
                              "local studentId = ARGV[1]\n" +
                              "local newAnswer = ARGV[4]\n" +
                              "\n" +
                              "if redis.call('hexists', key, studentId) == 1 then\n" +
                              "    local currentAnswer = redis.call('hget', key, studentId)\n" +
                              "    if currentAnswer == newAnswer then\n" +
                              "        return \"unchanged\"\n" +
                              "    end\n" +
                              "end\n" +
                              "redis.call('hset', key, studentId, newAnswer)\n" +
                              "redis.call('expire', key, 604800)\n" +
                              "return newAnswer\n";

            // 执行Lua脚本进行原子操作
            long luaStartTime = System.currentTimeMillis();
            String scriptResult = redisUtil.executeLuaScriptForStringResult(luaScript,
                Arrays.asList(studentAnswerKey),
                studentId.toString(), questionId.toString(), planId.toString(), newAnswer, String.valueOf(System.currentTimeMillis()));
            long luaEndTime = System.currentTimeMillis();
            log.info("执行Lua脚本完成，耗时: {}ms, 键: {}, 学生ID: {}, 答案: {}, 脚本返回结果: {}", 
                    (luaEndTime - luaStartTime), studentAnswerKey, studentId, newAnswer, scriptResult);

            // 根据脚本返回结果决定是否进行数据库操作
            if (scriptResult != null && !scriptResult.equals("unchanged")) {
                long delayTaskStartTime = System.currentTimeMillis();
                // 验证Redis中当前答案
                String redisCurrentAnswer = redisUtil.hGetString(studentAnswerKey, studentId.toString());
                log.info("脚本执行结果: {}, 答案: {}, Redis中当前答案: {}", scriptResult, newAnswer, redisCurrentAnswer);
                
                // 确保Redis中确实有值才触发延迟任务
                if (redisCurrentAnswer != null) {
                    // 触发延迟检测任务，携带脚本返回的答案作为快照信息
                    DelayCheckTask task = new DelayCheckTask(planId, questionId, studentId, scriptResult, studentAnswerKey);
                    boolean offerResult = delayQueue.offer(task);
                    long delayTaskEndTime = System.currentTimeMillis();
                    log.info("提交触发延迟检测任务完成，耗时: {}ms, 结果: {}, planId: {}, questionId: {}, studentId: {}, 当前队列大小: {}",
                            (delayTaskEndTime - delayTaskStartTime), offerResult, planId, questionId, studentId, delayQueue.size());
                } else {
                    // Redis中没有数据，说明数据已经丢失，直接降级到数据库操作
                    log.warn("Redis中无数据，直接降级到数据库操作，planId: {}, questionId: {}, studentId: {}",
                            planId, questionId, studentId);
                    Answer answerEntity = new Answer();
                    BeanUtils.copyProperties(answerDTO, answerEntity);
                    answerEntity.setAnswer(newAnswer);
                    answerEntity.setCreateTime(LocalDateTime.now());
                    answerEntity.setUpdateTime(LocalDateTime.now());
                    boolean offerResult = batchUpdateQueue.offer(answerEntity);
                    log.info("Redis无数据时加入批量队列结果: {}, planId: {}, questionId: {}, studentId: {}",
                            offerResult, planId, questionId, studentId);
                }
            } else if (scriptResult == null) {
                // 如果脚本执行失败，删除Redis记录并降级到数据库
                log.warn("Lua脚本执行失败，降级到数据库操作");
                long fallbackStartTime = System.currentTimeMillis();
                try {
                    redisUtil.delete(studentAnswerKey);
                } catch (Exception ex) {
                    log.error("删除Redis记录失败", ex);
                }
                // 降级到数据库操作
                Answer answerEntity = new Answer();
                BeanUtils.copyProperties(answerDTO, answerEntity);
                answerEntity.setAnswer(newAnswer);
                answerEntity.setCreateTime(LocalDateTime.now());
                answerEntity.setUpdateTime(LocalDateTime.now());
                boolean offerResult = batchUpdateQueue.offer(answerEntity);
                long fallbackEndTime = System.currentTimeMillis();
                log.info("脚本执行失败时加入批量队列结果: {}, 耗时: {}ms, planId: {}, questionId: {}, studentId: {}",
                        offerResult, (fallbackEndTime - fallbackStartTime), planId, questionId, studentId);
            } else if (scriptResult.equals("unchanged")) {
                long unchangedStartTime = System.currentTimeMillis();
                String redisCurrentAnswer = redisUtil.hGetString(studentAnswerKey, studentId.toString());
                long unchangedEndTime = System.currentTimeMillis();
                log.info("答案未变化，不进行数据库操作，当前Redis答案: {}, 耗时: {}ms, planId: {}, questionId: {}, studentId: {}",
                        redisCurrentAnswer, (unchangedEndTime - unchangedStartTime), planId, questionId, studentId);
                // 答案未变化时，不触发延迟任务，避免重复入库
            }
        } catch (Exception e) {
            log.error("Redis操作失败，降级到数据库存储: {}", e.getMessage());
            // Redis不可用时，直接加入批量更新队列
            try {
                long exceptionFallbackStartTime = System.currentTimeMillis();
                Answer answerEntity = new Answer();
                BeanUtils.copyProperties(answerDTO, answerEntity);
                answerEntity.setAnswer(newAnswer);
                answerEntity.setCreateTime(LocalDateTime.now());
                answerEntity.setUpdateTime(LocalDateTime.now());
                boolean offerResult = batchUpdateQueue.offer(answerEntity);
                long exceptionFallbackEndTime = System.currentTimeMillis();
                log.info("异常时加入批量队列结果: {}, 耗时: {}ms, planId: {}, questionId: {}, studentId: {}",
                        offerResult, (exceptionFallbackEndTime - exceptionFallbackStartTime), planId, questionId, studentId);
            } catch (Exception ex) {
                log.error("异常时加入批量队列失败", ex);
            }
        } finally {
            long methodEndTime = System.currentTimeMillis();
            log.info("submitAnswer方法执行完成，总耗时: {}ms, planId: {}, questionId: {}, studentId: {}",
                    (methodEndTime - methodStartTime), planId, questionId, studentId);
        }
    }

    @Async
    @Override
    public void saveAnswerToDbAsync(Answer answer) {
        // 检查是否已存在该学生的答题记录（需要考虑planId）
        LambdaQueryWrapper<Answer> wrapper = new LambdaQueryWrapper<Answer>()
                .eq(Answer::getQuestionId, answer.getQuestionId())
                .eq(Answer::getStudentId, answer.getStudentId())
                .eq(Answer::getPlanId, answer.getPlanId());
        Answer existingAnswer = answerMapper.selectOne(wrapper);
        if (existingAnswer != null) {
            // 已存在，更新记录
            existingAnswer.setAnswer(answer.getAnswer());
            existingAnswer.setUpdateTime(answer.getUpdateTime());
            answerMapper.updateById(existingAnswer);
        } else {
            // 不存在，创建新记录
            answerMapper.insert(answer);
        }
    }

    @Override
    public AnswerStatistic getAnswerStatistic(Long questionId, Integer planId) {
        // 如果Redis不可用，直接调用数据库版本
        if (redisUtil == null) {
            return getAnswerStatisticByDB(questionId, planId);
        }

        try {
            AnswerStatistic statistic = new AnswerStatistic();
            statistic.setQuestionId(questionId);
            statistic.setPlanId(planId);

            // Redis键名包含planId
            String answerKey = STUDENT_ANSWER_KEY_PREFIX + planId + ":" + questionId;

            // 获取所有学生的答题记录
            var answers = redisUtil.hGetAll(answerKey);
            int answeredCount = answers.size();

            // 统计各选项人数
            int aCount = 0, bCount = 0, cCount = 0, dCount = 0;
            for (Object value : answers.values()) {
                String answer = (String) value;
                switch (answer) {
                    case "A":
                        aCount++;
                        break;
                    case "B":
                        bCount++;
                        break;
                    case "C":
                        cCount++;
                        break;
                    case "D":
                        dCount++;
                        break;
                }
            }

            // 假设总学生数为50（实际应用中应从数据库或Redis获取）
            int totalStudents = 50;
            int notAnsweredCount = totalStudents - answeredCount;

            // 计算比例
            double total = totalStudents;
            statistic.setTotalStudents(totalStudents);
            statistic.setAnsweredCount(answeredCount);
            statistic.setNotAnsweredCount(notAnsweredCount);
            statistic.setACount(aCount);
            statistic.setBCount(bCount);
            statistic.setCCount(cCount);
            statistic.setDCount(dCount);
            statistic.setARatio(aCount / total);
            statistic.setBRatio(bCount / total);
            statistic.setCRatio(cCount / total);
            statistic.setDRatio(dCount / total);
            statistic.setNotAnsweredRatio(notAnsweredCount / total);

            return statistic;
        } catch (Exception e) {
            System.err.println("Redis unavailable for getAnswerStatistic, falling back to DB: " + e.getMessage());
            // Redis不可用时，从数据库获取统计数据
            return getAnswerStatisticByDB(questionId, planId);
        }
    }

    @Override
    public void submitAnswerByDB(AnswerDTO answerDTO) {
        Long questionId = answerDTO.getQuestionId();
        Long studentId = answerDTO.getStudentId();
        Integer planId = answerDTO.getPlanId();
        String answer = answerDTO.getAnswer().toUpperCase();

        // 检查数据库中是否已存在该学生的答题记录
        LambdaQueryWrapper<Answer> wrapper = new LambdaQueryWrapper<Answer>()
                .eq(Answer::getQuestionId, questionId)
                .eq(Answer::getStudentId, studentId)
                .eq(Answer::getPlanId, planId);
        Answer existingAnswer = answerMapper.selectOne(wrapper);

        // 构建答案JSON
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, String> answerMap = new HashMap<>();
        String finalAnswer;

        try {
            if (existingAnswer != null) {
                // 已有作答记录，解析现有答案并拼接新答案
                String existingAnswerStr = existingAnswer.getAnswer();
                Map<String, String> existingAnswerMap = objectMapper.readValue(existingAnswerStr, Map.class);

                // 如果已有first_ans，则保留为first_ans，新答案作为last_ans
                if (existingAnswerMap.containsKey("first_ans")) {
                    answerMap.put("first_ans", existingAnswerMap.get("first_ans"));
                    answerMap.put("last_ans", answer);
                } else {
                    // 如果现有答案不是预期的JSON格式，将现有答案作为first_ans，新答案作为last_ans
                    answerMap.put("first_ans", existingAnswerStr);
                    answerMap.put("last_ans", answer);
                }
            } else {
                // 没有作答记录，只保存first_ans
                answerMap.put("first_ans", answer);
            }

            // 转换为JSON字符串
            finalAnswer = objectMapper.writeValueAsString(answerMap);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to process answer JSON", e);
        }

        // 更新数据库
        Answer answerEntity;
        LocalDateTime now = LocalDateTime.now();

        if (existingAnswer != null) {
            // 更新现有记录
            answerEntity = existingAnswer;
            answerEntity.setAnswer(finalAnswer);
            answerEntity.setUpdateTime(now);
        } else {
            // 创建新记录
            answerEntity = new Answer();
            BeanUtils.copyProperties(answerDTO, answerEntity);
            answerEntity.setAnswer(finalAnswer);
            answerEntity.setIsFirst(true);
            answerEntity.setCreateTime(now);
            answerEntity.setUpdateTime(now);
        }

        // 保存到数据库
        if (existingAnswer != null) {
            // 已存在，更新记录
            existingAnswer.setAnswer(answerEntity.getAnswer());
            existingAnswer.setUpdateTime(answerEntity.getUpdateTime());
            answerMapper.updateById(existingAnswer);
        } else {
            // 不存在，创建新记录
            answerMapper.insert(answerEntity);
        }
    }


    @Override
    public void submitAnswerByDBMulti(AnswerDTO answerDTO) {
        // 每次都插入一个新记录
        Answer answerEntity = new Answer();
        BeanUtils.copyProperties(answerDTO, answerEntity);
        answerEntity.setIsFirst(true);
        answerEntity.setCreateTime(LocalDateTime.now());
        answerEntity.setUpdateTime(LocalDateTime.now());
        answerMapper.insert(answerEntity);
    }

    @Override
    public AnswerStatistic getAnswerStatisticByDB(Long questionId, Integer planId) {
        AnswerStatistic statistic = new AnswerStatistic();
        statistic.setQuestionId(questionId);
        statistic.setPlanId(planId);
        log.info("-------- qurey:"+ planId + " "+questionId);

        // 从数据库中获取该题目的所有答题记录（根据planId）
        List<Answer> answers = answerMapper.selectList(
                new LambdaQueryWrapper<Answer>()
                        .eq(Answer::getQuestionId, questionId)
                        .eq(Answer::getPlanId, planId)
        );

        // 按学生ID分组，只保留每个学生的最新作答记录
        Map<Long, Answer> latestAnswersByStudent = new HashMap<>();
        for (Answer answer : answers) {
            Long studentId = answer.getStudentId();
            Answer existingAnswer = latestAnswersByStudent.get(studentId);
            if (existingAnswer == null || answer.getCreateTime().isAfter(existingAnswer.getCreateTime())) {
                latestAnswersByStudent.put(studentId, answer);
            }
        }

        // 使用最新的作答记录进行统计
        List<Answer> latestAnswers = new ArrayList<>(latestAnswersByStudent.values());

        // 统计各选项数量
        int aCount = 0, bCount = 0, cCount = 0, dCount = 0;
        ObjectMapper objectMapper = new ObjectMapper();

        for (Answer answer : latestAnswers) {
            try {
                // 解析JSON格式的答案
                Map<String, String> answerMap = objectMapper.readValue(answer.getAnswer(), Map.class);

                // 获取最后一次的答案（如果有），否则获取第一次的答案
                String finalAnswer;
                if (answerMap.containsKey("last_ans")) {
                    finalAnswer = answerMap.get("last_ans");
                } else if (answerMap.containsKey("first_ans")) {
                    finalAnswer = answerMap.get("first_ans");
                } else {
                    // 如果都没有，使用整个答案作为选项
                    finalAnswer = answer.getAnswer();
                }

                // 根据答案更新统计
                switch (finalAnswer.toUpperCase()) {
                    case "A":
                        aCount++;
                        break;
                    case "B":
                        bCount++;
                        break;
                    case "C":
                        cCount++;
                        break;
                    case "D":
                        dCount++;
                        break;
                }
            } catch (JsonProcessingException e) {
                // 如果解析失败，尝试直接使用答案字符串
                String answerStr = answer.getAnswer().toUpperCase();
                switch (answerStr) {
                    case "A":
                        aCount++;
                        break;
                    case "B":
                        bCount++;
                        break;
                    case "C":
                        cCount++;
                        break;
                    case "D":
                        dCount++;
                        break;
                }
            }
        }

        // 这里假设总学生数为80（与压测脚本一致）
        int totalStudents = 50;
        int answeredCount = latestAnswers.size();
        int notAnsweredCount = totalStudents - answeredCount;

        // 计算比例
        double total = totalStudents;
        statistic.setTotalStudents(totalStudents);
        statistic.setAnsweredCount(answeredCount);
        statistic.setNotAnsweredCount(notAnsweredCount);
        statistic.setACount(aCount);
        statistic.setBCount(bCount);
        statistic.setCCount(cCount);
        statistic.setDCount(dCount);
        statistic.setARatio(aCount / total);
        statistic.setBRatio(bCount / total);
        statistic.setCRatio(cCount / total);
        statistic.setDRatio(dCount / total);
        statistic.setNotAnsweredRatio(notAnsweredCount / total);

        return statistic;
    }

}
