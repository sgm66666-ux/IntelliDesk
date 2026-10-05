package com.intellidesk.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/** Small metadata only; workspace authorization is always performed by the caller. */
@Slf4j
@Component
public class KnowledgeBaseMetadataCache {
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    public KnowledgeBaseMetadataCache(StringRedisTemplate redis, ObjectMapper json) {
        this.redis=redis; this.json=json;
    }
    String key(Long workspaceId, Long id) { return "intellidesk:kb:metadata:"+workspaceId+":"+id; }
    public KnowledgeBase get(Long workspaceId, Long id) {
        try {
            String value=redis.opsForValue().get(key(workspaceId,id));
            if (value==null) return null;
            if (value.length()>8192) { evict(workspaceId,id); return null; }
            KnowledgeBase kb=json.readValue(value,KnowledgeBase.class);
            if (!Objects.equals(kb.getWorkspaceId(),workspaceId) || !Objects.equals(kb.getId(),id)) {
                evict(workspaceId,id); return null;
            }
            return kb;
        } catch (Exception e) { log.debug("KB metadata cache read unavailable: {}",e.getClass().getSimpleName()); return null; }
    }
    public void put(KnowledgeBase kb) {
        // Never publish a potentially uncommitted database snapshot into Redis.
        if (TransactionSynchronizationManager.isActualTransactionActive()) return;
        try {
            String value=json.writeValueAsString(kb);
            if (value.length()<=8192) redis.opsForValue().set(key(kb.getWorkspaceId(),kb.getId()),value,
                    Duration.ofSeconds(300+ThreadLocalRandom.current().nextInt(61)));
        } catch (Exception e) { log.debug("KB metadata cache write unavailable: {}",e.getClass().getSimpleName()); }
    }
    public void invalidateAfterCommit(Long workspaceId,Long id) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { evict(workspaceId,id); }
            });
        } else evict(workspaceId,id);
    }
    private void evict(Long workspaceId,Long id) {
        try { redis.delete(key(workspaceId,id)); }
        catch (RuntimeException e) { log.warn("KB metadata cache eviction unavailable: {}",e.getClass().getSimpleName()); }
    }
}
