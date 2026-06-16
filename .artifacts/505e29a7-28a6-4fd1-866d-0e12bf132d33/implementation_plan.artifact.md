# Fix Build Failure: GitHub Packages Authentication

The build is failing with `401 Unauthorized` because it cannot resolve dependencies from the Meta Wearables GitHub Packages repository (`https://maven.pkg.github.com/facebook/meta-wearables-dat-android`). This repository requires a GitHub Personal Access Token (PAT) for authentication.

## User Review Required

> [!IMPORTANT]
> You need to provide a GitHub Personal Access Token (PAT) with `read:packages` scope.
> This token will be used to authenticate with GitHub Packages to download the Meta Wearables DAT dependencies.

## Proposed Changes

### Configuration Update

I will guide you through adding the necessary credentials to your `local.properties` file. This file is local to your machine and is not checked into version control, making it a safe place for this token.

#### [MODIFY] [local.properties](file:///G:/My%20Drive/GraffitiXR/local.properties)
Add the following line to your `local.properties` file:
```properties
github_token=YOUR_GITHUB_PERSONAL_ACCESS_TOKEN
```
Replace `YOUR_GITHUB_PERSONAL_ACCESS_TOKEN` with a PAT you generate on GitHub.

### Steps to Generate a GitHub PAT:
1. Go to [GitHub Settings > Developer settings > Personal access tokens > Tokens (classic)](https://github.com/settings/tokens).
2. Click **Generate new token (classic)**.
3. Give it a descriptive name (e.g., "GraffitiXR Build").
4. Select the **read:packages** scope.
5. Click **Generate token**.
6. Copy the token immediately; you won't be able to see it again.

## Verification Plan

### Manual Verification
1. After adding the token to `local.properties`, I will ask you to run the build again (e.g., `./gradlew :app:bundleRelease` or Sync Project with Gradle Files).
2. Verify that the `401 Unauthorized` errors are gone and the dependencies are successfully downloaded.
