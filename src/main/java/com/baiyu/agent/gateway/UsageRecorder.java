package com.baiyu.agent.gateway;

import com.baiyu.agent.gateway.entity.LlmUsageRecord;
import com.baiyu.agent.gateway.repository.LlmUsageRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用量落库。
 *
 * <p>两条纪律：
 * <ol>
 *   <li><b>独立事务</b>（{@code REQUIRES_NEW}）：业务事务回滚了，这条"钱已经真花掉了"的记录也应该留下。</li>
 *   <li><b>吞异常</b>：计量失败绝不能让用户拿不到答案。宁可少一条记录，也不能让问答接口报错。</li>
 * </ol>
 */
@Service
public class UsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(UsageRecorder.class);

    private final LlmUsageRecordRepository repository;
    private final GatewayProperties properties;

    public UsageRecorder(LlmUsageRecordRepository repository, GatewayProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(LlmUsageRecord record) {
        if (!properties.isMeteringEnabled()) {
            return;
        }
        try {
            repository.save(record);
        } catch (Exception e) {
            log.error("写入用量记录失败（不影响本次回答）：model={}, scene={}",
                    record.getModelId(), record.getScene(), e);
        }
    }
}
