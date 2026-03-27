import http from 'k6/http';
import { check } from 'k6';
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
            maxDuration: '60s',
        },
    },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export function setup() {
    const resetRes = http.post(`${BASE_URL}/api/fcfs/reset?productId=1&stock=100`);
    check(resetRes, { 'reset ok': (r) => r.status === 200 });
    console.log('Reset complete. Starting DB Lock test...');
}

export default function () {
    const userId = __VU * 10000 + __ITER;
    const payload = JSON.stringify({ productId: 1, userId: userId, quantity: 1 });
    const params = { headers: { 'Content-Type': 'application/json' } };

    const start = Date.now();
    const res = http.post(`${BASE_URL}/api/orders/db-lock`, payload, params);
    const elapsed = Date.now() - start;

    purchaseTime.add(elapsed);

    if (res.status === 200) {
        successCount.add(1);
    } else {
        failCount.add(1);
    }

    check(res, {
        'status is 200 or 409': (r) => r.status === 200 || r.status === 409,
    });
}
