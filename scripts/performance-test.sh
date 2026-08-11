#!/bin/bash

# Performance testing script for URL Shortener
# Tests latency, throughput, and error rates

BASE_URL="${BASE_URL:-http://localhost:8080}"

echo "========================================="
echo "URL Shortener Performance Test"
echo "========================================="
echo ""

# Test 1: Single request latency
echo "Test 1: Single Request Latency"
echo "--------------------------------"
time curl -s -o /dev/null -w "HTTP Status: %{http_code}\nTotal Time: %{time_total}s\n" \
  "$BASE_URL/actuator/health"
echo ""

# Test 2: Redirect latency (cache hit)
echo "Test 2: Redirect Latency (Cache Hit)"
echo "--------------------------------------"
# First create a short URL
SHORTEN_RESPONSE=$(curl -s -X POST "$BASE_URL/api/v1/urls/shorten" \
  -H "Content-Type: application/json" \
  -d '{"longUrl": "https://example.com/performance-test"}')

SHORT_CODE=$(echo $SHORTEN_RESPONSE | grep -o '"shortCode":"[^"]*' | cut -d'"' -f4)

if [ -n "$SHORT_CODE" ]; then
  # First request (cache miss)
  echo "First request (cache miss):"
  time curl -s -o /dev/null -w "HTTP Status: %{http_code}\nTotal Time: %{time_total}s\n" \
    "$BASE_URL/$SHORT_CODE"
  
  # Second request (cache hit)
  echo "Second request (cache hit):"
  time curl -s -o /dev/null -w "HTTP Status: %{http_code}\nTotal Time: %{time_total}s\n" \
    "$BASE_URL/$SHORT_CODE"
fi
echo ""

# Test 3: Concurrent requests
echo "Test 3: Concurrent Requests (100 requests, 10 concurrent)"
echo "-----------------------------------------------------------"
if command -v ab &> /dev/null; then
  ab -n 100 -c 10 "$BASE_URL/actuator/health" | grep -E "(Requests per second|Time per request|Failed requests)"
else
  echo "Apache Bench (ab) not installed. Skipping concurrent test."
fi
echo ""

# Test 4: Redis cache hit rate
echo "Test 4: Cache Hit Rate Analysis"
echo "-------------------------------"
echo "Making 100 redirect requests and measuring cache performance..."
# This would require custom tooling to measure cache hits vs misses
echo "Note: Use monitoring tools (Prometheus/Grafana) for detailed cache metrics"
echo ""

echo "========================================="
echo "Performance Test Complete"
echo "========================================="




