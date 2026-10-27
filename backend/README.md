# CatalystFlow-backend

Maintainer: Nixon.

CatalystFlow runs in the personal-dev AWS profile, account 142531840386, ap-southeast-2. Source branch: main. Website: https://d27obzyuyg6azr.cloudfront.net

## Delivery pipeline

Source is followed by six independent CodeBuild stages. Every stage must pass before the next starts. Queued executions prevent overlapping deployments and E2E fixtures.

| Stage | Buildspec |
|---|---|
| UnitTests | `buildspec-test.yml` |
| IntegrationTests | `buildspec-integration.yml` |
| Build | `buildspec-build.yml` |
| SecurityScan | `buildspec-security.yml` |
| Deploy | `buildspec-deploy.yml` |
| E2E | `buildspec-e2e.yml` |

Build produces a checksummed immutable artifact. SecurityScan checks source dependencies and the built artifact using pinned Trivy 0.76.0 with a verified vendor release checksum; CRITICAL vulnerability or secret findings block deployment. Deploy verifies artifact checksums and publishes that same artifact. High/medium findings are outside the inherited CRITICAL gate and are not claimed resolved.

E2E runs after deployment to the development environment because it tests the real API/site. There is no production promotion stage. A failed E2E stage leaves the development deployment in place and marks the pipeline failed; it does not roll back application data.

Build/test/security use a check role with no deployment permissions. Backend E2E uses a separate role limited to the development Cognito pool and fixture tables. Deploy uses the delivery role. `infra/pipeline.yaml` is the pipeline definition; the backend repository's `infra/hosting.json` owns hosting, identity and role definitions.

Validate wiring with `python scripts/validate-delivery.py` (PyYAML required). It checks stage order, all buildspec references, artifact flow, and backend SAM packaging. `buildspec-host.yml` was removed: combined builds no longer bypass the separate gates.

## Backend and SAM

Java 21 / Quarkus, six Lambda modules, on-demand DynamoDB, SQS and Cognito. `template.yaml` is the AWS SAM definition; `template-local.yaml` is only for local emulation. The active build produces JVM function.zip packages and deploy explicitly selects `LambdaRuntime=java21`. The optional Maven native profile is not used by this pipeline.

UnitTests excludes `*IntegrationTest*`; IntegrationTests explicitly selects the user-service authentication integration suite. Build skips tests because prior stages gate it. Deploy validates SAM before deployment. E2E runs the standalone `e2e-tests` module, using environment-scoped SSM table/API values and the personal Cognito configuration.

Assistance and email actions remain disabled. AI action E2E tests are explicitly skipped with `ASSISTANCE_ENABLED=false`; `DisabledFeaturesE2ETest` verifies denials and the read-only discipline catalogue. Workers, queue mappings and schedules remain disabled. The catalogue can initialize without Gemini credentials. Development Cognito invitation emails are suppressed.

Local checks: `mvn test -Dquarkus.dynamodb.devservices.enabled=false -Dquarkus.sqs.devservices.enabled=false -Djacoco.skip=true`. Docker is only required for optional native builds.

## Synthetic organisation

`scripts/seed-demo-organisation.py` creates 22 realistic synthetic profiles and Cognito identities without invitations. Includes ADMIN/MANAGER/EMPLOYEE, three disciplines, Graduate through Principal, different employment types and reporting lines. Re-running only updates rows owned by this dataset. It does not import real employee information. All synthetic seeded identities use the public demonstration password `CatalystDemo@123`.

Commit histories are authored by Nixon. Original local source repositories are preserved; deployed resources and data are independent. Retain third-party dependency license notices.
