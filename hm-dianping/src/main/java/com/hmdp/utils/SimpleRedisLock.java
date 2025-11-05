package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {

    private String name;
    private StringRedisTemplate stringRedisTemplate;

    private static final String key_prefix = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new org.springframework.core.io.ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    @Override
    public boolean tryLock(long timeoutSec) {

        // 获取线程标识
//        long threadId = Thread.currentThread().getId();
        String threadId = ID_PREFIX + Thread.currentThread().getId();

        // 获取锁
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(key_prefix + name, threadId, timeoutSec, TimeUnit.SECONDS);

        // 这边要防止自动拆箱出现的空指针异常
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        // 调用lua脚本
        stringRedisTemplate
                .execute(UNLOCK_SCRIPT,
                        Collections.singletonList(key_prefix + name),
                        ID_PREFIX + Thread.currentThread().getId());
    }

    /*
    @Override
    public void unlock() {
        // 先获取线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();

        // 再获取锁中的标识
        String id = stringRedisTemplate.opsForValue().get(key_prefix + name);

        // 判断标识是否一致
        if (threadId.equals(id)) {
            // 释放锁
            stringRedisTemplate.delete(key_prefix + name);
        }

    }
     */
}
