package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {

        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        // 写到Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 缓存穿透
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallback
     * @param time
     * @param unit
     * @return
     * @param <R>
     * @param <ID>
     */
    public <R, ID> R queryWithPassThrough (
            String keyPrefix, ID id, Class<R> type,
            Function<ID, R> dbFallback,
            Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1. 先去Redis中查一下缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断是否存在
        if (StrUtil.isNotBlank(json)) {
            // 3. 存在，就直接返回
            return JSONUtil.toBean(json, type);
        }

        // 判断命中的是否是空值
        // 我tm知道这个啥意思了，如果是空字符串，这个东西也就是isBlank成立，但是不是Null
        if (json != null) {
            return null;
        }

        // 4. 不存在的话，根据ID查询数据库
        // 但是这边我们不直接调用数据库，而是通过函数式编程传入的dbFallback来获取
        R r = dbFallback.apply(id);

        // 5. 数据库中不存在信息，返回错误
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        // 6. 数据库中如果存在，就写入Redis
        this.set(key, r, time, unit);

        // 7. 直接返回
        return r;
    }

    /**
     * 逻辑过期解决缓存击穿
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallback
     * @param time
     * @param unit
     * @return
     * @param <R>
     * @param <ID>
     */
    public <R, ID> R queryWithLogicalExpire (String keyPrefix, ID id, Class<R> type,
            Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1. 先去Redis中查一下商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断是否存在
        if (StrUtil.isBlank(json)) {
            // 3. 不存在，就直接返回
            return null;
        }

        // 4 命中，需要判断是否过期
        // 先把Json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);

        LocalDateTime expireTime = redisData.getExpireTime();

        if (expireTime.isAfter(LocalDateTime.now())) {
            //       5.1 未过期，直接返回店铺信息
            return r;
        }
        //        5.2 已过期，需要缓存重建！
        String localKey = RedisConstants.LOCK_SHOP_KEY + id;

        //       6. 对于缓存重建
        // 6.1 获取互斥锁
        // 6.2 判断是否成功获取到锁

        boolean isLock = tryLock(localKey);

        if (isLock) {
            // 获取成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                // 重建缓存
                try {
                    R r_new = dbFallback.apply(id);
                    this.setWithLogicalExpire(key, r_new, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 把锁释放掉
                    unlock(localKey);
                }
            });
        }
        // 这边获得锁之后，其实还要进行双重检查，检查Redis中那个东西有没有重建过了

        // 无论获取成功与否，返回店铺信息（这个商铺信息实际上是过期的）
        return r;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);

        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
