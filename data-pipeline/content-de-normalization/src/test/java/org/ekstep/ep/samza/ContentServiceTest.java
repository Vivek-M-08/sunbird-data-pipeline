package org.ekstep.ep.samza;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.apache.samza.storage.kv.KeyValueStore;
import org.ekstep.ep.samza.cache.CacheEntry;
import org.ekstep.ep.samza.cache.ContentService;
import org.ekstep.ep.samza.domain.Content;
import org.ekstep.ep.samza.external.SearchServiceClient;
import org.ekstep.ep.samza.fixture.ContentFixture;
import org.ekstep.ep.samza.service.CacheService;
import org.ekstep.ep.samza.task.ContentDeNormalizationMetrics;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.Date;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;
import static org.mockito.Mockito.*;

public class ContentServiceTest {

    private SearchServiceClient searchServiceMock;
    private long cacheTTL = 6000;
    private KeyValueStore contentStoreMock;
    private CacheService cacheService;
    private ContentDeNormalizationMetrics metricsMock;

    @Before
    public void setUp() {
        searchServiceMock = mock(SearchServiceClient.class);
        contentStoreMock = mock(KeyValueStore.class);
        metricsMock = mock(ContentDeNormalizationMetrics.class);
        cacheService = new CacheService(contentStoreMock, new TypeToken<CacheEntry<Content>>() {
        }.getType(), metricsMock);
    }

    @Test
    public void shouldProcessEventsFromCacheIfPresent() throws IOException {
        stub(contentStoreMock.get(getContentID())).toReturn(getContentCacheJson());

        ContentService contentService = new ContentService(searchServiceMock, cacheService, cacheTTL);
        Content content = contentService.getContent("someid", getContentID());

        assertEquals(content.name(), ContentFixture.getContentMap().get("name"));
        assertEquals(content.description(), ContentFixture.getContentMap().get("description"));
        assertEquals(content.ageGroup(), ContentFixture.getContentMap().get("ageGroup"));
        assertEquals(content.mediaType(), ContentFixture.getContentMap().get("mediaType"));
        assertEquals(content.contentType(), ContentFixture.getContentMap().get("contentType"));
        assertEquals(content.language(), ContentFixture.getContentMap().get("language"));
        assertEquals(content.owner(), ContentFixture.getContentMap().get("owner"));
        assertEquals(content.lastUpdatedOn(), ContentFixture.getContentMap().get("lastUpdatedOn"));
        verify(metricsMock).incCacheHitCounter();
        verifyNoMoreInteractions(metricsMock);
    }

    @Test
    public void shouldCallSearchServiceIfContentIsNotPresentInCache() throws IOException {
        Content content = ContentFixture.getContent();
        when(contentStoreMock.get(getContentID())).thenReturn(null, getContentCacheJson());
        stub(searchServiceMock.search(getContentID())).toReturn(content);

        ContentService contentService = new ContentService(searchServiceMock, cacheService, cacheTTL);
        contentService.getContent("someid", getContentID());

        verify(contentStoreMock, times(2)).get(getContentID());
        verify(contentStoreMock, times(1)).put(anyString(), anyString());
        verify(searchServiceMock, times(1)).search(getContentID());
        verify(metricsMock).incCacheMissCounter();
        verify(metricsMock).incCacheHitCounter();
        verifyNoMoreInteractions(metricsMock);
    }

    @Test
    public void shouldCallSearchServiceAndUpdateCacheIfCacheIsExpired() throws IOException {

        CacheEntry expiredContent = new CacheEntry(ContentFixture.getContent(), new Date().getTime() - 10000);
        CacheEntry validContent = new CacheEntry(ContentFixture.getContent(), new Date().getTime() + 10000);

        when(contentStoreMock.get(getContentID()))
                .thenReturn(
                        new Gson().toJson(expiredContent, CacheEntry.class),
                        new Gson().toJson(validContent, CacheEntry.class));
        stub(searchServiceMock.search(getContentID())).toReturn(ContentFixture.getContent());

        ContentService contentService = new ContentService(searchServiceMock, cacheService, cacheTTL);
        Content content = contentService.getContent("someid", getContentID());

        verify(contentStoreMock, times(2)).get(getContentID());
        verify(contentStoreMock, times(1)).put(anyString(), anyString());
        verify(searchServiceMock, times(1)).search(getContentID());

        assertEquals(content.name(), ContentFixture.getContentMap().get("name"));
        assertEquals(content.description(), ContentFixture.getContentMap().get("description"));
        assertFalse(content.getCacheHit());
        verify(metricsMock).incCacheHitCounter();
        verify(metricsMock).incCacheExpiredCounter();
        verifyNoMoreInteractions(metricsMock);
    }

    @Test
    public void shouldNotCallSearchServiceIfCacheIsNotExpired() throws IOException {

        CacheEntry contentCache = new CacheEntry(ContentFixture.getContent(), new Date().getTime() + 10000);
        String contentCacheJson = new Gson().toJson(contentCache, CacheEntry.class);

        stub(contentStoreMock.get(getContentID())).toReturn(contentCacheJson);
        stub(searchServiceMock.search(getContentID())).toReturn(ContentFixture.getContent());
        ContentService contentService = new ContentService(searchServiceMock, cacheService, cacheTTL);
        Content content = contentService.getContent("someid", getContentID());

        verify(contentStoreMock, times(0)).put(anyString(), anyString());
        verify(searchServiceMock, times(0)).search(getContentID());

        assertEquals(content.name(), ContentFixture.getContentMap().get("name"));
        assertEquals(content.description(), ContentFixture.getContentMap().get("description"));
        assertTrue(content.getCacheHit());
        verify(metricsMock).incCacheHitCounter();
        verifyNoMoreInteractions(metricsMock);
    }

    private String getContentID() {
        return ContentFixture.getContentID();
    }

    private String getContentCacheJson() {
        Content content = ContentFixture.getContent();
        CacheEntry contentCache = new CacheEntry(content, new Date().getTime());
        return new Gson().toJson(contentCache, CacheEntry.class);
    }
}