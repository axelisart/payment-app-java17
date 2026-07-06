# ──────────────────────────────────────────────────────────────────────────────
# variables.tf — app workspace
#
# Variables for provisioning the Kubernetes resources and GitHub Actions secrets.
# IBM Cloud / VPC / cluster provisioning variables live in the cluster workspace.
# ──────────────────────────────────────────────────────────────────────────────

# ── Cluster remote state (only needed when var.cluster_url is empty) ───────────

variable "cluster_state_bucket" {
  description = "S3/COS bucket name that holds the cluster workspace Terraform state."
  type        = string
  default     = ""
}

variable "cluster_state_key" {
  description = "Object key (path) of the cluster workspace state file in the bucket."
  type        = string
  default     = "payment-app/cluster/terraform.tfstate"
}

variable "cluster_state_region" {
  description = "Region of the COS bucket (e.g. eu-de)."
  type        = string
  default     = "eu-de"
}

variable "cluster_state_endpoint" {
  description = "S3-compatible endpoint for the COS bucket."
  type        = string
  default     = "https://s3.eu-de.cloud-object-storage.appdomain.cloud"
}

# ── OpenShift cluster connection ───────────────────────────────────────────────

variable "cluster_url" {
  description = <<-EOT
    OpenShift API server URL (e.g. https://api.mycluster.example.com:6443).
    Leave empty to read the URL from the cluster workspace remote state.
  EOT
  type        = string
  sensitive   = true
  default     = ""
}

variable "cluster_token" {
  description = "Service account token used to authenticate with the OpenShift API server."
  type        = string
  sensitive   = true
  # No default — must be supplied via TF_VAR_cluster_token
}

variable "cluster_insecure" {
  description = <<-EOT
    Skip TLS certificate verification for the cluster API server.
    Set to true ONLY for local development against self-signed certificates.
  EOT
  type        = bool
  default     = false
}

# ── GitHub provider ────────────────────────────────────────────────────────────

variable "github_token" {
  description = "GitHub Personal Access Token with the 'repo' scope."
  type        = string
  sensitive   = true
  # No default — must be supplied via TF_VAR_github_token
}

variable "github_owner" {
  description = "GitHub organisation or user that owns the repository."
  type        = string
  # No default — must be supplied via TF_VAR_github_owner
}

variable "github_repository" {
  description = "GitHub repository name (without owner prefix)."
  type        = string
  # No default — must be supplied via TF_VAR_github_repository
}

# ── Namespace names ────────────────────────────────────────────────────────────

variable "namespace_staging" {
  description = "Kubernetes namespace for the staging environment."
  type        = string
  default     = "bob-demo-staging"

  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$", var.namespace_staging))
    error_message = "Namespace name must be a valid DNS label (lowercase alphanumeric and hyphens, 3-63 chars)."
  }
}

variable "namespace_prod" {
  description = "Kubernetes namespace for the production environment."
  type        = string
  default     = "bob-demo-prod"

  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$", var.namespace_prod))
    error_message = "Namespace name must be a valid DNS label (lowercase alphanumeric and hyphens, 3-63 chars)."
  }
}

# ── ResourceQuota — staging ────────────────────────────────────────────────────

variable "quota_staging_requests_cpu" {
  description = "Total CPU requests allowed across all pods in the staging namespace."
  type        = string
  default     = "4"
}

variable "quota_staging_limits_cpu" {
  description = "Total CPU limits allowed across all pods in the staging namespace."
  type        = string
  default     = "8"
}

variable "quota_staging_requests_memory" {
  description = "Total memory requests allowed across all pods in the staging namespace."
  type        = string
  default     = "8Gi"
}

variable "quota_staging_limits_memory" {
  description = "Total memory limits allowed across all pods in the staging namespace."
  type        = string
  default     = "16Gi"
}

# ── ResourceQuota — prod ───────────────────────────────────────────────────────

variable "quota_prod_requests_cpu" {
  description = "Total CPU requests allowed across all pods in the production namespace."
  type        = string
  default     = "4"
}

variable "quota_prod_limits_cpu" {
  description = "Total CPU limits allowed across all pods in the production namespace."
  type        = string
  default     = "8"
}

variable "quota_prod_requests_memory" {
  description = "Total memory requests allowed across all pods in the production namespace."
  type        = string
  default     = "8Gi"
}

variable "quota_prod_limits_memory" {
  description = "Total memory limits allowed across all pods in the production namespace."
  type        = string
  default     = "16Gi"
}

# ── PostgreSQL ─────────────────────────────────────────────────────────────────

variable "postgres_image" {
  description = "PostgreSQL container image."
  type        = string
  default     = "postgres:15.6"
}

variable "postgres_db_staging" {
  description = "PostgreSQL database name for the staging instance."
  type        = string
  default     = "paymentdb_staging"
}

variable "postgres_db_prod" {
  description = "PostgreSQL database name for the production instance."
  type        = string
  default     = "paymentdb_prod"
}

variable "postgres_user" {
  description = "PostgreSQL superuser username."
  type        = string
  default     = "paymentapp"

  validation {
    condition     = length(var.postgres_user) >= 3
    error_message = "Postgres username must be at least 3 characters."
  }
}

variable "postgres_password_staging" {
  description = "PostgreSQL password for the staging instance."
  type        = string
  sensitive   = true
  # No default — must be supplied via TF_VAR_postgres_password_staging
}

variable "postgres_password_prod" {
  description = "PostgreSQL password for the production instance."
  type        = string
  sensitive   = true
  # No default — must be supplied via TF_VAR_postgres_password_prod
}

variable "postgres_pvc_size" {
  description = "Storage size for each PostgreSQL PVC."
  type        = string
  default     = "5Gi"

  validation {
    condition     = can(regex("^[0-9]+(Mi|Gi|Ti)$", var.postgres_pvc_size))
    error_message = "PVC size must be expressed in Mi, Gi, or Ti (e.g. 5Gi)."
  }
}

variable "postgres_storage_class" {
  description = <<-EOT
    StorageClass for the PostgreSQL PVCs.
    Leave empty to use the cluster default. On ROKS typically "ibmc-block-gold" or "gp3-csi".
  EOT
  type        = string
  default     = ""
}

variable "postgres_cpu_request" {
  description = "CPU request for the PostgreSQL pod."
  type        = string
  default     = "250m"
}

variable "postgres_cpu_limit" {
  description = "CPU limit for the PostgreSQL pod."
  type        = string
  default     = "1000m"
}

variable "postgres_memory_request" {
  description = "Memory request for the PostgreSQL pod."
  type        = string
  default     = "256Mi"
}

variable "postgres_memory_limit" {
  description = "Memory limit for the PostgreSQL pod."
  type        = string
  default     = "2Gi"
}

# ── GitHub Actions secrets ─────────────────────────────────────────────────────

variable "openshift_server" {
  description = "OpenShift API server URL stored as a GitHub Actions secret (OPENSHIFT_SERVER)."
  type        = string
  sensitive   = true
  # No default — must be supplied via TF_VAR_openshift_server
}

variable "openshift_token" {
  description = "Service account token stored as a GitHub Actions secret (OPENSHIFT_TOKEN)."
  type        = string
  sensitive   = true
  # No default — must be supplied via TF_VAR_openshift_token
}

variable "openshift_registry" {
  description = <<-EOT
    OpenShift internal image registry hostname stored as a GitHub Actions secret (OPENSHIFT_REGISTRY).
    Obtain with: oc get route default-route -n openshift-image-registry --template='{{ .spec.host }}'
  EOT
  type        = string
  sensitive   = true
  # No default — must be supplied via TF_VAR_openshift_registry
}
