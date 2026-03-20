# DevOps Guide — reports-scheduler

> **Purpose**: Single source of truth for the build, release, and deployment process.
> This document covers how the pipeline works, what rules to follow, what must be
> set up at the organization level, and how to operate each environment safely.

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Versioning Strategy — The Foundation](#2-versioning-strategy--the-foundation)
3. [Image Tagging Rules](#3-image-tagging-rules)
4. [Pipeline — How It Works End to End](#4-pipeline--how-it-works-end-to-end)
5. [Environment Rules & Isolation](#5-environment-rules--isolation)
6. [Deploying to Each Environment](#6-deploying-to-each-environment)
7. [Feature Branch Deployments](#7-feature-branch-deployments)
8. [Helm Chart — What Goes Where](#8-helm-chart--what-goes-where)
9. [Artifactory — Setup & Housekeeping](#9-artifactory--setup--housekeeping)
10. [AKS Cluster — One-Time Setup](#10-aks-cluster--one-time-setup)
11. [GitLab — Required Configuration](#11-gitlab--required-configuration)
12. [Monitoring & Operations](#12-monitoring--operations)
13. [Troubleshooting](#13-troubleshooting)
14. [Release Checklist](#14-release-checklist)
15. [Rollback Procedures](#15-rollback-procedures)
16. [Organization-Level Prerequisites](#16-organization-level-prerequisites)

---

## 1. Architecture Overview

```
Developer → GitLab CI → Build JAR → Docker Image → Artifactory → Helm → AKS CronJob
```

Two Kubernetes CronJobs per namespace (each a short-lived pod):

| CronJob | Schedule (Europe/Zurich) | Purpose |
|---------|--------------------------|---------|
| `reports-scheduler-reports` | Hourly, Mon–Sat | Data extraction, transformation, report generation |
| `reports-scheduler-delete` | Daily 7:00 AM | Data cleanup |

Each invocation: pod starts → Spring Batch job runs → pod exits with status code.

---

## 2. Versioning Strategy — The Foundation

### 2.1 Single Source of Truth: Git Tags

Version numbers come from **Git tags only**. There is no version file to manually maintain for releases.

```
Source of truth:   git tag v1.2.0
       ↓
Maven JAR:         reports-scheduler-1.2.0.jar
Docker image:      artifactory.example.com/docker-local/reports-scheduler:1.2.0
Helm chart:        appVersion: 1.2.0
```

All three artifacts derive their version from the same Git tag. This guarantees traceability:
**given any running pod, you can trace back to the exact Git commit.**

### 2.2 Semantic Versioning (SemVer)

Follow [semver.org](https://semver.org) strictly:

| Change Type | Version Bump | Example |
|-------------|-------------|---------|
| Bugfix, minor patch | PATCH | `1.2.0` → `1.2.1` |
| New feature, backward compatible | MINOR | `1.2.1` → `1.3.0` |
| Breaking change | MAJOR | `1.3.0` → `2.0.0` |

### 2.3 SNAPSHOT vs Release

| Context | Maven Version | Image Tag | Where it runs |
|---------|--------------|-----------|---------------|
| Developer working on `main` | `1.3.0-SNAPSHOT` | `main-abc1234` | dev only |
| Developer on feature branch | `1.3.0-SNAPSHOT` | `feature-xyz-abc1234` | dev only (manual) |
| Tagged release `v1.3.0` | `1.3.0` | `1.3.0` | dev, test, prod |

### 2.4 Golden Rules

| Rule | Why |
|------|-----|
| **Never reuse a Git tag** | A tag = one immutable artifact. Reusing tags breaks traceability. |
| **Never overwrite an image in Artifactory** | If `1.2.0` exists, it must remain unchanged forever. |
| **Never use `latest` tag for deployments** | `latest` is ambiguous — you can't tell what's running. |
| **Even a 1-line fix gets a new version** | `v1.2.0` → `v1.2.1`. Always forward, never rewrite. |
| **SNAPSHOT images never go to test/prod** | Only SemVer-tagged releases are promotable. |

---

## 3. Image Tagging Rules

| Source | Image Tag Pattern | Example | Promotable? | Retention |
|--------|-------------------|---------|-------------|-----------|
| `main` branch | `main-<commit-sha>` | `main-abc1234` | No (dev only) | Keep last 10 |
| Feature branch | `<branch-slug>-<commit-sha>` | `feature-add-report-abc1234` | No (dev only) | Delete after 7 days |
| Git tag `v1.2.0` | `<semver>` | `1.2.0` | **Yes** | Keep forever |

**Only SemVer-tagged images (`1.2.0`, `1.3.1`, etc.) are eligible for test and production.**

---

## 4. Pipeline — How It Works End to End

### 4.1 Pipeline Stages

```
┌─────────┐   ┌──────┐   ┌─────────┐   ┌─────────┐   ┌──────────┐
│  build  │ → │ test │ → │ package │ → │ publish │ → │  deploy  │
│ (Maven) │   │(Unit)│   │(Docker) │   │(Artif.) │   │  (Helm)  │
└─────────┘   └──────┘   └─────────┘   └─────────┘   └──────────┘
```

### 4.2 What Triggers What

| Trigger | build | test | package | publish | deploy-dev | deploy-test | deploy-prod |
|---------|:-----:|:----:|:-------:|:-------:|:----------:|:-----------:|:-----------:|
| Feature branch push | Auto | Auto | Manual | Manual | Manual | — | — |
| Merge to `main` | Auto | Auto | Auto | Auto | **Auto** | — | — |
| Git tag `v1.2.0` | Auto | Auto | Auto | Auto | — | **Manual** | **Manual** |

**Key principle**: Test and prod deployments are _always_ manual and _always_ from a Git tag.

### 4.3 What Happens at Each Stage

**build**: Compiles `reports-scheduler.jar` using Maven. The `${revision}` property is set from the Git tag (release) or pom.xml (snapshot).

**test**: Runs unit/integration tests. Pipeline stops here if tests fail — no image is ever built from broken code.

**package**: Builds the Docker image using multi-stage Dockerfile. Image runs as non-root user on JRE-only base (eclipse-temurin:21-jre-alpine).

**publish**: Pushes the Docker image to Artifactory. Requires `ARTIFACTORY_USER` and `ARTIFACTORY_PASSWORD` CI variables.

**deploy**: Runs `helm upgrade --install` with environment-specific values. The image tag is passed via `--set image.tag=<version>` — never hardcoded in values files.

---

## 5. Environment Rules & Isolation

### 5.1 Environment → Namespace Mapping

| Environment | K8s Namespace | Values File | Who Deploys | Approval |
|-------------|---------------|-------------|-------------|----------|
| dev | `reports-scheduler-dev` | `values-dev.yaml` | CI (auto) | None |
| test | `reports-scheduler-test` | `values-test.yaml` | DevOps (manual) | None |
| prod | `reports-scheduler-prod` | `values-prod.yaml` | DevOps (manual) | Recommended |

### 5.2 Environment Isolation — How It Works

Each environment is a **completely independent** Helm release in its own K8s namespace:

```
Namespace: reports-scheduler-dev   → image: main-abc1234  (latest from main)
Namespace: reports-scheduler-test  → image: 1.2.0          (last promoted release)
Namespace: reports-scheduler-prod  → image: 1.1.0          (current production)
```

- Deploying to dev **never** affects test or prod
- Deploying to test **never** affects prod
- Each `helm upgrade` targets a specific namespace via `-f values-{env}.yaml`
- The `--set image.tag=` flag is the **only** thing that determines which version runs

### 5.3 How Versions Move Through Environments

```
Developer merges to main
    → CI auto-deploys main-abc1234 to dev
    → dev is always the latest main branch code

Developer tags v1.3.0 and pushes
    → CI builds & publishes image 1.3.0 to Artifactory
    → DevOps clicks "deploy-test" → 1.3.0 goes to test
    → QA validates in test
    → DevOps clicks "deploy-prod" → 1.3.0 goes to prod
```

**At any point, different versions can run in different environments.** This is normal and expected.

### 5.4 How to Know What's Running Where

**Option A: GitLab Environments page** (recommended)
GitLab → Deployments → Environments → shows current version per environment with deploy timestamps.

**Option B: kubectl**
```bash
kubectl get cronjob reports-scheduler-reports -n reports-scheduler-test \
  -o jsonpath='{.spec.jobTemplate.spec.template.spec.containers[0].image}'
# → artifactory.example.com/docker-local/reports-scheduler:1.2.0
```

**Option C: Helm**
```bash
helm list -n reports-scheduler-prod
# shows chart version and app version
```

No spreadsheet or manual tracking needed.

---

## 6. Deploying to Each Environment

### 6.1 Dev (automatic)

Merge to `main` → pipeline auto-deploys. **No action required.**

### 6.2 Test (manual promotion)

1. Go to GitLab → CI/CD → Pipelines
2. Find the **tag pipeline** (e.g., `v1.2.0`)
3. Click the ▶ **play** button on `deploy-test`
4. The job deploys image `1.2.0` to `reports-scheduler-test` namespace
5. Verify:
   ```bash
   kubectl get cronjobs -n reports-scheduler-test
   ```

### 6.3 Prod (manual promotion)

Same as test — click ▶ on `deploy-prod` in the tag pipeline.

### 6.4 Deploying a Specific Version (manual override)

If you need to deploy a version outside the normal pipeline flow:

```bash
helm upgrade --install reports-scheduler helm/ \
  -f helm/values.yaml \
  -f helm/values-test.yaml \
  --set image.tag=1.1.0 \
  --namespace reports-scheduler-test
```

This is useful for rollbacks or deploying an older version.

---

## 7. Feature Branch Deployments

Feature branches can be deployed to **dev only**, via manual triggers.

### 7.1 How It Works

| Stage | Feature Branch |
|-------|---------------|
| build | Auto |
| test | Auto |
| package | Manual (click play) |
| publish | Manual (click play) |
| deploy-dev | Manual (click play) |

### 7.2 Image Tag for Feature Branches

```
Branch: feature/add-monthly-report
Image:  feature-add-monthly-repor-abc1234   (branch slug truncated to 20 chars + SHA)
```

### 7.3 Why Manual?

- Avoids flooding Artifactory with throwaway images
- Most feature branch pushes only need build + test feedback
- Developer explicitly decides when they need to test in the K8s environment

### 7.4 Artifact Cleanup

Feature branch images are **ephemeral**. They should be cleaned up by Artifactory retention policies (see Section 9). If retention policies are not yet configured, these images will accumulate — set up cleanup rules as a priority.

---

## 8. Helm Chart — What Goes Where

### 8.1 File Responsibilities

```
helm/
├── Chart.yaml              # Chart name, version, appVersion
├── values.yaml             # Defaults: schedules, pull secrets, job structure
├── values-dev.yaml         # Dev: namespace, resources, log level, JVM opts
├── values-test.yaml        # Test: namespace, resources, log level, JVM opts
├── values-prod.yaml        # Prod: namespace, resources, log level, JVM opts
└── templates/
    ├── _helpers.tpl         # Shared labels, image ref, SA name
    ├── cronjob-reports.yaml # CronJob spec for reports
    ├── cronjob-delete.yaml  # CronJob spec for delete
    ├── configmap.yaml       # Environment config (profile, log level)
    └── serviceaccount.yaml  # K8s service account
```

### 8.2 What Goes in Which File

| Configuration | File | How Often It Changes |
|---------------|------|---------------------|
| Cron schedules, job names, pull secret names | `values.yaml` | Rarely (structural changes) |
| Namespace, CPU/memory, JVM opts, log level | `values-{env}.yaml` | Occasionally (tuning) |
| Image tag | `--set image.tag=` at deploy time | **Every deployment** (never in a file) |
| Spring profile, env vars | `configmap.yaml` via `values-{env}.yaml` | Rarely |

### 8.3 Why Image Tag Is Never in values-{env}.yaml

If the image tag were hardcoded in `values-test.yaml`, you would need to:
1. Edit the file
2. Commit, push, get it reviewed
3. Then deploy

Instead, the tag is passed as a Helm `--set` override, making promotions a **one-click operation** in GitLab.

---

## 9. Artifactory — Setup & Housekeeping

### 9.1 Repository Structure (to be created if not exists)

| Repository | Type | Purpose |
|------------|------|---------|
| `docker-local` | Docker (local) | Store built images |
| `docker-remote` | Docker (remote) | Proxy to Docker Hub for base images |
| `docker-virtual` | Docker (virtual) | Aggregates local + remote |

### 9.2 Retention / Cleanup Policies — CRITICAL

**If your organization does not have Artifactory cleanup policies, set them up immediately.**
Without cleanup, the Docker repository will grow unbounded.

Recommended policy (configure in Artifactory → Administration → Artifactory → Cleanup Policies):

| Image Tag Pattern | Rule | Rationale |
|-------------------|------|-----------|
| `main-*` | Keep last 10 images | Dev snapshots; only the recent ones matter |
| Feature branch (`*-*` excluding `main-*` and SemVer) | Delete after 7 days | Throwaway; never promoted |
| SemVer (`X.Y.Z` pattern) | **Keep forever** | Release artifacts; must be traceable and reproducible |
| `latest` | Keep 1 (or don't use) | Convenience only; never used for deployments |

**How to implement in JFrog Artifactory:**

1. Go to Administration → Artifactory → Cleanup Policies
2. Create policy: `cleanup-dev-snapshots`
   - Repos: `docker-local`
   - Include: `reports-scheduler/main-*`
   - Keep: most recent 10 by date
3. Create policy: `cleanup-feature-branches`
   - Repos: `docker-local`
   - Include: `reports-scheduler/*-*` (exclude `main-*` and SemVer patterns)
   - Delete: older than 7 days
4. Schedule: Run daily

**If JFrog Artifactory is not available**, an alternative is to run a scheduled pipeline job:
```bash
# Example: GitLab scheduled pipeline that cleans old feature branch images
# using Artifactory REST API or AQL queries
```

### 9.3 Immutable Image Policy

Configure the `docker-local` repository to **prevent tag overwrites**:

Artifactory → Repositories → docker-local → Advanced → **"Block tag overwriting"** = Yes

This ensures that once `1.2.0` is published, it can never be replaced. This is essential for auditability.

### 9.4 Access Control

| Principal | Permission | Scope |
|-----------|-----------|-------|
| CI/CD service account | Push + Pull | `docker-local/reports-scheduler/**` |
| AKS pull secret | Pull only | `docker-local/reports-scheduler/**` |
| Developers | Pull only (no push) | `docker-local/**` |

Developers should never push images manually. All images come through CI/CD.

---

## 10. AKS Cluster — One-Time Setup

Run these commands **once per environment** before the first deployment.

### 10.1 Create Namespaces

```bash
kubectl create namespace reports-scheduler-dev
kubectl create namespace reports-scheduler-test
kubectl create namespace reports-scheduler-prod
```

### 10.2 Create Artifactory Pull Secrets

```bash
# Dev
kubectl create secret docker-registry artifactory-pull-secret \
  --docker-server=artifactory.example.com \
  --docker-username=<svc-account> \
  --docker-password=<password> \
  -n reports-scheduler-dev

# Test
kubectl create secret docker-registry artifactory-pull-secret \
  --docker-server=artifactory.example.com \
  --docker-username=<svc-account> \
  --docker-password=<password> \
  -n reports-scheduler-test

# Prod
kubectl create secret docker-registry artifactory-pull-secret \
  --docker-server=artifactory.example.com \
  --docker-username=<svc-account> \
  --docker-password=<password> \
  -n reports-scheduler-prod
```

### 10.3 RBAC (if cluster uses RBAC)

Ensure the service account used by Helm/GitLab has permission to create CronJobs, ConfigMaps, and ServiceAccounts in the target namespaces.

### 10.4 Verify K8s Version

CronJob `timeZone` field requires **Kubernetes 1.27+**. Verify:
```bash
kubectl version --short
```

---

## 11. GitLab — Required Configuration

### 11.1 CI/CD Variables

Set in GitLab → Settings → CI/CD → Variables:

| Variable | Value | Protected | Masked | Notes |
|----------|-------|-----------|--------|-------|
| `ARTIFACTORY_REGISTRY` | `artifactory.example.com` | No | No | Your Artifactory hostname |
| `ARTIFACTORY_USER` | `svc-reports-ci` | Yes | No | CI service account username |
| `ARTIFACTORY_PASSWORD` | `<token>` | Yes | Yes | CI service account password/API key |
| `KUBECONFIG` | `<base64 kubeconfig>` | Yes | Yes | Or use GitLab K8s Agent instead |

Mark `ARTIFACTORY_PASSWORD` and `KUBECONFIG` as **protected + masked** to prevent exposure in logs.

### 11.2 Protected Tags

GitLab → Settings → Repository → Protected Tags:

| Tag Pattern | Allowed to Create |
|-------------|------------------|
| `v*` | Maintainers only |

This prevents non-maintainers from creating release tags (and triggering production-eligible builds).

### 11.3 Environments

GitLab → Deployments → Environments: Create `dev`, `test`, `prod`. The pipeline references these by name, and GitLab will track which version is deployed to each.

### 11.4 Merge Request Settings (recommended)

| Setting | Value | Why |
|---------|-------|-----|
| Pipeline must succeed | Yes | No broken code reaches main |
| Approvals required | 1+ | Code review before merge |
| Delete source branch | Yes | Clean up feature branches |

---

## 12. Monitoring & Operations

### 12.1 Viewing CronJobs

```bash
# All CronJobs in an environment
kubectl get cronjobs -n reports-scheduler-prod

# Recent job runs (sorted by creation time)
kubectl get jobs -n reports-scheduler-prod --sort-by=.metadata.creationTimestamp

# Pods from recent jobs
kubectl get pods -n reports-scheduler-prod --sort-by=.metadata.creationTimestamp
```

### 12.2 Viewing Logs

```bash
# Logs from the most recent reports job
kubectl logs job/reports-scheduler-reports-<timestamp> -n reports-scheduler-prod

# Logs from the most recent delete job  
kubectl logs job/reports-scheduler-delete-<timestamp> -n reports-scheduler-prod
```

### 12.3 Manually Triggering a CronJob

```bash
# Trigger reports job now (useful for testing or re-runs)
kubectl create job --from=cronjob/reports-scheduler-reports manual-reports-$(date +%s) \
  -n reports-scheduler-dev

# Trigger with custom businessDate
kubectl create job --from=cronjob/reports-scheduler-reports manual-reports-$(date +%s) \
  -n reports-scheduler-dev \
  --dry-run=client -o yaml | \
  sed 's/BUSINESS_DATE" *$/&\n              value: "2026-03-15"/' | \
  kubectl apply -f -
```

### 12.4 Suspending a CronJob (emergency)

```bash
# Stop the CronJob from creating new pods (existing running pods continue)
kubectl patch cronjob reports-scheduler-reports -n reports-scheduler-prod \
  -p '{"spec":{"suspend":true}}'

# Resume
kubectl patch cronjob reports-scheduler-reports -n reports-scheduler-prod \
  -p '{"spec":{"suspend":false}}'
```

---

## 13. Troubleshooting

| Symptom | Diagnosis | Fix |
|---------|-----------|-----|
| CronJob not firing | `kubectl describe cronjob <name>` — check schedule, timezone, suspend flag | Fix schedule or set `suspend: false` |
| Pod `ImagePullBackOff` | Pull secret missing or expired, or image tag doesn't exist in Artifactory | Recreate pull secret; verify image exists: `docker pull <image>` |
| Pod `CrashLoopBackOff` | Java/Spring error at startup — check logs | `kubectl logs <pod>` — fix config or code |
| Job stuck / running too long | `activeDeadlineSeconds` not reached yet, or job is genuinely slow | Check pod logs; increase `activeDeadlineSeconds` in values if legitimate |
| Job `BackoffLimitExceeded` | Failed 3 times (default `backoffLimit`) | Check logs of all 3 pods; fix root cause; manually trigger re-run |
| Helm deploy fails | Wrong namespace, missing RBAC, or values error | `helm upgrade --install --debug --dry-run` to see rendered templates |
| Wrong version running | Stale Helm release | Verify with `kubectl get cronjob -o jsonpath='{..image}'`; redeploy with correct `--set image.tag=` |

---

## 14. Release Checklist

### For a Regular Release

```
[ ] 1. Developer creates git tag: git tag v1.3.0 && git push origin v1.3.0
[ ] 2. Verify pipeline: GitLab → Pipelines → tag pipeline passes build/test/package/publish
[ ] 3. Verify image in Artifactory: docker-local/reports-scheduler:1.3.0 exists
[ ] 4. Deploy to test: Click ▶ deploy-test in tag pipeline
[ ] 5. Verify test: kubectl get cronjobs -n reports-scheduler-test
[ ] 6. QA validates in test environment
[ ] 7. Deploy to prod: Click ▶ deploy-prod in tag pipeline
[ ] 8. Verify prod: kubectl get cronjobs -n reports-scheduler-prod
[ ] 9. Developer bumps pom.xml: <revision>1.4.0-SNAPSHOT</revision>
[ ] 10. Developer commits: "Start 1.4.0 development"
```

### For a Hotfix

```
[ ] 1. Fix is on main branch (already merged)
[ ] 2. Create patch tag: git tag v1.3.1 && git push origin v1.3.1
[ ] 3. Pipeline builds 1.3.1
[ ] 4. Deploy 1.3.1 directly to the affected environment
[ ] 5. Bump pom.xml SNAPSHOT if needed
```

---

## 15. Rollback Procedures

### Rolling Back via Helm

```bash
# See release history
helm history reports-scheduler -n reports-scheduler-prod

# Rollback to previous release
helm rollback reports-scheduler 1 -n reports-scheduler-prod
```

### Rolling Back to a Specific Version

```bash
# Deploy a known-good version
helm upgrade --install reports-scheduler helm/ \
  -f helm/values.yaml \
  -f helm/values-prod.yaml \
  --set image.tag=1.1.0 \
  --namespace reports-scheduler-prod
```

Because all images are immutable in Artifactory, every past release is always available for rollback.

---

## 16. Organization-Level Prerequisites

> **If any of these are not in place, they must be set up before this project can be
> operated safely in production.**

### Must Have (blocking)

| Requirement | Owner | Status |
|-------------|-------|--------|
| Artifactory Docker repository (`docker-local`) | DevOps/Platform | ☐ |
| Artifactory CI service account (push access) | DevOps/Platform | ☐ |
| AKS cluster with K8s 1.27+ (for CronJob timeZone) | Platform | ☐ |
| AKS namespaces created (dev, test, prod) | DevOps | ☐ |
| Artifactory pull secrets in each namespace | DevOps | ☐ |
| GitLab CI/CD variables configured | DevOps | ☐ |
| GitLab protected tags (`v*` → Maintainers only) | Tech Lead | ☐ |

### Should Have (important, set up within first sprint)

| Requirement | Owner | Status |
|-------------|-------|--------|
| Artifactory cleanup/retention policies | DevOps/Platform | ☐ |
| Artifactory immutable tag policy (block overwrites) | DevOps/Platform | ☐ |
| GitLab merge request pipeline-must-succeed rule | Tech Lead | ☐ |
| GitLab environment tracking (dev/test/prod) | DevOps | ☐ |
| RBAC: restrict Helm deploy permissions per namespace | Platform | ☐ |
| Prod deploy approval gate in GitLab | Tech Lead | ☐ |

### Nice to Have (mature organization)

| Requirement | Owner | Status |
|-------------|-------|--------|
| Helm chart stored in Artifactory Helm repo | DevOps | ☐ |
| Image vulnerability scanning (Artifactory Xray or GitLab SAST) | Security | ☐ |
| Centralized logging (ELK, Splunk, Azure Monitor) | Platform | ☐ |
| Alerting on CronJob failures (Prometheus/Grafana) | Platform | ☐ |
| GitOps (ArgoCD/FluxCD) instead of pipeline-driven deploy | Platform | ☐ |
