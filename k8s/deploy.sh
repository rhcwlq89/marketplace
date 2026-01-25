#!/bin/bash
set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${GREEN}=== Marketplace K8s Deployment ===${NC}"

# Set PATH for Homebrew and Colima
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"
export DOCKER_HOST="unix:///Users/$USER/.colima/default/docker.sock"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# Check if k3d cluster exists
if ! k3d cluster list | grep -q marketplace; then
    echo -e "${YELLOW}Creating k3d cluster...${NC}"
    k3d cluster create marketplace \
      --port "8080:80@loadbalancer" \
      --port "3000:3000@loadbalancer" \
      --port "9090:9090@loadbalancer" \
      --registry-create marketplace-registry:5000 \
      --agents 1
fi

# 1. Build Docker image
echo -e "${YELLOW}[1/5] Building Docker image...${NC}"
cd "$PROJECT_DIR"
docker build -t marketplace-app:latest .

# 2. Tag and push to local registry
echo -e "${YELLOW}[2/5] Pushing to local registry...${NC}"
docker tag marketplace-app:latest localhost:5000/marketplace-app:latest
docker push localhost:5000/marketplace-app:latest

# 3. Apply namespace
echo -e "${YELLOW}[3/5] Creating namespace...${NC}"
kubectl apply -f "$SCRIPT_DIR/namespace.yaml"

# 4. Deploy infrastructure
echo -e "${YELLOW}[4/5] Deploying infrastructure (MySQL, Redis, Kafka)...${NC}"
kubectl apply -f "$SCRIPT_DIR/mysql/"
kubectl apply -f "$SCRIPT_DIR/redis/"
kubectl apply -f "$SCRIPT_DIR/kafka/"

# Wait for infrastructure
echo -e "${YELLOW}Waiting for MySQL to be ready...${NC}"
kubectl wait --for=condition=ready pod -l app=mysql -n marketplace --timeout=120s || true

echo -e "${YELLOW}Waiting for Redis to be ready...${NC}"
kubectl wait --for=condition=ready pod -l app=redis -n marketplace --timeout=60s || true

echo -e "${YELLOW}Waiting for Zookeeper to be ready...${NC}"
kubectl wait --for=condition=ready pod -l app=zookeeper -n marketplace --timeout=60s || true

# 5. Deploy app and monitoring
echo -e "${YELLOW}[5/5] Deploying application and monitoring...${NC}"
kubectl apply -f "$SCRIPT_DIR/app/"
kubectl apply -f "$SCRIPT_DIR/monitoring/"

echo ""
echo -e "${GREEN}=== Deployment Complete ===${NC}"
echo ""
echo -e "Check status: ${YELLOW}kubectl get pods -n marketplace${NC}"
echo ""
echo -e "Access endpoints:"
echo -e "  - App:        ${GREEN}http://localhost:8080${NC}"
echo -e "  - Prometheus: ${GREEN}http://localhost:9090${NC}"
echo -e "  - Grafana:    ${GREEN}http://localhost:3000${NC} (admin/admin123)"
echo ""
echo -e "${YELLOW}Note: Kafka may take a few minutes to start. Check with:${NC}"
echo -e "  kubectl logs -l app=kafka -n marketplace"
