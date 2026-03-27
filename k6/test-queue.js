import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const successCount = new Counter('success_count');
const failCount = new Counter('fail_count');
const purchaseTime = new Trend('purchase_time');

export const options = {
    scenarios: {
        spike: {
            executor: 'shared-iterations',
            vus: __ENV.VUS ? parseInt(__ENV.VUS) : 100,
            iterations: __ENV.ITERATIONS ? parseInt(__ENV.ITERATIONS) : 100,
            maxDuration: '120s',
        },
    },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export function setup() {
    const resetRes = http.post(`${BASE_URL}/api/fcfs/reset?productId=1&stock=100`);
    check(resetRes, { 'reset ok': (r) => r.status === 200 });
    console.log('Reset complete. Starting Queue test...');
}

export default function () {
    const userId = __VU * 10000 + __ITER;
    const start = Date.now();

    // Phase 1: 대기열 진입
    const enterPayload = JSON.stringify({ productId: 1, userId: userId, quantity: 1 });
    const params = { headers: { 'Content-Type': 'application/json' } };
    const enterRes = http.post(`${BASE_URL}/api/queue/enter`, enterPayload, params);

    check(enterRes, { 'enter ok': (r) => r.status === 200 });

    if (enterRes.status !== 200) {
        failCount.add(1);
        purchaseTime.add(Date.now() - start);
        return;
    }

    // Phase 2: 폴링 — COMPLETED될 때까지 대기
    let completed = false;
    for (let i = 0; i < 60; i++) {
        const statusRes = http.get(
            `${BASE_URL}/api/queue/status?productId=1&userId=${userId}`
        );

        if (statusRes.status === 200) {
            const body = JSON.parse(statusRes.body);
            if (body.status === 'COMPLETED') {
                completed = true;
                break;
            }
            if (body.status === 'NOT_IN_QUEUE') {
                break;
            }
        }
        sleep(1);
    }

    const elapsed = Date.now() - start;
    purchaseTime.add(elapsed);

    if (completed) {
        successCount.add(1);
    } else {
        failCount.add(1);
    }
}
