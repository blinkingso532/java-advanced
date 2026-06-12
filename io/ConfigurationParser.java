package io;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * 文件解析器支持多种格式的配置文件解析
 * 支持JSON、XML、YAML等常见格式的配置文件解析
 */
public class ConfigurationParser {

    /**
     * 缓存池
     */
    public static final class CachePool {
        private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
        // LRU 缓存 (顺序访问)
        private final LinkedHashMap<String, CacheEntry> lruCache;
        // 清理过期缓存的线程池
        private final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1,
                new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r);
                        t.setDaemon(true); // 设置为守护线程
                        t.setName("CacheCleanerThread");
                        return t;
                    }
                });

        // 默认过期时间（秒）300秒
        private static final long DEFAULT_EXPIRE = 300;
        // 默认缓存大小1000条
        private static final int MAX_CACHE_SIZE = 1000;

        public CachePool() {
            this.lruCache = new LinkedHashMap<>(16, 0.75f, true) {
                // 当缓存大小超过最大缓存大小时，移除最老的条目
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, CacheEntry> eldest) {
                    return size() > MAX_CACHE_SIZE;
                }
            };
            // 启动清理过期缓存的线程
            executorService.scheduleWithFixedDelay(() -> {
                System.out.println("清理过期缓存");
                synchronized (this) {
                    // 确保在缓存池上加锁，避免并发修改
                    cleanExpired();
                }
            }, DEFAULT_EXPIRE, DEFAULT_EXPIRE,
                    TimeUnit.SECONDS);
        }

        public void put(String key, Object value) {
            put(key, value, DEFAULT_EXPIRE);
        }

        public void put(String key, Object value, long expireSeconds) {
            CacheEntry entry = new CacheEntry(value, expireSeconds);
            this.cache.put(key, entry);
            synchronized (this.lruCache) {
                this.lruCache.put(key, entry);
            }
        }

        @SuppressWarnings("unchecked")
        public <T> T get(String key) {
            CacheEntry entry = this.cache.get(key);
            if (entry == null) {
                return null;
            }
            if (entry.isExpired()) {
                this.cache.remove(key);
                return null;
            }

            entry.hitCount++;
            return (T) entry.value;
        }

        public void cleanExpired() {
            this.cache.entrySet().removeIf(entry -> entry.getValue().isExpired());
        }

        /**
         * 缓存条目
         */
        static final class CacheEntry {
            Object value;
            LocalDateTime expireTime;
            long hitCount;

            CacheEntry(Object value, long expireSeconds) {
                this.value = value;
                if (expireSeconds <= 0) {
                    // never expire.
                    expireTime = LocalDateTime.MAX;
                } else {
                    expireTime = LocalDateTime.now().plusSeconds(expireSeconds);
                }
                this.hitCount = 0;
            }

            /**
             * 判断缓存是否过期
             * 
             * @return true 过期 false 未过期
             */
            boolean isExpired() {
                return LocalDateTime.now().isAfter(expireTime);
            }
        }
    }
}
