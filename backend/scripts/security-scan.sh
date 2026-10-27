#!/usr/bin/env bash
set -euo pipefail
version=0.76.0
archive="trivy_${version}_Linux-64bit.tar.gz"
curl --fail --silent --show-error --location "https://github.com/aquasecurity/trivy/releases/download/v${version}/${archive}" -o "/tmp/${archive}"
curl --fail --silent --show-error --location "https://github.com/aquasecurity/trivy/releases/download/v${version}/trivy_${version}_checksums.txt" -o /tmp/trivy-checksums.txt
(cd /tmp && grep " ${archive}$" trivy-checksums.txt | sha256sum --check --strict)
tar -xzf "/tmp/${archive}" -C /usr/local/bin trivy
trivy --version
mkdir -p security-reports
# Unpack Lambda archives so nested JAR dependencies are scanned as shipped.
while IFS= read -r -d '' package; do
  module=$(basename "$(dirname "$(dirname "$package")")")
  mkdir -p "/tmp/catalystflow-artifacts/$module"
  unzip -q "$package" -d "/tmp/catalystflow-artifacts/$module"
done < <(find "${CODEBUILD_SRC_DIR_BuildOutput}" -name function.zip -print0)

set +e
trivy fs . --scanners vuln,secret --include-dev-deps --severity CRITICAL --format json -o security-reports/source.json --exit-code 1
source_status=$?
trivy rootfs /tmp/catalystflow-artifacts --scanners vuln,secret --severity CRITICAL --format json -o security-reports/artifact.json --exit-code 1
artifact_status=$?
# A successful empty result is not evidence that packaged dependencies were scanned.
if ! jq --exit-status '[.Results[]? | select(.Type == "jar")] | length > 0' security-reports/artifact.json >/dev/null; then
  echo "Artifact coverage error: no Java archives were identified."
  artifact_status=1
fi
set -e
if [ "$source_status" -ne 0 ] || [ "$artifact_status" -ne 0 ]; then
  echo "Security gate failed. Inspect security-reports/source.json and artifact.json."
  exit 1
fi
