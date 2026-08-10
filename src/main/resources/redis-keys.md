# Redis Key Patterns

This document defines all Redis key patterns used in the URL shortener application.

## Key Naming Convention

All keys follow a consistent pattern: `{prefix}:{identifier}` or `{prefix}:{type}:{identifier}`

## Key Patterns

### 1. URL Mappings

**Pattern:** `url:{shortCode}`

**Type:** String

**Value:** Long URL

**TTL:** 7 days (configurable)

**Purpose:** Fast lookup of short code to long URL for redirects.

**Example:**
```
Key: url:4c92
Value: https://example.com/very/long/url/path
TTL: 604800 seconds (7 days)
```

---

### 2. Click Counters

**Pattern:** `clicks:{shortCode}`

**Type:** String (integer counter)

**Value:** Total click count (atomic increment)

**TTL:** None (persists until cleanup)

**Purpose:** Real-time click counting without database writes.

**Example:**
```
Key: clicks:4c92
Value: 1234
```

**Operations:**
- `INCR clicks:4c92` - Atomic increment
- `GET clicks:4c92` - Get current count

---

### 3. Rate Limiting

**Pattern:** `rate:ip:{ipAddress}` or `rate:user:{userId}`

**Type:** Sorted Set (ZSET)

**Value:** Set of timestamps (request timestamps)

**TTL:** Window duration (e.g., 60 seconds)

**Purpose:** Sliding window rate limiting per IP or user.

**Example:**
```
Key: rate:ip:192.168.1.1
Type: ZSET
Members: [1704067200, 1704067201, 1704067202, ...]
Score: Timestamp (Unix epoch seconds)
TTL: 60 seconds
```

**Operations:**
- `ZREMRANGEBYSCORE` - Remove old entries
- `ZCARD` - Count current entries
- `ZADD` - Add new request timestamp
- `EXPIRE` - Set TTL

---

### 4. Analytics Buffers (Future)

**Pattern:** `analytics:buffer:{shortCode}` or `analytics:queue`

**Type:** List or Stream

**Value:** Analytics events (JSON)

**TTL:** Until consumed

**Purpose:** Buffer analytics events before batch processing.

**Note:** Currently, analytics are published directly to message queue.
This pattern can be used if buffering is needed.

---

## Key Expiration Strategy

### Automatic Expiration

- **URL mappings:** 7 days TTL (active URLs)
- **Rate limit keys:** Window duration TTL (e.g., 60 seconds)
- **Click counters:** No TTL (manual cleanup)

### Manual Cleanup

- **Click counters:** Background job syncs to DB and cleans up old counters
- **Expired URLs:** Background job removes expired URL mappings from cache

---

## Memory Management

### Estimated Memory Usage

- **URL mapping:** ~100 bytes per key (key + value + overhead)
- **Click counter:** ~50 bytes per key
- **Rate limit key:** ~200 bytes per key (sorted set overhead)

### Optimization

- Use Redis compression if available
- Set appropriate TTLs to prevent unbounded growth
- Monitor memory usage and implement cleanup jobs
- Consider Redis eviction policies (LRU, LFU)

---

## Key Access Patterns

### High Frequency (Read-Heavy)

- `url:{shortCode}` - Millions of reads per day
- `clicks:{shortCode}` - Millions of increments per day

### Medium Frequency

- `rate:ip:{ip}` - Thousands of checks per minute

### Low Frequency (Write-Heavy)

- Analytics buffers (if implemented)

---

## Distributed Considerations

All keys are designed to work in a distributed Redis cluster:

- **No local state:** All operations use Redis
- **Atomic operations:** Lua scripts ensure consistency
- **Shared state:** All application instances share the same Redis

---

## Security Considerations

### Key Exposure

- Keys contain user data (IP addresses, short codes)
- Ensure Redis is properly secured (authentication, encryption)
- Consider key encryption for sensitive data

### Rate Limiting Bypass

- Rate limit keys can be manipulated if Redis is compromised
- Use Redis ACLs to restrict write access
- Monitor for unusual patterns

---

## Monitoring

### Key Metrics

- **Cache hit rate:** `url:{shortCode}` hits vs misses
- **Rate limit rejections:** Count of rate limit violations
- **Memory usage:** Total Redis memory consumption
- **Key count:** Number of keys per pattern

### Alerts

- High memory usage (> 80% of Redis capacity)
- Low cache hit rate (< 90%)
- Rate limit key growth (potential attack)




