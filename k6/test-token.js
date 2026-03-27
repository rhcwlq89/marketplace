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
    console.log('Reset complete. Starting Token test...');
}

export default function () {
    const userId = __VU * 10000 + __ITER;
    const params = { headers: { 'Content-Type': 'application/json' } };
    const start = Date.now();

    // Phase 1: 토큰 발급
    const issuePayload = JSON.stringify({ productId: 1, userId: userId, quantity: 1 });
    const issueRes = http.post(`${BASE_URL}/api/tokens/issue`, issuePayload, params);

    if (issueRes.status !== 200) {
        failCount.add(1);
        purchaseTime.add(Date.now() - start);
        return;
    }

    const token = JSON.parse(issueRes.body).token;

    // Phase 2: 토큰으로 구매
    const orderRes = http.post(
        `${BASE_URL}/api/orders/token`,
        JSON.stringify({ quantity: 1 }),
        {
            headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${token}`,
            },
        }
    );

    const elapsed = Date.now() - start;
    purchaseTime.add(elapsed);

    if (orderRes.status === 200) {
        successCount.add(1);
    } else {
        if (__ITER < 3) console.log(`Order failed: status=${orderRes.status} body=${orderRes.body}`);
        failCount.add(1);
    }

    check(orderRes, {
        'order status is 200 or 409': (r) => r.status === 200 || r.status === 409,
    });
}
