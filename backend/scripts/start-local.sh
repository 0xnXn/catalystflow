#!/bin/bash
set -e

until docker info > /dev/null 2>&1; do
    echo "Waiting for Docker to be ready..."
    sleep 1
done

docker network create catalystflow-network 2>/dev/null || true

# start dynamo db
if ! docker ps | grep -q dynamodb-local; then
    echo "Starting DynamoDB Local..."
    docker run --rm -d \
        --name dynamodb-local \
        --network catalystflow-network \
        -p 8000:8000 \
        amazon/dynamodb-local \
        -jar DynamoDBLocal.jar -sharedDb
    sleep 5
fi

bash /workspaces/CatalystFlow-backend/scripts/setup-tables.sh

# start dynamodb ui
if ! docker ps | grep -q dynamodb-admin; then
    echo "Starting DynamoDB UI..."
    docker run --rm -d \
        --name dynamodb-admin \
        --network catalystflow-network \
        -p 8001:8001 \
        -e DYNAMO_ENDPOINT=http://dynamodb-local:8000 \
        aaronshaf/dynamodb-admin
fi