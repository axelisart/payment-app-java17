# ──────────────────────────────────────────────────────────────────────────────
# github-secrets.tf
#
# Provisions GitHub Actions repository secrets required by the CI/CD pipeline.
#
# These five secrets are read by the workflow YAML files to:
#   • authenticate `oc login` against the OpenShift cluster
#   • push built container images to the OpenShift internal registry
#   • parameterise `oc apply` with the correct namespace and image tag
#
# All secret values are sourced from Terraform input variables which are
# themselves injected from TF_VAR_* environment variables — no plaintext
# credential ever appears in a .tf file or in Terraform state in readable form.
#
# GitHub Actions secrets are encrypted at rest by GitHub using libsodium sealed
# boxes and are never exposed in workflow logs.
#
# ── How to reference these in a workflow ──────────────────────────────────────
#
#   jobs:
#     deploy:
#       steps:
#         - name: Log in to OpenShift
#           run: |
#             oc login --server=${{ secrets.OPENSHIFT_SERVER }} \
#                      --token=${{ secrets.OPENSHIFT_TOKEN }} \
#                      --insecure-skip-tls-verify=false
#
#         - name: Push image
#           run: |
#             docker login ${{ secrets.OPENSHIFT_REGISTRY }} \
#               -u unused \
#               -p $(oc whoami -t)
#             docker push ${{ secrets.OPENSHIFT_REGISTRY }}/${{ secrets.NAMESPACE_STAGING }}/payment-app:${{ github.sha }}
#
# ──────────────────────────────────────────────────────────────────────────────

# ── OPENSHIFT_SERVER ──────────────────────────────────────────────────────────
# The OpenShift API server URL used in all `oc login` and `kubectl` calls from
# the CI/CD pipeline. Example: https://api.mycluster.example.com:6443
resource "github_actions_secret" "openshift_server" {
  repository      = var.github_repository
  secret_name     = "OPENSHIFT_SERVER"
  value = var.openshift_server
}

# ── OPENSHIFT_TOKEN ───────────────────────────────────────────────────────────
# Long-lived service account token for the CI/CD pipeline to authenticate with
# the cluster. Generate with:
#   oc create token ci-sa -n bob-demo-staging --duration=8760h
# Or use a dedicated CI service account bound to the minimum required ClusterRole.
resource "github_actions_secret" "openshift_token" {
  repository      = var.github_repository
  secret_name     = "OPENSHIFT_TOKEN"
  value = var.openshift_token
}

# ── OPENSHIFT_REGISTRY ────────────────────────────────────────────────────────
# External hostname of the OpenShift internal image registry. Required by
# `docker login` and `docker push` steps in the CI workflow.
#
# Obtain the value with:
#   oc get route default-route \
#     -n openshift-image-registry \
#     --template='{{ .spec.host }}'
#
# Typical value:
#   default-route-openshift-image-registry.apps.<cluster-domain>
resource "github_actions_secret" "openshift_registry" {
  repository      = var.github_repository
  secret_name     = "OPENSHIFT_REGISTRY"
  value = var.openshift_registry
}

# ── NAMESPACE_STAGING ─────────────────────────────────────────────────────────
# The staging namespace name. Used in `oc apply` and `docker push` commands
# so that the image is pushed to the staging image stream and the Deployment
# is applied to the staging namespace.
resource "github_actions_secret" "namespace_staging" {
  repository      = var.github_repository
  secret_name     = "NAMESPACE_STAGING"
  value = var.namespace_staging
}

# ── NAMESPACE_PROD ────────────────────────────────────────────────────────────
# The production namespace name. Used in the production deployment job (which
# runs only after the manual approval gate is passed).
resource "github_actions_secret" "namespace_prod" {
  repository      = var.github_repository
  secret_name     = "NAMESPACE_PROD"
  value = var.namespace_prod
}
