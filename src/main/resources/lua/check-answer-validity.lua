-- 检查答题有效性并更新Redis数据
-- KEYS[1] - 学生答题键 (student_answer:{questionId}:{studentId})
-- KEYS[2] - 答题记录键 (answer:{questionId})
-- KEYS[3] - 统计键 (answer_stat:{questionId})
-- ARGV[1] - 学生ID
-- ARGV[2] - 当前答案
-- ARGV[3] - 过期时间（小时）

-- 启用单命令复制模式，允许在非确定性命令后使用写入命令
redis.replicate_commands()

local studentAnswerKey = KEYS[1]
local answerKey = KEYS[2]
local statKey = KEYS[3]

local studentId = ARGV[1]
local currentAnswer = ARGV[2]
local expireHoursStr = ARGV[3]
local expireHours = tonumber(expireHoursStr)

-- 如果转换失败，默认设置为24小时
if expireHours == nil then
    expireHours = 24
end

-- 获取之前存储的答案
local previousAnswer = redis.call('GET', studentAnswerKey)

-- 检查是否是首次答题
local isFirst = 0
local isValid = 1

if previousAnswer == false then
    -- 首次答题
    isFirst = 1
    isValid = 1
else
    -- 不是首次答题，检查是否与上次答案相同
    if previousAnswer == currentAnswer then
        -- 答案相同，无效
        isValid = 0
    else
        -- 答案不同，有效
        isValid = 1
    end
end

-- 如果答题有效，更新Redis数据
if isValid == 1 then
    -- 确保expireHours有值
    if expireHours == nil then
        expireHours = 24  -- 默认24小时
    end
    
    -- 设置学生答题记录（带过期时间）
    redis.call('SETEX', studentAnswerKey, expireHours * 3600, currentAnswer)
    
    -- 更新答题记录哈希表
    redis.call('HSET', answerKey, studentId, currentAnswer)
    
    -- 更新最后更新时间
    redis.call('HSET', statKey, 'last_update', redis.call('TIME')[1])
    
    -- 更新选项统计
    local optionCountKey = 'option_count_' .. currentAnswer
    local currentOptionCount = tonumber(redis.call('HGET', statKey, optionCountKey)) or 0
    redis.call('HSET', statKey, optionCountKey, currentOptionCount + 1)
    
    -- 如果不是首次答题，需要减少之前的选项统计
    if isFirst == 0 then
        local prevOptionCountKey = 'option_count_' .. previousAnswer
        local prevOptionCount = tonumber(redis.call('HGET', statKey, prevOptionCountKey)) or 0
        if prevOptionCount > 0 then
            redis.call('HSET', statKey, prevOptionCountKey, prevOptionCount - 1)
        end
    end
    
    -- 更新答题人数统计
    local answeredCount = tonumber(redis.call('HGET', statKey, 'answered_count')) or 0
    if isFirst == 1 then
        -- 首次答题，增加答题人数
        redis.call('HSET', statKey, 'answered_count', answeredCount + 1)
    else
        -- 非首次答题，人数不变，但选项统计已更新
    end
end

-- 返回结果：isFirst, isValid
return {isFirst, isValid}