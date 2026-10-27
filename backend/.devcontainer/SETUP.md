# CatalystFlow Backend - Developer Setup Guide

## Overview

This guide covers everything you need to clone the backend repo, start the devcontainer, run the stack locally, and attach a debugger to a Lambda function.

---


```

Local Dev Stack

        
           curl / ng serve (localhost:4200)
                        ↓
        SAM local API Gateway (localhost:3000)
                        ↓
            ┌───────────┴───────────┐
            │                       │
        Lambda 1              ...Lambda n
  (Quarkus JVM / java25)   (Quarkus JVM / java25)
            │                       │
            └───────────┬───────────┘
                        ↓
            DynamoDB Local (localhost:8000)
        

        
```
## Prerequisites

The devcontainer handles all tooling automatically. You only need:

- [Docker Desktop](https://www.docker.com/products/docker-desktop/) (or Docker Engine on Linux)
- [VS Code](https://code.visualstudio.com/) with the [Dev Containers extension](https://marketplace.visualstudio.com/items?itemName=ms-vscode-remote.remote-containers)

---

## Getting Started

### 1. Clone the repo

```bash
git clone <repo-url>
cd CatalystFlow-backend
```

### 2. Open in devcontainer

In VS Code: `Ctrl+Shift+P` → **Dev Containers: Reopen in Container**

The devcontainer will automatically:
- Install Java 25, Maven, Python, Node, AWS CLI, SAM CLI
- Start DynamoDB Local on port `8000`
- Create the `catalystflow-network` Docker network
- Create DynamoDB tables from `template-local.yaml`
- Install VS Code extensions (Java Pack, AWS Toolkit)

This takes a few minutes on first open. Subsequent opens are fast.

---

## Daily Dev Workflow

### Step 1 - Build (JVM mode, fast)

```bash
mvn clean package -Dquarkus.package.jar.type=legacy-jar
```

This produces `function.zip` in each service's `target/` directory - a JVM-compatible zip that SAM can run locally. Do this after every code change.

### Step 2 - Start SAM

```bash
sam local start-api -t template-local.yaml --docker-network catalystflow-network
```
This starts the API Gateway, routes will be printed on screen.

Routes are mounted at `http://localhost:3000`:

| Route | Function |
|---|---|
| `GET /reviews/health` | ReviewServiceFunction |
| `GET/POST /reviews` | ReviewServiceFunction |
| `GET/PUT/DELETE /reviews/{reviewId}` | ReviewServiceFunction |
| `GET /users/health` | UserServiceFunction |
| `GET/POST /users` | UserServiceFunction |
| `GET/PUT/DELETE /users/{userId}` | UserServiceFunction |


### Step 3 - Test with a fake JWT

Since SAM skips Cognito auth locally, pass a fake JWT with your desired role:

**As USER:**
```bash
curl -X POST \
  -H "Authorization: Bearer header.$(echo '{"sub":"test-user-id","cognito:groups":["USER"]}' | base64 -w 0).signature" \
  -H "Content-Type: application/json" \
  -d '{"content": "This is a test review"}' \
  http://localhost:3000/reviews
```

**As ADMIN:**
```bash
curl -X GET \
  -H "Authorization: Bearer header.$(echo '{"sub":"test-user-id","cognito:groups":["ADMIN"]}' | base64 -w 0).signature" \
  http://localhost:3000/reviews
```

**Health check (no auth needed):**
```bash
curl http://localhost:3000/reviews/health
curl http://localhost:3000/users/health
```

---

## Debugging

### How it works

SAM's `-d 5005` flag starts the Lambda JVM with `suspend=y` - the Lambda pauses immediately on startup and waits for a debugger to attach before executing any code.

### Step 1 - Start SAM in debug mode

```bash
export SAM_CLI_CONTAINER_CONNECTION_TIMEOUT=600
sam local start-api -t template-local.yaml --docker-network catalystflow-network -d 5005
```

### Step 2 - Trigger the Lambda

In a new terminal, hit an endpoint:

```bash
curl http://localhost:3000/reviews/health
```

SAM will start the Lambda container and it will pause, waiting for the debugger.

### Step 3 - Set a breakpoint

Open the relevant Java file in VS Code and click to the left of a line number to set a breakpoint (red dot appears). For example, open `ReviewResource.java` and set a breakpoint inside the `health()` method.

### Step 4 - Attach the debugger

In VS Code: `Ctrl+Shift+D` → select **"Attach to SAM Lambda"** → click ▶

The Lambda will resume execution and pause at your breakpoint. You can inspect variables, step through code, and view the call stack in the debug panel.

### Notes

- The debugger attaches per request - each new request spins up a new Lambda container, so you need to re-attach for each request
- `SAM_CLI_CONTAINER_CONNECTION_TIMEOUT=600` gives you 10 minutes to attach before SAM times out, you can change it to any time period long enough for the debugger to attach.


---

## Auth in Local Dev

The backend decodes the JWT itself from the `Authorization` header - it does **not** verify the signature. This means you can craft fake JWTs locally by base64 encoding a JSON payload.

`AuthFilter.java` reads:
- `sub` → userId
- `cognito:groups` → roles (e.g. `ADMIN`, `USER`)

JWT structure: `header.payload.signature`

**Generate a fake JWT for any role:**
```bash
# USER role
echo '{"sub":"test-user-id","cognito:groups":["USER"]}' | base64 -w 0

# ADMIN role
echo '{"sub":"admin-user-id","cognito:groups":["ADMIN"]}' | base64 -w 0
```

Pass as: `Authorization: Bearer header.<base64-payload>.signature`

---

## DynamoDB Local

Tables are created automatically on devcontainer start from `template-local.yaml` via `scripts/setup-tables.sh`.

**Verify tables exist:**
```bash
aws --endpoint-url=http://localhost:8000 dynamodb list-tables --region ap-southeast-2
```

**Scan a table:**
```bash
aws --endpoint-url=http://localhost:8000 dynamodb scan --table-name CatalystFlow_Reviews --region ap-southeast-2
```

**Recreate tables manually (if needed):**
```bash
bash scripts/setup-tables.sh
```

Note: DynamoDB Local data is lost when the container stops. Tables are recreated automatically on next devcontainer start.

---

## Template Differences

### `template.yaml` (prod) EDIT ONLY TO CONFIRM INFRA DEPLOYMENT
- Runtime: `provided.al2023` (GraalVM native)
- Has Cognito authorizer, S3 bucket, IAM policies
- Uses `!Ref` for table names and Cognito pool

### `template-local.yaml` (local)
- Runtime: `java25` (JVM)
- Cognito, S3, IAM stripped out - SAM skips them anyway
- Table names hardcoded (SAM doesn't resolve `!Ref` locally)
- `AWS_ENDPOINT_URL` points to DynamoDB Local
- `JAVA_TOOL_OPTIONS` disables AOT cache for debugger compatibility
-  Lambda Timeout set to 900s for debugging

### `template-local.yaml` PERMITTED CHANGES
- Add tables but follow TableName naming conventions exactly. 
- An example would be, CatalystFlow_Managers
- Copy paste existing lambda function blocks for new functions.
- Follow naming conventions for FunctionName and TableName.
- **NOTE** You can mess around in the template-local, but once you want to finalize your resources, update template.yaml as per required.

---

## Troubleshooting

**`Session expired` / AWS credentials error**
Dummy credentials aren't set. They should be set automatically via `remoteEnv` in `devcontainer.json`. Verify: `echo $AWS_ACCESS_KEY_ID` — should print `test`.

**`Git`**
is enabled within the container, however, it will not have your aws keys to be able to perform authenticated git commands.
The directory however, is synced with your host filesystem, which means you can run authenticated git commands (push, pull, rebase, merge, etc) from your host filesystem and it will reflect within the container.