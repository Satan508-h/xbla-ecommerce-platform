package com.xbla.rag.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.xbla.rag.entity.ProductSku;

/**
 * 商品 SKU Service
 *
 * <p>继承 {@code IService<ProductSku>} 后自带 save / saveBatch / getById / list /
 * updateById / removeById 等常用方法。
 *
 * <p><b>这里目前是空的，这是正常的。</b>Service 层的价值不在于包一层 CRUD
 * （那层 BaseMapper 已经提供了），而在于承载<b>业务逻辑</b>——
 * 比如「下单要同时扣库存、发券、写日志」这种跨表跨组件的编排。
 * 阶段 1 只是建表和灌数据，还没有业务逻辑可写，等对应功能开发时再往这里加方法。

 */
public interface ProductSkuService extends IService<ProductSku> {
}
