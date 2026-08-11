#!/bin/bash

# Load testing script for URL Shortener
# Uses Apache Bench (ab) for HTTP load testing

BASE_URL="${BASE_URL:-http://localhost:8080}"
CONCURRENT_USERS="${CONCURRENT_USERS:-100}"
TOTAL_REQUESTS="${TOTAL_REQUESTS:-10000}"

echo "========================================="
echo "URL Shortener Load Test"
echo "========================================="
echo "Base URL: $BASE_URL"
echo "Concurrent Users: $CONCURRENT_USERS"
echo "Total Requests: $TOTAL_REQUESTS"
echo "========================================="
echo ""

# First, create a short URL for redirect testing
echo "Creating short URL for redirect testing..."
SHORTEN_RESPONSE=$(curl -s -X POST "$BASE_URL/api/v1/urls/shorten" \
  -H "Content-Type: application/json" \
  -d '{"longUrl": "https://example.com/load-test"}')

SHORT_CODE=$(echo $SHORTEN_RESPONSE | grep -o '"shortCode":"[^"]*' | cut -d'"' -f4)

if [ -z "$SHORT_CODE" ]; then
  echo "ERROR: Failed to create short URL"
  exit 1
fi

echo "Short code created: $SHORT_CODE"
echo ""

# Test 1: Redirect endpoint (most critical)
echo "========================================="
echo "Test 1: Redirect Endpoint Load Test"
echo "========================================="
ab -n $TOTAL_REQUESTS -c $CONCURRENT_USERS \
  -H "Accept: */*" \
  "$BASE_URL/$SHORT_CODE" > redirect-load-test.txt

echo "Results saved to redirect-load-test.txt"
echo ""

# Test 2: Shorten endpoint
echo "========================================="
echo "Test 2: Shorten Endpoint Load Test"
echo "========================================="
ab -n 1000 -c 10 \
  -p shorten-request.json \
  -T "application/json" \
  "$BASE_URL/api/v1/urls/shorten" > shorten-load-test.txt

echo "Results saved to shorten-load-test.txt"
echo ""

# Test 3: Health endpoint
echo "========================================="
echo "Test 3: Health Endpoint Load Test"
echo "========================================="
ab -n 10000 -c 100 \
  "$BASE_URL/actuator/health" > health-load-test.txt

echo "Results saved to health-load-test.txt"
echo ""

echo "========================================="
echo "Load Testing Complete"
echo "========================================="
echo "Review the generated .txt files for detailed results"




