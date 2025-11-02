package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        // 缓存穿透
//        Shop shop = cacheClient.queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY
//        , id, Shop.class, this::getById,
//                RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 使用互斥锁解决缓存击穿
        Shop shop = cacheClient.queryWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY, id, Shop.class,
                this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 逻辑过期解决缓存击穿
//        Shop shop = queryWithLogicalExpire(id);
        if (shop == null) {
            return Result.fail("店铺不存在！");
        }

        // 7. 直接返回
        return Result.ok(shop);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        // 牢记，先操作数据库，再操作缓存！

        // 1. 更新数据库
        updateById(shop);

        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空！");
        }
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        // 2. 删除缓存
        stringRedisTemplate.delete(key);

        return Result.ok();
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);

        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }


    public Shop queryWithMutex (Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        // 1. 先去Redis中查一下商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3. 存在，就直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        // 判断命中的是否是空值
        // 我tm知道这个啥意思了，如果是空字符串，这个东西也就是isBlank成立，但是不是Null
        if (shopJson != null) {
            return null;
        }

        // 实现缓存重建
        String lockKey = RedisConstants.CACHE_SHOP_KEY + id;
        Shop shop = null;
        try {
            // 4.1. 获取互斥锁
            boolean isLock = tryLock(lockKey);

            // 如果失败，则休眠，然后重试
            if (!isLock) {
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            // 如果成功， 根据ID查询数据库
            shop = getById(id);

            // 模拟重建的延时
            Thread.sleep(200);

            // 5. 数据库中不存在信息，返回错误
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }

            // 6. 数据库中如果存在，就写入Redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 释放互斥锁
            unlock(lockKey);
        }
        // 7. 直接返回
        return shop;
    }

    public void saveShopToRedis (Long id, Long expireSeconds) {
        // 1. 查询店铺数据
        Shop shop = getById(id);

        // 2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 3. 写入Redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));

    }

}
