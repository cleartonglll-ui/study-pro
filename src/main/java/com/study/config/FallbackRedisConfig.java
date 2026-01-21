package com.study.config;

import com.study.utils.RedisUtil;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Redis回退配置 - 当Redis连接失败时提供空实现
 */
@Configuration
public class FallbackRedisConfig {

    /**
     * 当RedisUtil Bean无法创建时，提供一个空实现
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(RedisUtil.class)
    public RedisUtil fallbackRedisUtil() {
        return new RedisUtil() {
            @Override
            public Boolean set(String key, Object value) {
                // 记录警告日志，但不抛出异常
                System.out.println("WARN: Redis is unavailable, skipping set operation for key: " + key);
                return false;
            }

            @Override
            public Boolean set(String key, Object value, long time, java.util.concurrent.TimeUnit unit) {
                System.out.println("WARN: Redis is unavailable, skipping set operation for key: " + key);
                return false;
            }

            @Override
            public Boolean setIfAbsent(String key, Object value, long time, java.util.concurrent.TimeUnit unit) {
                System.out.println("WARN: Redis is unavailable, skipping setIfAbsent operation for key: " + key);
                return false;
            }

            @Override
            public Object get(String key) {
                System.out.println("WARN: Redis is unavailable, returning null for key: " + key);
                return null;
            }

            @Override
            public Boolean delete(String key) {
                System.out.println("WARN: Redis is unavailable, skipping delete operation for key: " + key);
                return false;
            }

            @Override
            public Boolean hSet(String key, String hashKey, Object value) {
                System.out.println("WARN: Redis is unavailable, skipping hSet operation for key: " + key);
                return false;
            }

            @Override
            public Object hGet(String key, String hashKey) {
                System.out.println("WARN: Redis is unavailable, returning null for hash key: " + hashKey + ", in hash: " + key);
                return null;
            }

            @Override
            public java.util.Map<Object, Object> hGetAll(String key) {
                System.out.println("WARN: Redis is unavailable, returning empty map for key: " + key);
                return new java.util.HashMap<>();
            }

            @Override
            public Boolean hDelete(String key, Object... hashKeys) {
                System.out.println("WARN: Redis is unavailable, skipping hDelete operation for key: " + key);
                return false;
            }

            @Override
            public Long lPush(String key, Object... values) {
                System.out.println("WARN: Redis is unavailable, skipping lPush operation for key: " + key);
                return 0L;
            }

            @Override
            public Long rPush(String key, Object... values) {
                System.out.println("WARN: Redis is unavailable, skipping rPush operation for key: " + key);
                return 0L;
            }

            @Override
            public Object lPop(String key) {
                System.out.println("WARN: Redis is unavailable, returning null for lPop operation for key: " + key);
                return null;
            }

            @Override
            public Object rPop(String key) {
                System.out.println("WARN: Redis is unavailable, returning null for rPop operation for key: " + key);
                return null;
            }

            @Override
            public Long lLen(String key) {
                System.out.println("WARN: Redis is unavailable, returning 0 for lLen operation for key: " + key);
                return 0L;
            }

            @Override
            public Long sAdd(String key, Object... values) {
                System.out.println("WARN: Redis is unavailable, skipping sAdd operation for key: " + key);
                return 0L;
            }

            @Override
            public Long sCard(String key) {
                System.out.println("WARN: Redis is unavailable, returning 0 for sCard operation for key: " + key);
                return 0L;
            }

            @Override
            public Boolean sIsMember(String key, Object value) {
                System.out.println("WARN: Redis is unavailable, returning false for sIsMember operation for key: " + key);
                return false;
            }

            @Override
            public java.util.Set<Object> sMembers(String key) {
                System.out.println("WARN: Redis is unavailable, returning empty set for sMembers operation for key: " + key);
                return new java.util.HashSet<>();
            }

            @Override
            public Long sRemove(String key, Object... values) {
                System.out.println("WARN: Redis is unavailable, returning 0 for sRemove operation for key: " + key);
                return 0L;
            }

            @Override
            public Boolean zAdd(String key, Object value, double score) {
                System.out.println("WARN: Redis is unavailable, skipping zAdd operation for key: " + key);
                return false;
            }

            @Override
            public Long zCard(String key) {
                System.out.println("WARN: Redis is unavailable, returning 0 for zCard operation for key: " + key);
                return 0L;
            }

            @Override
            public <T> T executeLuaScript(String script, java.util.List<String> keys, Object... args) {
                System.out.println("WARN: Redis is unavailable, skipping Lua script execution");
                return null;
            }

            @Override
            public <T> T executeLuaScriptWithResultType(String script, Class<T> resultType, java.util.List<String> keys, Object... args) {
                System.out.println("WARN: Redis is unavailable, skipping Lua script execution");
                return null;
            }

            @Override
            public java.util.List<Long> executeLuaScriptForAnswerCheck(String script, java.util.List<String> keys, Object... args) {
                System.out.println("WARN: Redis is unavailable, skipping answer check Lua script execution");
                return new java.util.ArrayList<>();
            }

            @Override
            public Boolean expire(String key, long time, java.util.concurrent.TimeUnit unit) {
                System.out.println("WARN: Redis is unavailable, skipping expire operation for key: " + key);
                return false;
            }

            @Override
            public Long getExpire(String key, java.util.concurrent.TimeUnit unit) {
                System.out.println("WARN: Redis is unavailable, returning -1 for getExpire operation for key: " + key);
                return -1L;
            }

            @Override
            public Boolean hasKey(String key) {
                System.out.println("WARN: Redis is unavailable, returning false for hasKey operation for key: " + key);
                return false;
            }
        };
    }
}