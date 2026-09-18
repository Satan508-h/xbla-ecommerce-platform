package com.xbla.rag.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.xbla.rag.entity.QaLog;
import com.xbla.rag.mapper.QaLogMapper;
import com.xbla.rag.service.QaLogService;
import org.springframework.stereotype.Service;

/**
 * {@link com.xbla.rag.service.QaLogService} 的实现。
 *
 * <p>{@code ServiceImpl<QaLogMapper, QaLog>} 把 Mapper 注入进来
 * 并实现了 IService 的全部默认方法，所以这个类现在是空的但已经可用。
 *
 * <p>{@code @Service} 让 Spring 扫描到它并注册成 Bean。
 */
@Service
public class QaLogServiceImpl
        extends ServiceImpl<QaLogMapper, QaLog>
        implements QaLogService {
}
