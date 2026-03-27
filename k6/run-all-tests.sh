#!/bin/bash
# 전체 부하 테스트 자동화 스크립트
# 각 방식 × 각 VU × 10회 반복 → 평균값 추출

RESULT_DIR="results/runs"
mkdir -p "$RESULT_DIR"

K6_OPTS="--summary-trend-stats=avg,p(95),p(99),max"

run_test() {
    local script=$1
    local vus=$2
    local iterations=$3
    local name=$4
    local runs=$5

    echo "=== $name: ${vus} VUs × ${runs} runs ==="

    for i in $(seq 1 $runs); do
        local outfile="$RESULT_DIR/${name}-${vus}-run${i}.txt"
        k6 run $K6_OPTS -e VUS=$vus -e ITERATIONS=$iterations "$script" 2>&1 > "$outfile"
        # Extract key metrics
        local avg=$(grep "purchase_time" "$outfile" | head -1 | sed 's/.*avg=\([0-9.]*\).*/\1/')
        local p95=$(grep "purchase_time" "$outfile" | head -1 | sed 's/.*p(95)=\([0-9.]*\).*/\1/')
        local p99=$(grep "purchase_time" "$outfile" | head -1 | sed 's/.*p(99)=\([0-9.]*\).*/\1/')
        local success=$(grep "success_count" "$outfile" | sed 's/.*: \([0-9]*\).*/\1/')
        local fail=$(grep "fail_count" "$outfile" | sed 's/.*: \([0-9]*\).*/\1/')
        local iters_per_sec=$(grep "iterations\.\." "$outfile" | head -1 | sed 's/.*: [0-9]*[[:space:]]*\([0-9.]*\)\/s/\1/')
        echo "  Run $i: avg=${avg}ms p95=${p95}ms p99=${p99}ms success=${success} fail=${fail:-0} iters/s=${iters_per_sec}"
        # TIME_WAIT 포트 해제 대기 (2000 VU 이상은 15초, 미만은 5초)
        if [ "$vus" -ge 2000 ]; then
            sleep 15
        else
            sleep 5
        fi
    done
    echo ""
}

echo "=========================================="
echo " FCFS Load Test — 10 runs per scenario"
echo "=========================================="
echo ""

# DB Lock
run_test "k6/test-db-lock.js" 100 100 "db-lock" 10
run_test "k6/test-db-lock.js" 500 500 "db-lock" 10
run_test "k6/test-db-lock.js" 1000 1000 "db-lock" 10
run_test "k6/test-db-lock.js" 2000 2000 "db-lock" 10

# Redis
run_test "k6/test-redis.js" 100 100 "redis" 10
run_test "k6/test-redis.js" 500 500 "redis" 10
run_test "k6/test-redis.js" 1000 1000 "redis" 10
run_test "k6/test-redis.js" 2000 2000 "redis" 10

# Token
run_test "k6/test-token.js" 100 100 "token" 10
run_test "k6/test-token.js" 500 500 "token" 10
run_test "k6/test-token.js" 1000 1000 "token" 10
run_test "k6/test-token.js" 2000 2000 "token" 10

# Queue (100/500/1000 × 10회, 5000 × 3회)
run_test "k6/test-queue.js" 100 100 "queue" 10
run_test "k6/test-queue.js" 500 500 "queue" 10
run_test "k6/test-queue.js" 1000 1000 "queue" 10
run_test "k6/test-queue.js" 2000 2000 "queue" 3

echo "=========================================="
echo " All tests complete!"
echo "=========================================="
