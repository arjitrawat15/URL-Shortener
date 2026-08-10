package com.shortener.service;

import com.shortener.repository.UrlMappingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Background service for syncing Redis click counters to the database.
 * 
 * This service runs periodically to ensure click counts are persisted
 * to the database for durability and analytics.
 * 
 * Why This Job is Needed:
 * 
 * 1. Durability:
 *    - Redis is in-memory and can lose data on restart
 *    - Database provides persistent storage
 *    - Ensures click counts survive Redis failures
 * 
 * 2. Analytics:
 *    - Database queries are needed for historical analysis
 *    - Click counts are used in reporting and dashboards
 *    - Enables complex queries across time periods
 * 
 * 3. Consistency:
 *    - Syncs real-time Redis counters to persistent storage
 *    - Ensures data consistency between cache and database
 *    - Provides backup if Redis data is lost
 * 
 * 4. Performance:
 *    - Batch updates are more efficient than individual updates
 *    - Reduces database load during redirect operations
 *    - Allows async processing of click counts
 * 
 * Strategy:
 * 
 * 1. Read all click counters from Redis using SCAN (non-blocking)
 * 2. Batch update database in chunks (e.g., 100 at a time)
 * 3. After successful DB update, clear or adjust Redis counters
 * 4. Handle failures gracefully (log and continue)
 * 
 * Redis Counter Management:
 * 
 * Option 1: Clear after sync (current implementation)
 * - Reset Redis counter to 0 after syncing
 * - Pros: Simple, prevents double-counting
 * - Cons: Loses real-time count if sync fails
 * 
 * Option 2: Subtract after sync
 * - Subtract synced amount from Redis counter
 * - Pros: Preserves real-time count
 * - Cons: More complex, potential for drift
 * 
 * Option 3: Keep Redis counter, sync incrementally
 * - Store last synced value, sync only delta
 * - Pros: Most accurate
 * - Cons: Most complex, requires additional storage
 * 
 * Current implementation uses Option 1 (clear after sync) for simplicity.
 */
@Service
public class ClickCounterSyncService {

