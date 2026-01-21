package com.study.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
public class RedisUtil {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // ============================= String =============================
    public Boolean set(String key, Object value) {
        try {
            redisTemplate.opsForValue().set(key, value);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public Boolean set(String key, Object value, long time, TimeUnit unit) {
        try {
            redisTemplate.opsForValue().set(key, value, time, unit);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public Boolean setIfAbsent(String key, Object value, long time, TimeUnit unit) {
        try {
            return redisTemplate.opsForValue().setIfAbsent(key, value, time, unit);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public Object get(String key) {
        return key == null ? null : redisTemplate.opsForValue().get(key);
    }

    public Boolean delete(String key) {
        return redisTemplate.delete(key);
    }

    // ============================= Hash =============================
    public Boolean hSet(String key, String hashKey, Object value) {
        try {
            redisTemplate.opsForHash().put(key, hashKey, value);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public Object hGet(String key, String hashKey) {
        return redisTemplate.opsForHash().get(key, hashKey);
    }

    public Map<Object, Object> hGetAll(String key) {
        return redisTemplate.opsForHash().entries(key);
    }

    public Boolean hDelete(String key, Object... hashKeys) {
        try {
            redisTemplate.opsForHash().delete(key, hashKeys);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    // ============================= List =============================
    public Long lPush(String key, Object... values) {
        return redisTemplate.opsForList().leftPushAll(key, values);
    }

    public Long rPush(String key, Object... values) {
        return redisTemplate.opsForList().rightPushAll(key, values);
    }

    public Object lPop(String key) {
        return redisTemplate.opsForList().leftPop(key);
    }

    public Object rPop(String key) {
        return redisTemplate.opsForList().rightPop(key);
    }

    public Long lLen(String key) {
        return redisTemplate.opsForList().size(key);
    }

    // ============================= Set =============================
    public Long sAdd(String key, Object... values) {
        return redisTemplate.opsForSet().add(key, values);
    }

    public Long sCard(String key) {
        return redisTemplate.opsForSet().size(key);
    }

    public Boolean sIsMember(String key, Object value) {
        return redisTemplate.opsForSet().isMember(key, value);
    }

    public Set<Object> sMembers(String key) {
        return redisTemplate.opsForSet().members(key);
    }

    public Long sRemove(String key, Object... values) {
        return redisTemplate.opsForSet().remove(key, values);
    }

    // ============================= ZSet =============================
    public Boolean zAdd(String key, Object value, double score) {
        return redisTemplate.opsForZSet().add(key, value, score);
    }

    public Long zCard(String key) {
        return redisTemplate.opsForZSet().size(key);
    }

    // ============================= Lua =============================
    public <T> T executeLuaScript(String script, List<String> keys, Object... args) {
        DefaultRedisScript<T> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(script);
        redisScript.setResultType((Class<T>) List.class); // 修改返回类型为List
        return (T) redisTemplate.execute(redisScript, keys, args);
    }

    public <T> T executeLuaScriptWithResultType(String script, Class<T> resultType, List<String> keys, Object... args) {
        DefaultRedisScript<T> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(script);
        redisScript.setResultType(resultType);
        return (T) redisTemplate.execute(redisScript, keys, args);
    }
    
    public List<Long> executeLuaScriptForAnswerCheck(String script, List<String> keys, Object... args) {
        DefaultRedisScript<List<Long>> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(script);
        redisScript.setResultType((Class<List<Long>>) (Class<?>) List.class);
        return redisTemplate.execute(redisScript, keys, args);
    }

    // ============================= 通用 =============================
    public Boolean expire(String key, long time, TimeUnit unit) {
        return redisTemplate.expire(key, time, unit);
    }

    public Long getExpire(String key, TimeUnit unit) {
        return redisTemplate.getExpire(key, unit);
    }

    public Boolean hasKey(String key) {
        return redisTemplate.hasKey(key);
    }
}
