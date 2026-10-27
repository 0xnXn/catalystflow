# CatalystFlow-frontend

Maintainer: Nixon.

CatalystFlow runs in the personal-dev AWS profile, account 142531840386, ap-southeast-2. Source branch: main. Website: https://d27obzyuyg6azr.cloudfront.net

## Delivery pipeline

Source is followed by six independent CodeBuild stages. Every stage must pass before the next starts. Queued executions prevent overlapping deployments and E2E fixtures.

| Stage | Buildspec |
|---|---|
| UnitTests | `buildspec-test.yml` |
| IntegrationTests | `buildspec-integration.yml` |
| Build | `buildspec.yml` |
| SecurityScan | `buildspec-security.yml` |
| Deploy | `buildspec-deploy.yml` |
| E2E | `buildspec-e2e.yml` |

Build produces a checksummed immutable artifact. SecurityScan checks source dependencies and the built artifact using pinned Trivy 0.76.0 with a verified vendor release checksum; CRITICAL vulnerability or secret findings block deployment. Deploy verifies artifact checksums and publishes that same artifact. High/medium findings are outside the inherited CRITICAL gate and are not claimed resolved.

E2E runs after deployment to the development environment because it tests the real API/site. There is no production promotion stage. A failed E2E stage leaves the development deployment in place and marks the pipeline failed; it does not roll back application data.

Build/test/security use a check role with no deployment permissions. Backend E2E uses a separate role limited to the development Cognito pool and fixture tables. Deploy uses the delivery role. `infra/pipeline.yaml` is the pipeline definition; the backend repository's `infra/hosting.json` owns hosting, identity and role definitions.

Validate wiring with `python scripts/validate-delivery.py` (PyYAML required). It checks stage order, all buildspec references, artifact flow, and backend SAM packaging. `buildspec-host.yml` was removed: combined builds no longer bypass the separate gates.

## Frontend

Angular 18. `npm ci`, `npm run start:proxy` for local development (API localhost:3000). `npm run test:ci` for tests; Chrome is required. UnitTests excludes `*.integration.spec.ts`; IntegrationTests selects those files and verifies the component/interceptor/profile flow. Browser E2E (`scripts/e2e.mjs`) signs in as each public demo role against the deployed website.

Build obtains the backend API URL from `/catalystflow/dev/backendApiUrl`, then `scripts/configure.mjs` generates production configuration from Cognito/client/region inputs. Browser code never contains AWS credentials or Cognito client secrets. Deploy publishes the built browser artifact to private S3 through CloudFront.

`DEMO_ACCOUNTS_ENABLED=true` displays the public demo credential panel in development. Shared password: `CatalystDemo@123`. These are separate from the maintainer account. The panel is off by default in other builds. `scripts/seed-demo.py` provisions/resets only those three demonstration accounts; no invitation is sent. Use synthetic data only in this shared sandbox.

Commit histories are authored by Nixon. Original local source repositories are preserved; deployed resources and data are independent. Retain third-party dependency license notices.
