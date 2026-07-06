# ──────────────────────────────────────────────────────────────────────────────
# outputs.tf — app workspace
# ──────────────────────────────────────────────────────────────────────────────

# ── Namespace names ────────────────────────────────────────────────────────────

output "namespace_staging" {
  description = "Name of the staging Kubernetes namespace."
  value       = kubernetes_namespace_v1.env["staging"].metadata[0].name
}

output "namespace_prod" {
  description = "Name of the production Kubernetes namespace."
  value       = kubernetes_namespace_v1.env["prod"].metadata[0].name
}

# ── ServiceAccount names ───────────────────────────────────────────────────────

output "service_account_staging" {
  description = "ServiceAccount name in the staging namespace."
  value       = kubernetes_service_account_v1.payment_sa["staging"].metadata[0].name
}

output "service_account_prod" {
  description = "ServiceAccount name in the production namespace."
  value       = kubernetes_service_account_v1.payment_sa["prod"].metadata[0].name
}

# ── PVC names ─────────────────────────────────────────────────────────────────

output "pvc_name_staging" {
  description = "PostgreSQL PVC name in the staging namespace."
  value       = kubernetes_persistent_volume_claim_v1.postgres["staging"].metadata[0].name
}

output "pvc_name_prod" {
  description = "PostgreSQL PVC name in the production namespace."
  value       = kubernetes_persistent_volume_claim_v1.postgres["prod"].metadata[0].name
}

# ── PostgreSQL service endpoints ───────────────────────────────────────────────

output "postgres_service_endpoint_staging" {
  description = "PostgreSQL ClusterIP FQDN for the staging namespace."
  value = format(
    "postgres-service.%s.svc.cluster.local:5432",
    kubernetes_namespace_v1.env["staging"].metadata[0].name
  )
}

output "postgres_service_endpoint_prod" {
  description = "PostgreSQL ClusterIP FQDN for the production namespace."
  value = format(
    "postgres-service.%s.svc.cluster.local:5432",
    kubernetes_namespace_v1.env["prod"].metadata[0].name
  )
}

# ── PostgreSQL ClusterIPs ──────────────────────────────────────────────────────

output "postgres_cluster_ip_staging" {
  description = "ClusterIP of the postgres-service in the staging namespace."
  value       = kubernetes_service_v1.postgres["staging"].spec[0].cluster_ip
  sensitive   = true
}

output "postgres_cluster_ip_prod" {
  description = "ClusterIP of the postgres-service in the production namespace."
  value       = kubernetes_service_v1.postgres["prod"].spec[0].cluster_ip
  sensitive   = true
}

# ── PostgreSQL database names ──────────────────────────────────────────────────

output "postgres_database_staging" {
  description = "PostgreSQL database name for the staging instance."
  value       = var.postgres_db_staging
}

output "postgres_database_prod" {
  description = "PostgreSQL database name for the production instance."
  value       = var.postgres_db_prod
}

# ── GitHub Actions secrets ─────────────────────────────────────────────────────

output "github_secrets_provisioned" {
  description = "List of GitHub Actions secret names provisioned."
  value = [
    github_actions_secret.openshift_server.secret_name,
    github_actions_secret.openshift_token.secret_name,
    github_actions_secret.openshift_registry.secret_name,
    github_actions_secret.namespace_staging.secret_name,
    github_actions_secret.namespace_prod.secret_name,
  ]
}

output "github_repository" {
  description = "GitHub repository where Actions secrets were provisioned."
  value       = format("%s/%s", var.github_owner, var.github_repository)
}

# ── Summary ───────────────────────────────────────────────────────────────────

output "summary" {
  description = "Human-readable summary of all app workspace resources."
  value       = <<-EOT

    ════════════════════════════════════════════════════════
     Payment App — App Workspace Apply Summary
    ════════════════════════════════════════════════════════

    Namespaces
      Staging : ${kubernetes_namespace_v1.env["staging"].metadata[0].name}
      Prod    : ${kubernetes_namespace_v1.env["prod"].metadata[0].name}

    ServiceAccounts
      Staging : ${kubernetes_service_account_v1.payment_sa["staging"].metadata[0].name}
      Prod    : ${kubernetes_service_account_v1.payment_sa["prod"].metadata[0].name}

    PersistentVolumeClaims
      Staging : ${kubernetes_persistent_volume_claim_v1.postgres["staging"].metadata[0].name} (${var.postgres_pvc_size})
      Prod    : ${kubernetes_persistent_volume_claim_v1.postgres["prod"].metadata[0].name} (${var.postgres_pvc_size})

    PostgreSQL Services (ClusterIP — internal only)
      Staging : postgres-service.${var.namespace_staging}.svc.cluster.local:5432
      Prod    : postgres-service.${var.namespace_prod}.svc.cluster.local:5432

    GitHub Actions Secrets (${var.github_owner}/${var.github_repository})
      OPENSHIFT_SERVER    ✔
      OPENSHIFT_TOKEN     ✔
      OPENSHIFT_REGISTRY  ✔
      NAMESPACE_STAGING   ✔
      NAMESPACE_PROD      ✔

    Next steps
      1. Run Ansible playbook to inject postgres-credentials Secret
         and payment-app-config ConfigMap into each namespace.
      2. Trigger CI pipeline — image will be pushed to staging and deployed.

    ════════════════════════════════════════════════════════
  EOT
}
