#!/bin/bash

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m'

export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"

echo -e "${RED}=== Destroying Marketplace K8s Cluster ===${NC}"

k3d cluster delete marketplace

echo -e "${GREEN}Cluster deleted successfully${NC}"
