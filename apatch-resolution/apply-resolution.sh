#!/bin/bash
# Script to resolve conflicts and merge APatch PR #3
# See: https://github.com/AliShahidF2023-752/APatch/pull/3
#
# This script merges the upstream bmax121/APatch:main into
# AliShahidF2023-752/APatch:main and resolves the build.yml conflict.
#
# USAGE: Run this from a fresh clone of AliShahidF2023-752/APatch:
#   git clone https://github.com/AliShahidF2023-752/APatch.git
#   cd APatch
#   bash apply-resolution.sh

set -e

echo "=== Resolving APatch PR #3 conflict ==="

# Add upstream remote
git remote add upstream https://github.com/bmax121/APatch.git || true
git fetch upstream main

# Start the merge (will have conflict in build.yml)
git merge upstream/main || true

# Check if there are conflicts
if git diff --name-only --diff-filter=U | grep -q "build.yml"; then
  echo "=== Resolving build.yml conflict ==="

  cat > .github/workflows/build.yml << 'ENDOFFILE'
name: Build Manager

on:
  push:
    tags: [ "*" ]
    branches: [ "main" ]
    paths:
      - '.github/workflows/build.yml'
      - 'app/**'
      - 'apd/**'
      - 'build.gradle.kts'
      - 'gradle/libs.versions.toml'
  pull_request:
    branches: [ "main" ]
    paths:
      - '.github/workflows/build.yml'
      - 'app/**'
      - 'apd/**'
      - 'build.gradle.kts'
      - 'gradle/libs.versions.toml'
  workflow_call:
  workflow_dispatch:

jobs:
  build-manager:
    runs-on: ubuntu-latest
    permissions:
      contents: write
    steps:
      - name: Checkout
        uses: actions/checkout@v6
        with:
          fetch-depth: 0

      - name: Generate version
        id: parse_version
        run: |
          COMMIT_NUM=$(git rev-list --count HEAD)
          VERSION=$(echo "$COMMIT_NUM + 200 + 10000" | bc)
          echo "Generated Version: $VERSION"
          echo "VERSION=$VERSION" >> $GITHUB_OUTPUT

      - name: Setup Java
        uses: actions/setup-java@v5
        with:
          distribution: jetbrains
          java-version: 21

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v5

      - name: Setup Android SDK
        uses: android-actions/setup-android@v3
        with:
          packages: ''

      - name: Install toolchain
        run: |
          rustup default stable
          rustup update stable
          cargo install cargo-ndk
          rustup target install aarch64-linux-android

      - name: Cache Rust
        uses: Swatinem/rust-cache@v2
        with:
          workspaces: apd
          cache-targets: false

      - name: Build with Gradle
        run: |
          echo 'org.gradle.parallel=true' >> gradle.properties
          echo 'org.gradle.vfs.watch=true' >> gradle.properties
          echo 'org.gradle.jvmargs=-Xmx2048m' >> gradle.properties
          echo 'android.native.buildOutput=verbose' >> gradle.properties
          sed -i 's/org.gradle.configuration-cache=true//g' gradle.properties
          ./gradlew clean assembleDebug assembleRelease

      - name: Sign Release
        env:
          SIGNING_KEY: ${{ secrets.SIGNING_KEY }}
          BUILD_TOOLS_VERSION: ${{ env.BUILD_TOOL_VERSION }}
        if: ${{ env.SIGNING_KEY != '' }}
        continue-on-error: true
        uses: kevin-david/zipalign-sign-android-release@v2
        id: sign_app
        with:
          releaseDirectory: app/build/outputs/apk/release
          signingKeyBase64: ${{ secrets.SIGNING_KEY }}
          alias: ${{ secrets.ALIAS }}
          keyStorePassword: ${{ secrets.KEY_STORE_PASSWORD }}
          keyPassword: ${{ secrets.KEY_PASSWORD }}
          zipAlign: true

      - name: Sign Debug APK
        env:
          SIGNING_KEY: ${{ secrets.SIGNING_KEY }}
          BUILD_TOOLS_VERSION: ${{ env.BUILD_TOOL_VERSION }}
        if: ${{ env.SIGNING_KEY != '' }}
        continue-on-error: true
        uses: kevin-david/zipalign-sign-android-release@v2
        id: sign_debug_app
        with:
          releaseDirectory: app/build/outputs/apk/debug
          signingKeyBase64: ${{ secrets.SIGNING_KEY }}
          alias: ${{ secrets.ALIAS }}
          keyStorePassword: ${{ secrets.KEY_STORE_PASSWORD }}
          keyPassword: ${{ secrets.KEY_PASSWORD }}
          zipAlign: true

      - name: Upload mappings
        uses: actions/upload-artifact@v6
        with:
          name: "mappings"
          path: "app/build/outputs/mapping/release/"

      - name: Upload build artifact1
        env:
          SIGNING_KEY: ${{ secrets.SIGNING_KEY }}
        if: ${{ env.SIGNING_KEY != '' }}
        uses: actions/upload-artifact@v6
        with:
          name: APatch-Release
          path: |
            ${{ steps.sign_app.outputs.signedReleaseFile }}

      - name: Upload build artifact2
        env:
          SIGNING_KEY: ${{ secrets.SIGNING_KEY }}
        if: ${{ env.SIGNING_KEY != '' }}
        uses: actions/upload-artifact@v6
        with:
          name: APatch-Debug
          path: |
            ${{ steps.sign_debug_app.outputs.signedReleaseFile }}

      - name: Upload release artifact
        uses: actions/upload-artifact@v6
        with:
          name: APatch-Release
          path: app/build/outputs/apk/release/*.apk

      - name: Upload debug artifact
        uses: actions/upload-artifact@v6
        with:
          name: APatch-Debug
          path: app/build/outputs/apk/debug/*.apk

      - name: Release APK
        if: github.ref_type == 'tag'
        uses: ncipollo/release-action@v1
        with:
          token: ${{ github.token }}
          tag: ${{ steps.parse_version.outputs.VERSION }}
          artifacts: app/build/outputs/apk/release/*.apk
          generateReleaseNotes: true
          makeLatest: true
          replacesArtifacts: true
ENDOFFILE

  git add .github/workflows/build.yml
fi

# Commit the merge
git commit -m "Merge upstream changes from bmax121/APatch

- Incorporate all upstream changes from bmax121/APatch:main
- Resolve conflict in .github/workflows/build.yml:
  - Keep upstream optional signing steps (when SIGNING_KEY secret is set)
  - Keep unconditional artifact uploads as fallback (for unsigned builds)"

echo ""
echo "=== Merge commit created successfully! ==="
echo "Now push to your fork and the PR will auto-close as merged:"
echo "  git push origin main"
echo ""
echo "Or to merge the PR via GitHub CLI:"
echo "  gh pr merge 3 --repo AliShahidF2023-752/APatch --merge"
