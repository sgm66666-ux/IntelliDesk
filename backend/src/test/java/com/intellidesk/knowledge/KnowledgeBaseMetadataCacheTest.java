package com.intellidesk.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellidesk.common.*;
import com.intellidesk.document.DocumentMapper;
import com.intellidesk.workspace.WorkspaceAuthorizationService;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeBaseMetadataCacheTest {
    private final StringRedisTemplate redis=mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked") private final ValueOperations<String,String> values=mock(ValueOperations.class);
    private final KnowledgeBaseMetadataCache cache=new KnowledgeBaseMetadataCache(redis,new ObjectMapper().findAndRegisterModules());
    private KnowledgeBase kb() { var kb=new KnowledgeBase(); kb.setId(2L); kb.setWorkspaceId(1L); kb.setName("Synthetic metadata"); return kb; }
    @BeforeEach void setup() { when(redis.opsForValue()).thenReturn(values); }
    @AfterEach void clean() { if(TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization(); TransactionSynchronizationManager.setActualTransactionActive(false); }
    @Test void hitDeserializesBoundedMetadata() throws Exception {
        when(values.get(cache.key(1L,2L))).thenReturn(new ObjectMapper().writeValueAsString(kb()));
        assertThat(cache.get(1L,2L)).isEqualTo(kb());
    }
    @Test void missAndInfrastructureFailureFallBack() {
        assertThat(cache.get(1L,2L)).isNull();
        when(values.get(anyString())).thenThrow(new IllegalStateException("synthetic offline Redis"));
        assertThat(cache.get(1L,2L)).isNull();
    }
    @Test void writeUsesNamespaceAndBoundedJitteredTtl() {
        cache.put(kb());
        verify(values).set(eq("intellidesk:kb:metadata:1:2"),anyString(),argThat(ttl->ttl.getSeconds()>=300 && ttl.getSeconds()<=360));
    }
    @Test void invalidationOccursOnlyAfterCommit() {
        TransactionSynchronizationManager.initSynchronization(); TransactionSynchronizationManager.setActualTransactionActive(true);
        cache.invalidateAfterCommit(1L,2L); verify(redis,never()).delete(anyString());
        TransactionSynchronizationManager.getSynchronizations().forEach(s->s.afterCommit());
        verify(redis).delete("intellidesk:kb:metadata:1:2");
    }
    @Test void rollbackDoesNotEvictOrPopulateUncommittedMetadata() {
        TransactionSynchronizationManager.initSynchronization(); TransactionSynchronizationManager.setActualTransactionActive(true);
        cache.put(kb()); cache.invalidateAfterCommit(1L,2L);
        TransactionSynchronizationManager.getSynchronizations().forEach(s->s.afterCompletion(1));
        verify(values,never()).set(anyString(),anyString(),any(java.time.Duration.class));
        verify(redis,never()).delete(anyString());
    }
    @Test void crossWorkspacePoisonedEntryIsRejected() throws Exception {
        var other=kb(); other.setWorkspaceId(99L);
        when(values.get(cache.key(1L,2L))).thenReturn(new ObjectMapper().writeValueAsString(other));
        assertThat(cache.get(1L,2L)).isNull(); verify(redis).delete(cache.key(1L,2L));
    }
    @Test void authorizationIsCheckedEvenOnWarmCache() {
        var auth=mock(WorkspaceAuthorizationService.class); var meta=mock(KnowledgeBaseMetadataCache.class);
        var service=new KnowledgeBaseService(mock(KnowledgeBaseMapper.class),mock(DocumentMapper.class),auth,meta);
        doThrow(new BusinessException(ErrorCode.WORKSPACE_ACCESS_DENIED)).when(auth).requireMember(1L,7L);
        assertThatThrownBy(()->service.getKnowledgeBase(1L,2L,7L)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(meta);
    }
    @Test void serviceMissReadsDbThenCachesAndHitAvoidsDb() {
        var auth=mock(WorkspaceAuthorizationService.class); var meta=mock(KnowledgeBaseMetadataCache.class);
        var service=spy(new KnowledgeBaseService(mock(KnowledgeBaseMapper.class),mock(DocumentMapper.class),auth,meta));
        doReturn(kb()).when(service).getById(2L);
        assertThat(service.getKnowledgeBase(1L,2L,7L)).isEqualTo(kb()); verify(meta).put(kb());
        when(meta.get(1L,2L)).thenReturn(kb());
        assertThat(service.getKnowledgeBase(1L,2L,7L)).isEqualTo(kb());
        verify(service,times(1)).getById(2L); verify(auth,times(2)).requireMember(1L,7L);
    }
    @Test void metadataUpdateInvalidatesMatchingTenantKey() {
        var meta=mock(KnowledgeBaseMetadataCache.class);
        var service=spy(new KnowledgeBaseService(mock(KnowledgeBaseMapper.class),mock(DocumentMapper.class),mock(WorkspaceAuthorizationService.class),meta));
        doReturn(kb()).when(service).getById(2L); doReturn(true).when(service).updateById(any(KnowledgeBase.class));
        service.updateKnowledgeBase(1L,2L,7L,"Updated metadata",null,null,null,null);
        verify(meta).invalidateAfterCommit(1L,2L);
    }
    @Test void metadataDeletionInvalidatesMatchingTenantKey() {
        var meta=mock(KnowledgeBaseMetadataCache.class);
        var service=spy(new KnowledgeBaseService(mock(KnowledgeBaseMapper.class),mock(DocumentMapper.class),mock(WorkspaceAuthorizationService.class),meta));
        doReturn(kb()).when(service).getById(2L); doReturn(true).when(service).removeById(2L);
        service.deleteKnowledgeBase(1L,2L,7L);
        verify(meta).invalidateAfterCommit(1L,2L);
    }
}
