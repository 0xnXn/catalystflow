#!/bin/bash
set -e

# Other tools
pip install aws-sam-cli boto3 pyyaml
npm install -g @google/gemini-cli

# adding debugger to container
sudo mkdir -p /workspaces/.vscode
sudo tee /workspaces/.vscode/launch.json > /dev/null << 'EOF'
{
    "version": "0.2.0",
    "configurations": [
        {
            "type": "java",
            "name": "Attach to SAM Lambda",
            "request": "attach",
            "hostName": "localhost",
            "port": 5005
        }
    ]
}
EOF