    private static final Logger logger = LoggerFactory.getLogger(ClickCounterSyncService.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final UrlMappingRepository urlMappingRepository;

    /**
     * Redis key prefix for click counters.
     * Format: "clicks:{shortCode}" -> count
     */
    private static final String REDIS_CLICKS_KEY_PREFIX = "clicks:";

    /**
     * Batch size for database updates.
     * Configurable via: url-shortener.sync.batch-size
     * Default: 100 records per batch
     */
    @Value("${url-shortener.sync.batch-size:100}")
    private int batchSize;

    /**
     * Sync interval in minutes.
     * Configurable via: url-shortener.sync.interval-minutes
     * Default: 5 minutes
     */
    @Value("${url-shortener.sync.interval-minutes:5}")
    private int syncIntervalMinutes;

    /**
     * Maximum number of counters to process per sync run.
     * Prevents long-running jobs from blocking.
     * Configurable via: url-shortener.sync.max-counters-per-run
     * Default: 10000
     */
    @Value("${url-shortener.sync.max-counters-per-run:10000}")
    private int maxCountersPerRun;

    public ClickCounterSyncService(
            RedisTemplate<String, String> redisTemplate,
            UrlMappingRepository urlMappingRepository) {
        this.redisTemplate = redisTemplate;
        this.urlMappingRepository = urlMappingRepository;
    }

    /**
     * Scheduled job that runs periodically to sync click counters.
     * 
     * Runs every N minutes (configurable via syncIntervalMinutes).
     * Uses fixed delay to ensure previous run completes before next starts.
     * 
     * Process:
     * 1. Scan Redis for all click counter keys
     * 2. Read counter values in batches
     * 3. Update database in batches
     * 4. Clear Redis counters after successful sync
     * 5. Log statistics
     */
    @Scheduled(fixedDelayString = "${url-shortener.sync.interval-minutes:5}", 
               initialDelay = 60000, // Start after 1 minute
               timeUnit = TimeUnit.MINUTES)
    public void syncClickCounters() {
        logger.info("Starting click counter sync job");

        long startTime = System.currentTimeMillis();
        int totalProcessed = 0;
        int totalSynced = 0;
        int totalErrors = 0;

        try {
            // Step 1: Scan Redis for all click counter keys
            Map<String, Long> clickCounters = scanClickCounters();
            totalProcessed = clickCounters.size();

            if (clickCounters.isEmpty()) {
                logger.debug("No click counters to sync");
                return;
            }

            logger.info("Found {} click counters to sync", totalProcessed);

            // Step 2: Process in batches
            List<Map.Entry<String, Long>> entries = new ArrayList<>(clickCounters.entrySet());
            int batchCount = 0;

            for (int i = 0; i < entries.size(); i += batchSize) {
                int endIndex = Math.min(i + batchSize, entries.size());
                List<Map.Entry<String, Long>> batch = entries.subList(i, endIndex);
                batchCount++;

                try {
                    // Step 3: Update database batch
                    int synced = syncBatch(batch);
                    totalSynced += synced;

                    // Step 4: Clear Redis counters after successful sync
                    clearRedisCounters(batch);

                    logger.debug("Synced batch {}/{}: {} counters", 
                            batchCount, (entries.size() + batchSize - 1) / batchSize, synced);

                } catch (Exception e) {
                    totalErrors++;
                    logger.error("Error syncing batch {}: {}", batchCount, e.getMessage(), e);
                    // Continue with next batch (graceful failure handling)
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            logger.info("Click counter sync completed: processed={}, synced={}, errors={}, duration={}ms",
                    totalProcessed, totalSynced, totalErrors, duration);

        } catch (Exception e) {
            logger.error("Fatal error in click counter sync job", e);
        }
    }

    /**
     * Scans Redis for all click counter keys.
     * 
     * Uses SCAN instead of KEYS to avoid blocking Redis.
     * SCAN is cursor-based and non-blocking.
     * 
     * @return Map of shortCode -> click count
     */
    private Map<String, Long> scanClickCounters() {
        Map<String, Long> clickCounters = new HashMap<>();
        int scanned = 0;

        try {
            ScanOptions scanOptions = ScanOptions.scanOptions()
                    .match(REDIS_CLICKS_KEY_PREFIX + "*")
                    .count(100) // Process 100 keys at a time
                    .build();

            try (Cursor<String> cursor = redisTemplate.scan(scanOptions)) {
                while (cursor.hasNext() && scanned < maxCountersPerRun) {
                    String key = cursor.next();
                    scanned++;

                    try {
                        // Extract short code from key (remove prefix)
                        String shortCode = key.substring(REDIS_CLICKS_KEY_PREFIX.length());

                        // Get counter value
                        String value = redisTemplate.opsForValue().get(key);
                        if (value != null) {
                            long count = Long.parseLong(value);
                            if (count > 0) {
                                clickCounters.put(shortCode, count);
                            }
                        }
                    } catch (Exception e) {
                        logger.warn("Error reading click counter for key {}: {}", key, e.getMessage());
                        // Continue with next key
                    }
                }
            }

            logger.debug("Scanned {} click counter keys from Redis", scanned);

        } catch (Exception e) {
            logger.error("Error scanning Redis for click counters: {}", e.getMessage(), e);
        }

        return clickCounters;
    }

    /**
     * Syncs a batch of click counters to the database.
     * 
     * Uses batch update for efficiency.
     * Updates url_mappings.click_count by adding the Redis counter value.
     * 
     * Strategy:
     * - Read current click_count from database
     * - Add Redis counter value to it
     * - Update database with new total
     * 
     * Alternative: Use SQL UPDATE with addition (more efficient)
     * UPDATE url_mappings SET click_count = click_count + ? WHERE short_code = ?
     * 
     * @param batch list of (shortCode, count) entries
     * @return number of successfully synced counters
     */
    @Transactional
    private int syncBatch(List<Map.Entry<String, Long>> batch) {
        int synced = 0;

        for (Map.Entry<String, Long> entry : batch) {
            String shortCode = entry.getKey();
            Long redisCount = entry.getValue();

            try {
                // Find the URL mapping
                var urlMappingOpt = urlMappingRepository.findByShortCode(shortCode);
                
                if (urlMappingOpt.isPresent()) {
                    var urlMapping = urlMappingOpt.get();
                    
                    // Add Redis counter to existing database count
                    long currentCount = urlMapping.getClickCount() != null 
                            ? urlMapping.getClickCount() 
                            : 0L;
                    long newCount = currentCount + redisCount;
                    
                    urlMapping.setClickCount(newCount);
                    urlMappingRepository.save(urlMapping);
                    
                    synced++;
                    logger.trace("Synced click counter for shortCode {}: {} (current) + {} (redis) = {} (total)", 
                            shortCode, currentCount, redisCount, newCount);
                } else {
                    logger.warn("Short code not found in database: {}", shortCode);
                }

            } catch (Exception e) {
                logger.error("Error syncing click counter for shortCode {}: {}", 
                        shortCode, e.getMessage(), e);
                // Continue with next entry
            }
        }

        return synced;
    }

    /**
     * Clears Redis counters after successful database sync.
     * 
     * Strategy: Clear counter (set to 0 or delete)
     * 
     * Alternative strategies:
     * - Subtract synced amount: DECRBY key count
     * - Keep counter, track last synced value separately
     * 
     * @param batch list of (shortCode, count) entries that were synced
     */
    private void clearRedisCounters(List<Map.Entry<String, Long>> batch) {
        for (Map.Entry<String, Long> entry : batch) {
            String shortCode = entry.getKey();
            String key = REDIS_CLICKS_KEY_PREFIX + shortCode;

            try {
                // Option 1: Delete the key (cleanest)
                redisTemplate.delete(key);

                // Option 2: Set to 0 (preserves key structure)
                // redisTemplate.opsForValue().set(key, "0");

                // Option 3: Subtract synced amount (preserves real-time count)
                // Long count = entry.getValue();
                // redisTemplate.opsForValue().decrement(key, count);

                logger.trace("Cleared Redis counter for shortCode: {}", shortCode);

            } catch (Exception e) {
                logger.warn("Error clearing Redis counter for key {}: {}", key, e.getMessage());
                // Continue with next entry
            }
        }
    }

    /**
     * Manual trigger for sync (useful for testing or admin operations).
     * 
     * @return sync statistics
     */
    public SyncStatistics manualSync() {
        logger.info("Manual sync triggered");
        syncClickCounters();
        return new SyncStatistics();
    }

    /**
     * Statistics about the sync operation.
     */
    public static class SyncStatistics {
        // Can be extended with detailed statistics if needed
    }
}

