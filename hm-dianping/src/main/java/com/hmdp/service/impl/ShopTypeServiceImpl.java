package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryList() {
        String key = RedisConstants.CACHE_TYPE_LIST;

        // 从Redis中查询类型缓存
        String typeJson = stringRedisTemplate.opsForValue().get(key);

        // 如果缓存不为空，就直接返回
        if (StrUtil.isNotBlank(typeJson)) {
            List<ShopType> shopTypeList = JSONUtil.toList(typeJson, ShopType.class);
            return Result.ok(shopTypeList);
        }

        // 缓存是空的，去查询数据库！
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();
        // 把数据库的信息保存到Redis缓存中去
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shopTypeList));

        return Result.ok(shopTypeList);
    }
}
