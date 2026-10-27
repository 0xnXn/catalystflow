#!/bin/bash
set -e

TEMPLATE=${1:-/workspaces/CatalystFlow-backend/template-local.yaml}
ENDPOINT=${2:-http://localhost:8000}
REGION=${3:-ap-southeast-2}

echo "Scanning $TEMPLATE for DynamoDB tables..."

python3 - <<EOF
import yaml
import boto3

# handle CloudFormation intrinsic functions so yaml doesn't choke
def cf_constructor(loader, tag_suffix, node):
    if isinstance(node, yaml.ScalarNode):
        return loader.construct_scalar(node)
    elif isinstance(node, yaml.SequenceNode):
        return loader.construct_sequence(node)
    elif isinstance(node, yaml.MappingNode):
        return loader.construct_mapping(node)

yaml.add_multi_constructor('!', cf_constructor, Loader=yaml.SafeLoader)

with open("$TEMPLATE") as f:
    template = yaml.safe_load(f)

resources = template.get("Resources", {})
dynamodb = boto3.client(
    "dynamodb",
    endpoint_url="$ENDPOINT",
    region_name="$REGION",
    aws_access_key_id="test",
    aws_secret_access_key="test"
)

for name, resource in resources.items():
    if resource.get("Type") != "AWS::DynamoDB::Table":
        continue

    props = resource.get("Properties", {})
    table_name = props.get("TableName", name)

    try:
        dynamodb.describe_table(TableName=table_name)
        print(f"Table {table_name} already exists, skipping")
        continue
    except dynamodb.exceptions.ResourceNotFoundException:
        pass

    print(f"Creating table: {table_name}")
    create_kwargs = {
        "TableName": table_name,
        "AttributeDefinitions": props["AttributeDefinitions"],
        "KeySchema": props["KeySchema"],
        "BillingMode": props.get("BillingMode", "PAY_PER_REQUEST")
    }

    if "GlobalSecondaryIndexes" in props:
        create_kwargs["GlobalSecondaryIndexes"] = props["GlobalSecondaryIndexes"]

    if "LocalSecondaryIndexes" in props:
        create_kwargs["LocalSecondaryIndexes"] = props["LocalSecondaryIndexes"]

    try:
        dynamodb.create_table(**create_kwargs)
        print(f"Created: {table_name}")
    except Exception as e:
        print(f"Failed to create {table_name}: {e}")

print("Done!")
EOF

echo ""
read -p "Press enter to close script..."
exit