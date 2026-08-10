# URL Shortener - Production-Grade Spring Boot Application

A scalable, production-ready URL shortener service built with Spring Boot, Redis, and PostgreSQL. This service converts long URLs into short, shareable links with analytics tracking and rate limiting.

## Table of Contents

- [Problem Statement](#problem-statement)
- [High-Level Architecture](#high-level-architecture)
- [Tech Stack](#tech-stack)
- [Features](#features)
- [API Documentation](#api-documentation)
- [Redis Key Design](#redis-key-design)
- [Rate Limiting Strategy](#rate-limiting-strategy)
- [Failure Handling](#failure-handling)
- [Scalability](#scalability)
- [Getting Started](#getting-started)
- [Configuration](#configuration)
- [Monitoring & Observability](#monitoring--observability)
- [Production Deployment](#production-deployment)

## Problem Statement

URL shorteners solve several key problems:

1. **Length Constraints**: Social media platforms (Twitter, SMS) have character limits
2. **User Experience**: Long URLs are hard to read, remember, and share
3. **Analytics**: Track click-through rates, geographic distribution, and user behavior
4. **Branding**: Create branded short links (e.g., `short.ly/abc123`)
5. **Link Management**: Organize and manage multiple URLs with tags and expiration

This implementation provides a production-grade solution that can handle millions of requests per day with sub-millisecond redirect latency.

## High-Level Architecture

```
┌─────────┐
│ Client  │ (Web/Mobile/API consumers)
└────┬────┘
     │ HTTP/REST
     ▼
┌─────────────────────────────────────┐
│         API Layer                    │
│  (Spring Boot REST Controllers)     │
│  - Request validation               │
│  - Rate limiting                    │
│  - Error handling                   │
└────┬────────────────────────────────┘
     │
     ▼
┌─────────────────────────────────────┐
│      Service Layer                  │
│  - URL Shortening Service           │
│  - URL Resolution Service           │
│  - Analytics Service                │
└────┬────────────────────────────────┘
     │
     ├──────────────────┬──────────────────┐
     ▼                  ▼                  ▼
┌──────────┐      ┌──────────┐      ┌──────────┐
│  Redis   │      │Database  │      │ Analytics│
│  Cache   │      │(PostgreSQL│      │  Queue   │
│          │      │ /MySQL)   │      │(Kafka/   │
│  - Hot   │      │           │      │ RabbitMQ)│
│  URLs    │      │ - URL     │      │          │
│  - Rate  │      │   mappings│      │ - Click  │
│  limiting│      │ - Analytics│     │   events │
│  - Click │      │   (cold)  │      │          │
│  counters│      │           │      │          │
└──────────┘      └──────────┘      └──────────┘
```

### Component Responsibilities

- **API Layer**: Handles HTTP requests, validation, rate limiting, and error responses
- **Service Layer**: Business logic for URL shortening, resolution, and analytics
- **Redis**: Hot URL cache, rate limiting, real-time click counters
- **PostgreSQL**: Persistent storage for URL mappings and analytics
- **Message Queue**: Async analytics event processing (Kafka/RabbitMQ)

## Tech Stack

### Core Framework
- **Spring Boot 3.x**: Application framework
- **Java 17+**: Programming language (LTS)
- **Spring Data JPA**: Database access layer
- **Spring Data Redis**: Redis integration

### Database & Cache
- **PostgreSQL 14+**: Primary database (or MySQL 8+)
- **Redis 7+**: In-memory cache and rate limiting

### Build & Deployment
- **Maven/Gradle**: Build tool
- **Docker**: Containerization
- **Docker Compose**: Local development environment

### Optional (Production)
- **Kafka/RabbitMQ**: Message queue for analytics
- **Prometheus**: Metrics collection
- **Grafana**: Metrics visualization
- **ELK Stack**: Log aggregation

## Features

### Core Features
- ✅ URL shortening with Base62 encoding
- ✅ Custom alias support
- ✅ URL expiration
- ✅ Tag-based organization
- ✅ Click analytics
- ✅ Rate limiting (per IP/user)

### Performance Features
- ✅ Redis caching (99%+ cache hit rate)
- ✅ Async analytics processing
- ✅ Batch database updates
- ✅ Non-blocking redirects

### Production Features
- ✅ Graceful failure handling
- ✅ Health checks
- ✅ Structured logging
- ✅ Error tracking
- ✅ Background job for counter sync

## API Documentation

### Base URL
```
http://localhost:8080
```

### 1. Shorten URL

**Endpoint:** `POST /api/v1/urls/shorten`

**Request:**
```json
{
  "longUrl": "https://example.com/very/long/url/path",
  "customAlias": "my-link",  // Optional
  "expirationDays": 365,      // Optional
  "tags": ["tag1", "tag2"]    // Optional
}
```

**Response (201 Created):**
```json
{
  "shortCode": "4c92",
  "shortUrl": "http://localhost:8080/4c92",
  "longUrl": "https://example.com/very/long/url/path",
  "createdAt": "2024-01-15T10:30:00Z",
  "expiresAt": "2025-01-15T10:30:00Z",
  "tags": ["tag1", "tag2"]
}
```

**Error Responses:**
- `400 Bad Request`: Invalid URL format
- `409 Conflict`: Custom alias already exists
- `429 Too Many Requests`: Rate limit exceeded

**Example:**
```bash
curl -X POST http://localhost:8080/api/v1/urls/shorten \
  -H "Content-Type: application/json" \
  -d '{
    "longUrl": "https://example.com/very/long/url",
    "customAlias": "my-link"
  }'
```

### 2. Redirect to Long URL

**Endpoint:** `GET /{shortCode}`

**Response (302 Found):**
```
Location: https://example.com/very/long/url/path
```

**Error Responses:**
- `404 Not Found`: Short code doesn't exist
- `410 Gone`: URL expired or inactive
- `429 Too Many Requests`: Rate limit exceeded

**Example:**
```bash
curl -I http://localhost:8080/4c92
```

### 3. Get URL Analytics

**Endpoint:** `GET /api/v1/urls/{shortCode}/analytics`

**Query Parameters:**
- `startDate` (optional): ISO 8601 date
- `endDate` (optional): ISO 8601 date
- `granularity` (optional): `hour`, `day`, `week`, `month`

**Response (200 OK):**
```json
{
  "shortCode": "4c92",
  "totalClicks": 1234,
  "uniqueClicks": 890,
  "timeSeries": [
    {
      "timestamp": "2024-01-15T00:00:00Z",
      "clicks": 45,
      "uniqueClicks": 32
    }
  ],
  "topReferrers": [
    {"referrer": "https://twitter.com", "clicks": 234}
  ],
  "topCountries": [
    {"country": "US", "clicks": 456}
  ]
}
```

## Redis Key Design

### Key Patterns

| Pattern | Type | TTL | Purpose |
|---------|------|-----|---------|
| `url:{shortCode}` | String | 7 days | URL mappings (fast redirects) |
| `clicks:{shortCode}` | String | None | Click counters (synced to DB) |
| `rate:ip:{ip}` | Sorted Set | 60s | Rate limiting per IP |
| `rate:user:{userId}` | Sorted Set | 60s | Rate limiting per user |

### Key Examples

```
url:4c92 → "https://example.com/very/long/url"
clicks:4c92 → "1234"
rate:ip:192.168.1.1 → ZSET with timestamps
```

### Memory Management

- **URL mappings**: ~100 bytes per key
- **Click counters**: ~50 bytes per key
- **Rate limit keys**: ~200 bytes per key (sorted set)
- **Automatic expiration**: TTL-based cleanup
- **Manual cleanup**: Background job syncs and cleans counters

See [redis-keys.md](src/main/resources/redis-keys.md) for detailed documentation.

## Rate Limiting Strategy

### Algorithm: Sliding Window Log

Uses Redis sorted sets to track request timestamps within a time window.

### Configuration

- **Shorten endpoint**: 100 requests/minute (default)
- **Redirect endpoint**: 1000 requests/minute (default)
- **Window duration**: 60 seconds (configurable)

### Implementation

- **Atomic operations**: Lua scripts ensure consistency
- **Distributed**: Works across multiple application instances
- **Fail-open**: Allows requests if Redis is down (prevents outages)

### Rate Limit Headers

```
X-RateLimit-Remaining: 95
Retry-After: 60
```

### Why Rate Limiting is Critical

1. **Abuse Prevention**: Prevents DDoS attacks and API abuse
2. **Resource Protection**: Ensures fair usage of system resources
3. **Cost Control**: Limits infrastructure costs
4. **Service Stability**: Maintains availability for all users

## Failure Handling

### Redis Down

**Strategy**: Fail-open (allow requests)

- **URL resolution**: Falls back to database
- **Rate limiting**: Allows all requests (logged)
- **Click counting**: Logs warning, continues
- **Cache writes**: Non-critical, can rebuild

**Rationale**: Better to allow some abuse than deny all legitimate users.

### Database Down

**Strategy**: Serve from cache if available

- **URL resolution**: Returns 503 if cache miss
- **URL shortening**: Returns 503 (requires DB write)
- **Analytics**: Queued for later processing

### Analytics Failures

**Strategy**: Non-blocking, fire-and-forget

- **Redirect performance**: Never blocked by analytics
- **Event loss**: Acceptable (eventually consistent)
- **Retry mechanism**: Handled by message queue

### Background Job Failures

**Strategy**: Graceful degradation

- **Counter sync**: Individual failures don't stop job
- **Batch processing**: Continues with next batch
- **Logging**: All errors logged for monitoring

## Scalability

### Horizontal Scaling

- **Stateless API**: Multiple instances behind load balancer
- **Shared Redis**: All instances share same cache
- **Database replicas**: Read replicas for analytics queries

### Performance Optimizations

1. **Cache-First Strategy**: 99%+ requests served from Redis (< 1ms)
2. **Async Processing**: Analytics don't block redirects
3. **Batch Updates**: Database updates in batches
4. **Connection Pooling**: Efficient database connections
5. **CDN Integration**: Cache redirects at edge (CloudFlare, CloudFront)

### Capacity Planning

**Single Instance:**
- **Redirects**: ~10,000 requests/second (with Redis cache)
- **Shortening**: ~1,000 requests/second
- **Database**: PostgreSQL can handle 10K+ writes/second

**Scaled Deployment:**
- **10 instances**: ~100,000 redirects/second
- **Redis cluster**: Handles millions of operations/second
- **Database cluster**: Read replicas for analytics

### Bottlenecks & Solutions

| Bottleneck | Solution |
|------------|----------|
| Database writes | Batch updates, async processing |
| Redis memory | TTL-based expiration, cleanup jobs |
| Network latency | CDN, edge caching |
| Analytics processing | Message queue, batch consumers |

## Getting Started

### Prerequisites

- Java 17+
- Maven 3.8+
- Docker & Docker Compose (for local development)
- PostgreSQL 14+ (or use Docker)
- Redis 7+ (or use Docker)

### Local Development (Docker Compose)

1. **Clone the repository:**
```bash
git clone <repository-url>
cd url-shortener
```

2. **Start services:**
```bash
docker-compose up -d
```

This starts:
- PostgreSQL (port 5432)
- Redis (port 6379)
- Spring Boot application (port 8080)

3. **Check logs:**
```bash
docker-compose logs -f app
```

4. **Test the API:**
```bash
curl -X POST http://localhost:8080/api/v1/urls/shorten \
  -H "Content-Type: application/json" \
  -d '{"longUrl": "https://example.com"}'
```

### Local Development (Manual)

1. **Start PostgreSQL and Redis:**
```bash
# PostgreSQL
docker run -d -p 5432:5432 -e POSTGRES_PASSWORD=postgres postgres:14

# Redis
docker run -d -p 6379:6379 redis:7
```

2. **Configure application.properties:**
```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/urlshortener
spring.datasource.username=postgres
spring.datasource.password=postgres
spring.data.redis.host=localhost
spring.data.redis.port=6379
```

3. **Run the application:**
```bash
mvn spring-boot:run
```

### Building Docker Image

```bash
docker build -t url-shortener:latest .
```

## Configuration

### Application Properties

```properties
# Database
spring.datasource.url=jdbc:postgresql://postgres:5432/urlshortener
spring.datasource.username=postgres
spring.datasource.password=postgres

# Redis
spring.data.redis.host=redis
spring.data.redis.port=6379

# URL Shortener Settings
url-shortener.base-url=http://localhost:8080
url-shortener.cache.ttl-days=7

# Rate Limiting
url-shortener.rate-limit.shorten.requests-per-minute=100
url-shortener.rate-limit.redirect.requests-per-minute=1000
url-shortener.rate-limit.window-seconds=60

# Background Jobs
url-shortener.sync.interval-minutes=5
url-shortener.sync.batch-size=100
url-shortener.sync.max-counters-per-run=10000
```

### Environment Variables

All properties can be overridden via environment variables:

```bash
export SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/urlshortener
export SPRING_DATA_REDIS_HOST=redis
export URL_SHORTENER_BASE_URL=https://short.ly
```

## Monitoring & Observability

### Health Checks

**Endpoint:** `GET /actuator/health`

```json
{
  "status": "UP",
  "components": {
    "db": {"status": "UP"},
    "redis": {"status": "UP"}
  }
}
```

### Metrics (Prometheus)

**Endpoint:** `GET /actuator/prometheus`

Key metrics:
- `http_requests_total`: Total HTTP requests
- `cache_hits_total`: Redis cache hits
- `cache_misses_total`: Redis cache misses
- `rate_limit_exceeded_total`: Rate limit violations
- `click_counter_sync_duration`: Background job duration

### Logging

Structured JSON logging (for ELK stack):

```json
{
  "timestamp": "2024-01-15T10:30:00Z",
  "level": "INFO",
  "logger": "com.shortener.service.UrlRedirectService",
  "message": "Resolved short code: 4c92",
  "shortCode": "4c92",
  "duration": 2
}
```

## Production Deployment

### Recommended Architecture

```
┌─────────────┐
│ Load       │
│ Balancer   │
└─────┬───────┘
      │
      ├──────────┬──────────┬──────────┐
      ▼          ▼          ▼          ▼
┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐
│ App     │ │ App     │ │ App     │ │ App     │
│ Instance│ │ Instance│ │ Instance│ │ Instance│
└────┬────┘ └────┬────┘ └────┬────┘ └────┬────┘
     │          │          │          │
     └──────────┴──────────┴──────────┘
                │
     ┌──────────┴──────────┐
     ▼                     ▼
┌──────────┐         ┌──────────┐
│ Redis    │         │PostgreSQL│
│ Cluster  │         │ Cluster  │
└──────────┘         └──────────┘
```

### Deployment Checklist

- [ ] Configure production database (with replication)
- [ ] Set up Redis cluster (high availability)
- [ ] Configure message queue (Kafka/RabbitMQ)
- [ ] Set up monitoring (Prometheus, Grafana)
- [ ] Configure logging (ELK stack)
- [ ] Set up CI/CD pipeline
- [ ] Configure SSL/TLS certificates
- [ ] Set up CDN (CloudFlare, CloudFront)
- [ ] Configure backup strategy
- [ ] Set up alerting (PagerDuty, etc.)

### Security Considerations

1. **Authentication**: Implement JWT/OAuth2 for user endpoints
2. **HTTPS**: Enforce HTTPS only
3. **Rate Limiting**: Configure appropriate limits
4. **Input Validation**: Sanitize all inputs
5. **SQL Injection**: Use parameterized queries (JPA handles this)
6. **Redis Security**: Enable authentication, use SSL
7. **Database Security**: Use connection encryption, limit access

## License

This project is licensed under the MIT License.

## Contributing

Contributions are welcome! Please read the contributing guidelines and submit pull requests.

## Support

For issues and questions, please open an issue on GitHub.




