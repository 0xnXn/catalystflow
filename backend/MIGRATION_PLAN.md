# CatalystFlow backend migration plan

Owner and commit author: Nixon.

1. Commit the independent pipeline and infrastructure configuration.
2. Commit the renamed backend application, including current local source changes.
3. Commit validation fixes and operating documentation.
4. Create the personal-account CodeCommit repository and push main.

Repositories use fresh histories. Original local repositories are preserved.
Deployment requires fresh Cognito, hosting, IAM roles and Parameter Store settings.
Pipeline definitions are build-only; deployment buildspecs are provided separately.
No cloud application resources are deployed as part of the source import.
