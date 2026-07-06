# ──────────────────────────────────────────────────────────────────────────────
# provider.tf — cluster workspace
#
# Provisions the IBM Cloud VPC and ROKS (OpenShift) cluster.
# This workspace must be applied BEFORE the app workspace.
#
# Providers:
#   • ibm-cloud/ibm  — IBM Cloud resources (VPC, OpenShift cluster).
#
# Workspaces:
#   cluster/  ← this file  — VPC + subnet + ROKS cluster
#   app/                   — Namespaces, Quotas, NetworkPolicies, PostgreSQL
#
# ── Usage ─────────────────────────────────────────────────────────────────────
#   cp .env.example .env && source .env
#   terraform init
#   terraform plan -out=tfplan
#   terraform apply tfplan
# ──────────────────────────────────────────────────────────────────────────────

terraform {
  required_version = ">= 1.9.0"

  required_providers {
    ibm = {
      source  = "ibm-cloud/ibm"
      version = ">= 2.3.0, < 3.0.0"
    }
  }

  # ── Remote state backend ───────────────────────────────────────────────────
  # Uncomment to store this workspace's state in IBM Cloud Object Storage.
  # The app workspace reads cluster_master_url from this state via remote_state.
  # backend "s3" {
  #   bucket                      = "my-tfstate-bucket"
  #   key                         = "payment-app/cluster/terraform.tfstate"
  #   region                      = "eu-de"
  #   endpoint                    = "https://s3.eu-de.cloud-object-storage.appdomain.cloud"
  #   skip_credentials_validation = true
  #   skip_metadata_api_check     = true
  #   skip_region_validation      = true
  #   force_path_style            = true
  # }
}

# ── IBM Cloud provider ─────────────────────────────────────────────────────────
# Authenticates with an IAM API key.
# Set TF_VAR_ibmcloud_api_key in your environment (never hardcode the key).
provider "ibm" {
  ibmcloud_api_key = var.ibmcloud_api_key
  region           = var.region
}
