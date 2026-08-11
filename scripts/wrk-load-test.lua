-- wrk load testing script for URL Shortener
-- Usage: wrk -t12 -c400 -d30s -s wrk-load-test.lua http://localhost:8080

-- Initialize
local shortenUrl = nil
local shortCode = nil

-- Setup: Create a short URL once
function setup(thread)
    thread:set("id", math.random(1000000))
end

function init(args)
    -- Create a short URL for redirect testing
    local response = http.request("POST", "http://localhost:8080/api/v1/urls/shorten",
        '{"longUrl": "https://example.com/wrk-test-' .. math.random(1000000) .. '"}',
        {["Content-Type"] = "application/json"})
    
    if response.status == 201 then
        local body = json.decode(response.body)
        shortCode = body.shortCode
        print("Created short code: " .. shortCode)
    else
        print("Failed to create short URL: " .. response.status)
    end
end

-- Request function: 80% redirects, 20% shorten
function request()
    if shortCode and math.random() < 0.8 then
        -- Redirect request (80% of traffic)
        return wrk.format("GET", "/" .. shortCode)
    else
        -- Shorten request (20% of traffic)
        local url = "https://example.com/test-" .. math.random(1000000)
        local body = '{"longUrl": "' .. url .. '"}'
        return wrk.format("POST", "/api/v1/urls/shorten", {
            ["Content-Type"] = "application/json"
        }, body)
    end
end

-- Response function: Track latency percentiles
function response(status, headers, body)
    if status ~= 200 and status ~= 201 and status ~= 302 then
        print("Error status: " .. status)
    end
end

-- Done: Print summary
function done(summary, latency, requests)
    print("=========================================")
    print("Load Test Summary")
    print("=========================================")
    print(string.format("Total Requests: %d", summary.requests.total))
    print(string.format("Successful: %d", summary.requests.completed))
    print(string.format("Failed: %d", summary.requests.total - summary.requests.completed))
    print(string.format("Latency (p50): %dms", latency:percentile(50) / 1000))
    print(string.format("Latency (p95): %dms", latency:percentile(95) / 1000))
    print(string.format("Latency (p99): %dms", latency:percentile(99) / 1000))
    print("=========================================")
end




