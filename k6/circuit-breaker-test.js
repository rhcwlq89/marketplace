import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

let product3Success = new Counter('product3_success');
let product3Failed = new Counter('product3_failed');
let product4Success = new Counter('product4_success');
let product4Failed = new Counter('product4_failed');

export let options = {
    vus: 10,
    duration: '8s',
};

export function setup() {
    let loginRes = http.post('http://localhost:8080/api/v1/auth/login',
        JSON.stringify({
            email: 'buyer@example.com',
            password: 'buyer123!'
        }),
        { headers: { 'Content-Type': 'application/json' } }
    );

    if (loginRes.status !== 200) {
        console.log(`Login failed: ${loginRes.status}`);
        return { token: null };
    }

    let body = JSON.parse(loginRes.body);
    console.log(`✅ Login successful`);

    // 상품 재고 확인
    let p3 = http.get('http://localhost:8080/api/v1/products/3');
    let p4 = http.get('http://localhost:8080/api/v1/products/4');
    console.log(`📦 Product 3 Stock: ${JSON.parse(p3.body).data.stockQuantity}`);
    console.log(`📦 Product 4 Stock: ${JSON.parse(p4.body).data.stockQuantity}`);

    return { token: body.data.accessToken };
}

export default function(data) {
    if (!data.token) return;

    let iteration = __ITER;

    // 처음 4초: 상품 3 주문 (30개 재고 소진시키기)
    // 나중 4초: 상품 4 주문 (Circuit Breaker가 열려도 동작하는지 확인)
    let productId = iteration < 40 ? 3 : 4;

    let orderRes = http.post('http://localhost:8080/api/v1/orders',
        JSON.stringify({
            orderItems: [{ productId: productId, quantity: 1 }],
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

    if (productId === 3) {
        if (orderRes.status === 200) {
            product3Success.add(1);
        } else {
            product3Failed.add(1);
            if (orderRes.status === 409) {
                let body = JSON.parse(orderRes.body);
                console.log(`❌ Product 3: ${body.code}`);
            } else if (orderRes.status === 503) {
                console.log(`⚡ Product 3: Circuit Breaker/Bulkhead`);
            }
        }
    } else {
        if (orderRes.status === 200) {
            product4Success.add(1);
            let body = JSON.parse(orderRes.body);
            console.log(`✅ Product 4 Order: ID=${body.data.id}`);
        } else {
            product4Failed.add(1);
            if (orderRes.status === 503) {
                console.log(`⚡ Product 4: Circuit Breaker BLOCKED! (This should not happen)`);
            } else {
                console.log(`❌ Product 4 failed: ${orderRes.status}`);
            }
        }
    }

    sleep(0.1);
}

export function teardown(data) {
    let p3 = http.get('http://localhost:8080/api/v1/products/3');
    let p4 = http.get('http://localhost:8080/api/v1/products/4');
    console.log(`\n📦 Final Product 3 Stock: ${JSON.parse(p3.body).data.stockQuantity}`);
    console.log(`📦 Final Product 4 Stock: ${JSON.parse(p4.body).data.stockQuantity}`);
    console.log(`🏁 Test completed`);
}
