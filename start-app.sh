#!/bin/bash

echo "🚀 Starting URL Shortener Application"
echo "======================================"
echo ""

# Stop any existing containers and app
echo "1. Cleaning up..."
docker stop url-shortener-postgres url-shortener-redis 2>/dev/null
docker rm url-shortener-postgres url-shortener-redis 2>/dev/null
pkill -f "url-shortener" 2>/dev/null
sleep 2

# Start PostgreSQL
echo "2. Starting PostgreSQL..."
docker run -d --name url-shortener-postgres \
  -e POSTGRES_DB=urlshortener \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -p 5433:5432 \
  postgres:14-alpine

# Start Redis
echo "3. Starting Redis..."
docker run -d --name url-shortener-redis \
  -p 6380:6379 \
  redis:7-alpine

# Wait for services
echo "4. Waiting for services to be ready..."
sleep 10

# Configure PostgreSQL to allow connections
echo "5. Configuring PostgreSQL..."
docker exec url-shortener-postgres sh -c "echo 'host all all 0.0.0.0/0 md5' > /var/lib/postgresql/data/pg_hba.conf"
docker exec url-shortener-postgres sh -c "echo 'local all all trust' >> /var/lib/postgresql/data/pg_hba.conf"
docker exec url-shortener-postgres sh -c "echo 'host all all 127.0.0.1/32 trust' >> /var/lib/postgresql/data/pg_hba.conf"
docker exec url-shortener-postgres sh -c "echo 'host all all ::1/128 trust' >> /var/lib/postgresql/data/pg_hba.conf"
docker restart url-shortener-postgres
sleep 8

# Start application
echo "6. Starting application..."
cd "$(dirname "$0")"
nohup java -jar target/url-shortener-1.0.0.jar > app.log 2>&1 &

echo ""
echo "⏳ Waiting 60 seconds for application to start..."
sleep 60

# Check status
echo ""
echo "7. Checking status..."
if tail -100 app.log | grep -q "Started UrlShortenerApplication"; then
    echo "✅✅✅ APPLICATION STARTED SUCCESSFULLY! ✅✅✅"
    echo ""
    echo "🌐 Application is running at: http://localhost:8081"
    echo ""
    echo "Test it with:"
    echo "  curl http://localhost:8081/actuator/health"
    echo "  curl -X POST http://localhost:8081/api/v1/urls/shorten -H 'Content-Type: application/json' -d '{\"longUrl\": \"https://www.google.com\"}'"
else
    echo "❌ Application failed to start. Check app.log for details:"
    tail -20 app.log | grep -E "ERROR|Exception" | tail -5
fi




