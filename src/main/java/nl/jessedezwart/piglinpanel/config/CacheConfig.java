package nl.jessedezwart.piglinpanel.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.cache.Caching;
import nl.jessedezwart.piglinpanel.model.ModUpdate;
import org.ehcache.config.CacheConfiguration;
import org.ehcache.config.builders.CacheConfigurationBuilder;
import org.ehcache.config.builders.ExpiryPolicyBuilder;
import org.ehcache.config.builders.ResourcePoolsBuilder;
import org.ehcache.config.units.EntryUnit;
import org.ehcache.config.units.MemoryUnit;
import org.ehcache.core.config.DefaultConfiguration;
import org.ehcache.impl.config.persistence.DefaultPersistenceConfiguration;
import org.ehcache.jsr107.EhcacheCachingProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.jcache.JCacheCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCaching
public class CacheConfig {

  @Value("${piglinpanel.cache.dir:}")
  private String configuredCacheDir;

  @SuppressWarnings("rawtypes")
  @Bean(destroyMethod = "close")
  javax.cache.CacheManager ehcacheJcacheManager() {
    EhcacheCachingProvider provider =
        (EhcacheCachingProvider)
            Caching.getCachingProvider("org.ehcache.jsr107.EhcacheCachingProvider");

    CacheConfiguration<String, String> hashConfig =
        CacheConfigurationBuilder.newCacheConfigurationBuilder(
                String.class,
                String.class,
                ResourcePoolsBuilder.newResourcePoolsBuilder()
                    .heap(500, EntryUnit.ENTRIES)
                    .disk(10, MemoryUnit.MB, true))
            .build();

    CacheConfiguration<String, Map> projectsConfig =
        CacheConfigurationBuilder.newCacheConfigurationBuilder(
                String.class,
                Map.class,
                ResourcePoolsBuilder.newResourcePoolsBuilder()
                    .heap(50, EntryUnit.ENTRIES)
                    .disk(5, MemoryUnit.MB, true))
            .withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(Duration.ofHours(24)))
            .build();

    CacheConfiguration<String, ModUpdate> updatesConfig =
        CacheConfigurationBuilder.newCacheConfigurationBuilder(
                String.class,
                ModUpdate.class,
                ResourcePoolsBuilder.newResourcePoolsBuilder()
                    .heap(500, EntryUnit.ENTRIES)
                    .disk(10, MemoryUnit.MB, true))
            .withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(Duration.ofHours(6)))
            .build();

    CacheConfiguration<String, List> fileListingsConfig =
        CacheConfigurationBuilder.newCacheConfigurationBuilder(
                String.class,
                List.class,
                ResourcePoolsBuilder.newResourcePoolsBuilder().heap(500, EntryUnit.ENTRIES))
            .withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(Duration.ofSeconds(30)))
            .build();

    Map<String, CacheConfiguration<?, ?>> caches = new LinkedHashMap<>();
    caches.put("mod-hashes", hashConfig);
    caches.put("modrinth-projects", projectsConfig);
    caches.put("mod-updates", updatesConfig);
    caches.put("file-listings", fileListingsConfig);

    DefaultConfiguration config =
        new DefaultConfiguration(
            caches,
            provider.getDefaultClassLoader(),
            new DefaultPersistenceConfiguration(resolveCacheDir().toFile()));

    return provider.getCacheManager(provider.getDefaultURI(), config);
  }

  @Bean
  JCacheCacheManager cacheManager(javax.cache.CacheManager ehcacheJcacheManager) {
    return new JCacheCacheManager(ehcacheJcacheManager);
  }

  private Path resolveCacheDir() {
    if (configuredCacheDir != null && !configuredCacheDir.isBlank()) {
      return Path.of(configuredCacheDir);
    }
    return PiglinpanelPaths.cacheDir();
  }
}
