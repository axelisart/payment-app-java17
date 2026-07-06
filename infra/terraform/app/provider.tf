# ──────────────────────────────────────────────────────────────────────────────
# provider.tf — app workspace
#
# Provisions the Kubernetes resources (namespaces, quotas, network policies,
# PostgreSQL, RBAC) and GitHub Actions secrets for the payment-app.
#
# Providers:
#   • hashicorp/kubernetes  — OpenShift resources via the Kubernetes API.
#   • integrations/github   — GitHub Actions repository secrets.
#   • hashicorp/null        — local-exec readiness checks.
#
# The cluster API URL is read from the cluster workspace remote state so that
# this workspace does not need to know the URL in advance.
#
# ── How to connect to the cluster ─────────────────────────────────────────────
# Option A (recommended): remote state (configure the backend below and in the
#   cluster workspace, then `terraform init`).
# Option B: set TF_VAR_cluster_url manually (e.g. for local dev with oc login).
# ──────────────────────────────────────────────────────────────────────────────

terraform {
  required_version = ">= 1.9.0"

  required_providers {
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = ">= 3.0.0, < 4.0.0"
    }

    github = {
      source  = "integrations/github"
      version = "~> 6.0"
    }

    null = {
      source  = "hashicorp/null"
      version = ">= 3.2.1, < 4.0.0"
    }
  }

  # ── Remote state backend ─────────────────────────────────────────────────────
  # Uncomment to store this workspace's state remotely.
  # backend "s3" {
  #   bucket                      = "my-tfstate-bucket"
  #   key                         = "payment-app/app/terraform.tfstate"
  #   region                      = "eu-de"
  #   endpoint                    = "https://s3.eu-de.cloud-object-storage.appdomain.cloud"
  #   skip_credentials_validation = true
  #   skip_metadata_api_check     = true
  #   skip_region_validation      = true
  #   force_path_style            = true
  # }
}

# ── Remote state: read cluster outputs ────────────────────────────────────────
# Reads cluster_master_url from the cluster workspace.
# Requires the cluster workspace to use a shared backend (configured above).
# Set var.cluster_url to override (e.g. local dev).
data "terraform_remote_state" "cluster" {
  # Set to false when the cluster workspace backend is not yet configured
  # (e.g. first run, local dev). In that case set var.cluster_url instead.
  count = var.cluster_url == "" ? 1 : 0

  backend = "s3"

  config = {
    bucket                      = var.cluster_state_bucket
    key                         = var.cluster_state_key
    region                      = var.cluster_state_region
    endpoint                    = var.cluster_state_endpoint
    skip_credentials_validation = true
    skip_metadata_api_check     = true
    skip_region_validation      = true
    force_path_style            = true
  }
}

locals {
  # Prefer explicit var.cluster_url; fall back to remote state output.
  resolved_cluster_url = (
    var.cluster_url != ""
    ? var.cluster_url
    : data.terraform_remote_state.cluster[0].outputs.cluster_master_url
  )
}

provider "kubernetes" {
  host     = local.resolved_cluster_url
  token    = var.cluster_token
  insecure = var.cluster_insecure
}

provider "github" {
  token = var.github_token
  owner = var.github_owner
}
