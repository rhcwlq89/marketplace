import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

// 커스텀 메트릭
let orderSuccess = new Counter('order_success');
let orderFailed = new Counter('order_failed');
let stockInsufficient = new Counter('stock_insufficient');
let lockFailed = new Counter('lock_failed');

export let options = {
    vus: 10,            // 가상 사용자 10명 (적절한 부하)
    duration: '5s',     // 5초간 실행
};

// 테스트 시작 전 1회 실행 (토큰 획득)
export function setup() {
    let loginRes = http.post('http://localhost:8080/api/v1/auth/login',
        JSON.stringify({
            email: 'buyer@example.com',
            password: 'buyer123!'
        }),
        { headers: { 'Content-Type': 'application/json' } }
    );

    if (loginRes.status !== 200) {
        console.log(`Setup login failed: ${loginRes.status} - ${loginRes.body}`);
        return { token: null };
    }

    let body = JSON.parse(loginRes.body);
    console.log(`✅ Login successful, token acquired`);

    // 테스트 전 상품 재고 확인
    let productRes = http.get('http://localhost:8080/api/v1/products/2');
    if (productRes.status === 200) {
        let product = JSON.parse(productRes.body);
        console.log(`📦 Product ID 2 - Stock: ${product.data.stockQuantity}`);
    }

    return { token: body.data.accessToken };
}

export default function(data) {
    if (!data.token) {
        console.log('No token available, skipping');
        return;
    }

    // 동일 상품(productId=1)에 동시 주문
    let orderRes = http.post('http://localhost:8080/api/v1/orders',
        JSON.stringify({
            orderItems: [{ productId: 2, quantity: 1 }],
            shippingAddress: {
                zipCode: '12345',
                address: 'Test Address',
                addressDetail: 'Apt 101',
                receiverName: 'Test User',
                receiverPhone: '010-1234-5678'
            }
        }),
        { headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${data.token}`
        }}
    );

    let success = check(orderRes, {
        'response received': (r) => r.status !== 0,
    });

    // 응답 코드별 메트릭 수집
    if (orderRes.status === 200) {
        orderSuccess.add(1);
        let body = JSON.parse(orderRes.body);
        console.log(`✅ Order created: ID=${body.data.id}`);
    } else if (orderRes.status === 409) {
        let body = JSON.parse(orderRes.body);
        if (body.code === 'INSUFFICIENT_STOCK') {
            stockInsufficient.add(1);
            console.log(`❌ Insufficient stock`);
        } else if (body.code === 'LOCK_ACQUISITION_FAILED') {
            lockFailed.add(1);
            console.log(`🔒 Lock acquisition failed`);
        } else {
            orderFailed.add(1);
            console.log(`❌ Order failed: ${body.code}`);
        }
    } else if (orderRes.status === 429) {
        // Rate limited - 정상적인 보호 동작
    } else if (orderRes.status === 503) {
        console.log(`⚡ Circuit breaker or bulkhead triggered`);
    } else {
        orderFailed.add(1);
        console.log(`❌ Unexpected: ${orderRes.status} - ${orderRes.body.substring(0, 100)}`);
    }

    // 요청 간 짧은 대기 (Rate Limiter 회피)
    sleep(0.1);
}

export function teardown(data) {
    // 테스트 후 상품 재고 확인
    let productRes = http.get('http://localhost:8080/api/v1/products/2');
    if (productRes.status === 200) {
        let product = JSON.parse(productRes.body);
        console.log(`\n📦 Final Stock: ${product.data.stockQuantity}`);
    }
    console.log('🏁 Test completed');
}
