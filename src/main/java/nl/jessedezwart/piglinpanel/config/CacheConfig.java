package nl.jessedezwart.piglinpanel.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.cache.Caching;
import org.ehcache.config.CacheConfiguration;
import org.ehcache.config.builders.CacheConfigurationBuilder;
import org.ehcache.config.builders.ExpiryPolicyBuilder;
import org.ehcache.config.builders.ResourcePoolsBuilder;
import org.ehcache.config.units.EntryUnit;
import org.ehcache.config.units.MemoryUnit;
import org.ehcache.core.config.DefaultConfiguration;
import org.ehcache.impl.config.persistence.DefaultPersistenceConfiguration;
import org.ehcache.jsr107.EhcacheCachingProvider;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.jcache.JCacheCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCaching
public class CacheConfig {

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

    Map<String, CacheConfiguration<?, ?>> caches = new LinkedHashMap<>();
    caches.put("mod-hashes", hashConfig);
    caches.put("modrinth-projects", projectsConfig);

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

  private static Path resolveCacheDir() {
    String appData = System.getenv("APPDATA");
    if (appData != null && !appData.isBlank()) {
      return Path.of(appData, "Piglinpanel", "cache");
    }
    return Path.of(System.getProperty("user.home"), ".config", "piglinpanel", "cache");
  }
}
