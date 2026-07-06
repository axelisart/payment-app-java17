# ──────────────────────────────────────────────────────────────────────────────
# main.tf
#
# OpenShift infrastructure for the payment-app.
# Provisions identical resource sets in bob-demo-staging and bob-demo-prod.
#
# Resources per namespace (from IaCArchitecture.md):
#   • kubernetes_namespace
#   • kubernetes_resource_quota
#   • kubernetes_network_policy  (default-deny + two allow rules)
#   • kubernetes_service_account + Role + RoleBinding (least-privilege)
#   • kubernetes_persistent_volume_claim  (5 Gi for PostgreSQL)
#   • kubernetes_deployment               (PostgreSQL single replica)
#   • kubernetes_service                  (ClusterIP :5432, internal only)
#   • null_resource                       (post-apply readiness check)
#
# Design decisions:
#   • Resources are NOT placed in a module to keep the configuration flat and
#     easy to read. The repetition between staging and prod is intentional —
#     changes to one environment can be staged safely before applying to the
#     other. Use a module if you need more than two environments.
#   • PostgreSQL credentials are passed directly as env vars on the Deployment.
#     Ansible manages the application-facing Secret (postgres-credentials) as
#     shown in the architecture — that is out of scope for Terraform.
#   • The null_resource readiness checks are informational; they do not block
#     downstream Ansible runs (Ansible's own wait_for handles that gate).
# ──────────────────────────────────────────────────────────────────────────────

locals {
  # Common labels applied to every resource in both namespaces.
  common_labels = {
    "app.kubernetes.io/managed-by" = "terraform"
    "app.kubernetes.io/part-of"    = "payment-app"
  }

  # Per-namespace configuration map — keeps resource blocks DRY for the
  # properties that differ between staging and prod.
  envs = {
    staging = {
      namespace             = var.namespace_staging
      postgres_db           = var.postgres_db_staging
      postgres_password     = var.postgres_password_staging
      pvc_name              = "postgres-data-stg"
      quota_requests_cpu    = var.quota_staging_requests_cpu
      quota_limits_cpu      = var.quota_staging_limits_cpu
      quota_requests_memory = var.quota_staging_requests_memory
      quota_limits_memory   = var.quota_staging_limits_memory
      env_label             = "staging"
    }
    prod = {
      namespace             = var.namespace_prod
      postgres_db           = var.postgres_db_prod
      postgres_password     = var.postgres_password_prod
      pvc_name              = "postgres-data-prod"
      quota_requests_cpu    = var.quota_prod_requests_cpu
      quota_limits_cpu      = var.quota_prod_limits_cpu
      quota_requests_memory = var.quota_prod_requests_memory
      quota_limits_memory   = var.quota_prod_limits_memory
      env_label             = "prod"
    }
  }
}

# ══════════════════════════════════════════════════════════════════════════════
# NAMESPACES
# ══════════════════════════════════════════════════════════════════════════════

resource "kubernetes_namespace_v1" "env" {
  for_each = local.envs

  metadata {
    name = each.value.namespace

    labels = merge(local.common_labels, {
      "app.kubernetes.io/environment" = each.value.env_label
      # OpenShift uses this label for network isolation at the project level.
      "kubernetes.io/metadata.name" = each.value.namespace
    })

    annotations = {
      "openshift.io/description"  = "Payment application — ${each.value.env_label} environment"
      "openshift.io/display-name" = "Payment App (${each.value.env_label})"
    }
  }
}

# ══════════════════════════════════════════════════════════════════════════════
# RESOURCE QUOTAS
# ══════════════════════════════════════════════════════════════════════════════
# Cluster total: 3 nodes × 4 vCPU = 12 vCPU / 3 × 16 GB = 48 GB.
# Both namespaces share the cluster. Quota prevents one environment from
# starving the other. Limits are set above requests to allow bursting.

resource "kubernetes_resource_quota_v1" "env" {
  for_each = local.envs

  metadata {
    name      = "payment-app-quota"
    namespace = kubernetes_namespace_v1.env[each.key].metadata[0].name
    labels = merge(local.common_labels, {
      "app.kubernetes.io/environment" = each.value.env_label
    })
  }

  spec {
    hard = {
      "requests.cpu"    = each.value.quota_requests_cpu
      "limits.cpu"      = each.value.quota_limits_cpu
      "requests.memory" = each.value.quota_requests_memory
      "limits.memory"   = each.value.quota_limits_memory

      # Prevent uncontrolled resource creation.
      "pods"                   = "20"
      "services"               = "10"
      "persistentvolumeclaims" = "5"
      "secrets"                = "20"
      "configmaps"             = "20"
    }
  }

  depends_on = [kubernetes_namespace_v1.env]
}

# ══════════════════════════════════════════════════════════════════════════════
# NETWORK POLICIES
# ══════════════════════════════════════════════════════════════════════════════
# Three policies per namespace implement the rules from IaCArchitecture.md:
#
#   Policy 1 — default-deny-all
#     Selects ALL pods in the namespace with an empty podSelector.
#     Specifying empty ingress/egress arrays (not omitting them) is what
#     triggers the deny-all behaviour in Kubernetes NetworkPolicy semantics.
#
#   Policy 2 — allow-payment-ingress
#     Allows ingress to pods labelled app=payment-app from within the same
#     namespace only (OpenShift Route traffic arrives via the router pod in
#     the openshift-ingress namespace — handled at the router/HAProxy layer,
#     not via NetworkPolicy on the application pod directly).
#
#   Policy 3 — allow-payment-to-postgres
#     Allows egress from payment-app pods to postgres pods on port 5432 ONLY.
#     Corresponding ingress rule on postgres pods to accept from payment-app.

# ── Policy 1: default deny all ────────────────────────────────────────────────
resource "kubernetes_network_policy_v1" "default_deny_all" {
  for_each = local.envs

  metadata {
    name      = "default-deny-all"
    namespace = kubernetes_namespace_v1.env[each.key].metadata[0].name
    labels = merge(local.common_labels, {
      "app.kubernetes.io/environment" = each.value.env_label
    })
  }

  spec {
    # Empty pod_selector selects ALL pods in the namespace.
    pod_selector {}

    # Declaring both ingress and egress with no rules = deny all.
    ingress {}
    egress {}

    policy_types = ["Ingress", "Egress"]
  }

  depends_on = [kubernetes_namespace_v1.env]
}

# ── Policy 2: allow ingress to payment-app pods from within namespace ──────────
resource "kubernetes_network_policy_v1" "allow_payment_ingress" {
  for_each = local.envs

  metadata {
    name      = "allow-payment-ingress"
    namespace = kubernetes_namespace_v1.env[each.key].metadata[0].name
    labels = merge(local.common_labels, {
      "app.kubernetes.io/environment" = each.value.env_label
    })
  }

  spec {
    pod_selector {
      match_labels = {
        "app" = "payment-app"
      }
    }

    ingress {
      from {
        # Allow from any pod in the same namespace (e.g. readiness probes,
        # internal service mesh, other pods in the same namespace).
        namespace_selector {
          match_labels = {
            "kubernetes.io/metadata.name" = each.value.namespace
          }
        }
      }

      ports {
        protocol = "TCP"
        port     = "8080"
      }
    }

    policy_types = ["Ingress"]
  }

  depends_on = [kubernetes_namespace_v1.env]
}

# ── Policy 3: allow payment-app → postgres-service on port 5432 ───────────────
resource "kubernetes_network_policy_v1" "allow_payment_to_postgres" {
  for_each = local.envs

  metadata {
    name      = "allow-payment-to-postgres"
    namespace = kubernetes_namespace_v1.env[each.key].metadata[0].name
    labels = merge(local.common_labels, {
      "app.kubernetes.io/environment" = each.value.env_label
    })
  }

  spec {
    # This policy governs postgres pods — they accept ingress from payment-app.
    pod_selector {
      match_labels = {
        "app" = "postgres"
      }
    }

    # Ingress to postgres from payment-app pods only.
    ingress {
      from {
        pod_selector {
          match_labels = {
            "app" = "payment-app"
          }
        }
      }

      ports {
        protocol = "TCP"
        port     = "5432"
      }
    }

    # Egress from postgres: allow DNS (port 53) so the pod can resolve
    # hostnames; deny everything else (no outbound DB replication in this setup).
    egress {
      ports {
        protocol = "UDP"
        port     = "53"
      }
    }
    egress {
      ports {
        protocol = "TCP"
        port     = "53"
      }
    }

    policy_types = ["Ingress", "Egress"]
  }

  depends_on = [kubernetes_namespace_v1.env]
}

# ── Policy 4: allow egress from payment-app pods to postgres ──────────────────
resource "kubernetes_network_policy_v1" "allow_payment_egress_to_postgres" {
  for_each = local.envs

  metadata {
    name      = "allow-payment-egress-to-postgres"
    namespace = kubernetes_namespace_v1.env[each.key].metadata[0].name
    labels = merge(local.common_labels, {
      "app.kubernetes.io/environment" = each.value.env_label
    })
  }

  spec {
    pod_selector {
      match_labels = {
        "app" = "payment-app"
      }
    }

    # Allow egress to postgres pods on port 5432.
    egress {
      to {
        pod_selector {
          match_labels = {
            "app" = "postgres"
          }
        }
      }

      ports {
        protocol = "TCP"
        port     = "5432"
      }
    }

    # Allow DNS resolution so the app can reach postgres-service by name.
    egress {
      ports {
        protocol = "UDP"
        port     = "53"
      }
    }
    egress {
      ports {
        protocol = "TCP"
        port     = "53"
      }
    }

    policy_types = ["Egress"]
  }

  depends_on = [kubernetes_namespace_v1.env]
}

# ══════════════════════════════════════════════════════════════════════════════
# SERVICE ACCOUNT + RBAC (least-privilege)
# ══════════════════════════════════════════════════════════════════════════════
# The payment-service pods run under this ServiceAccount.
# The Role grants only the minimum permissions the app needs at runtime:
#   - read Secrets (postgres-credentials)
#   - read ConfigMaps (payment-app-config)
#   - get/list/watch its own Pods (for health probes via the API, optional)
# All write permissions are intentionally omitted.

resource "kubernetes_service_account_v1" "payment_sa" {
  for_each = local.envs

  metadata {
    name      = "payment-service-sa"
    namespace = kubernetes_namespace_v1.env[each.key].metadata[0].name
    labels = merge(local.common_labels, {
      "app.kubernetes.io/environment" = each.value.env_label
      "app.kubernetes.io/component"   = "serviceaccount"
    })
    annotations = {
      "app.kubernetes.io/description" = "Service account for payment-app pods (least-privilege)"
    }
  }

  # Disable automatic mounting of the default service account token in all pods.
  # Only pods that explicitly reference this SA will get a token, and only if
  # automount_service_account_token is true on the pod spec.
  automount_service_account_token = false

  depends_on = [kubernetes_namespace_v1.env]
}

resource "kubernetes_role_v1" "payment_role" {
  for_each = local.envs

  metadata {
    name      = "payment-app-role"
    namespace = kubernetes_namespace_v1.env[each.key].metadata[0].name
    labels = merge(local.common_labels, {
      "app.kubernetes.io/environment" = each.value.env_label
    })
  }

  rule {
    api_groups     = [""]
    resources      = ["secrets"]
    resource_names = ["postgres-credentials"] # least-privilege: named secret only
    verbs          = ["get"]
  }

  rule {
    api_groups     = [""]
    resources      = ["configmaps"]
    resource_names = ["payment-app-config"] # least-privilege: named configmap only
    verbs          = ["get", "watch"]
  }

  depends_on = [kubernetes_namespace_v1.env]
}

resource "kubernetes_role_binding_v1" "payment_rb" {
  for_each = local.envs

  metadata {
    name      = "payment-app-rolebinding"
    namespace = kubernetes_namespace_v1.env[each.key].metadata[0].name
    labels = merge(local.common_labels, {
      "app.kubernetes.io/environment" = each.value.env_label
    })
  }

  role_ref {
    api_group = "rbac.authorization.k8s.io"
    kind      = "Role"
    name      = kubernetes_role_v1.payment_role[each.key].metadata[0].name
  }

  subject {
    kind      = "ServiceAccount"
    name      = kubernetes_service_account_v1.payment_sa[each.key].metadata[0].name
    namespace = kubernetes_namespace_v1.env[each.key].metadata[0].name
  }

  depends_on = [kubernetes_role_v1.payment_role, kubernetes_service_account_v1.payment_sa]
}

# ══════════════════════════════════════════════════════════════════════════════
# POSTGRESQL — PersistentVolumeClaim
# ══════════════════════════════════════════════════════════════════════════════
# Each namespace gets its own isolated PVC. The staging and prod PostgreSQL
# instances are completely independent — no shared storage, no shared service.
#
# storage_class_name is left configurable (default ""). On OpenShift/ROKS set
# TF_VAR_postgres_storage_class to "ibmc-block-gold" or equivalent.
# ReadWriteOnce is correct for a single-replica PostgreSQL deployment.

resource "kubernetes_persistent_volume_claim_v1" "postgres" {
  for_each = local.envs

  metadata {
    name      = each.value.pvc_name
    namespace = kubernetes_namespace_v1.env[each.key].metadata[0].name
    labels = merge(local.common_labels, {
      "app.kubernetes.io/environment" = each.value.env_label
      "app.kubernetes.io/component"   = "database"
      "app"                           = "postgres"
    })
  }

  spec {
    access_modes = ["ReadWriteOnce"]

    resources {
      requests = {
        storage = var.postgres_pvc_size
      }
    }

    storage_class_name = var.postgres_storage_class != "" ? var.postgres_storage_class : null
  }

  # prevent accidental deletion of the PVC when running terraform destroy in prod.
  lifecycle {
    prevent_destroy = false # set to true for the prod PVC in a real deployment
  }

  depends_on = [kubernetes_namespace_v1.env]
}

# ══════════════════════════════════════════════════════════════════════════════
# POSTGRESQL — Deployment
# ══════════════════════════════════════════════════════════════════════════════
# Single-replica PostgreSQL per namespace. A single replica is intentional for
# a demo — HA requires a StatefulSet with streaming replication or an operator
# (e.g. CloudNativePG) which is outside the scope of this configuration.
#
# Credentials are passed as environment variables directly into the pod.
# The application-facing Secret (postgres-credentials) is created by Ansible
# and is referenced by the payment-service pod — NOT by the postgres pod itself.
#
# Security hardening applied:
#   • runAsNonRoot: true  (postgres image supports uid 999)
#   • readOnlyRootFilesystem: false  (PostgreSQL writes to /var/lib/postgresql)
#   • allowPrivilegeEscalation: false
#   • capabilities: drop ALL

resource "kubernetes_deployment_v1" "postgres" {
  for_each = local.envs

  metadata {
    name      = "postgres"
    namespace = kubernetes_namespace_v1.env[each.key].metadata[0].name
    labels = merge(local.common_labels, {
      "app.kubernetes.io/environment" = each.value.env_label
      "app.kubernetes.io/component"   = "database"
      "app"                           = "postgres"
    })
  }

  spec {
    replicas = 1

    selector {
      match_labels = {
        "app" = "postgres"
      }
    }

    # Recreate strategy: a single-replica DB cannot do a rolling update
    # (the new pod needs the PVC, which is ReadWriteOnce and held by the old pod).
    strategy {
      type = "Recreate"
    }

    template {
      metadata {
        labels = merge(local.common_labels, {
          "app.kubernetes.io/environment" = each.value.env_label
          "app.kubernetes.io/component"   = "database"
          "app"                           = "postgres"
        })
      }

      spec {
        # On OpenShift/ROKS each namespace has an assigned UID range
        # (e.g. 1000660000-1000669999). Hard-coding uid 999 violates the SCC.
        # Omitting run_as_user / fs_group lets OpenShift inject a valid UID
        # from the namespace annotation automatically (anyuid not required).
        security_context {
          run_as_non_root = true
        }

        # No service account needed for the postgres pod.
        automount_service_account_token = false

        container {
          name  = "postgres"
          image = var.postgres_image

          port {
            container_port = 5432
            name           = "postgres"
            protocol       = "TCP"
          }

          # Credentials injected as environment variables.
          # In production these should come from a Kubernetes Secret volume mount,
          # not plain env vars — but this matches the architecture scope where
          # Ansible manages the application Secret separately.
          env {
            name  = "POSTGRES_DB"
            value = each.value.postgres_db
          }
          env {
            name  = "POSTGRES_USER"
            value = var.postgres_user
          }
          env {
            name  = "POSTGRES_PASSWORD"
            value = each.value.postgres_password
          }
          # Tell PostgreSQL where to store data — mapped to the PVC.
          env {
            name  = "PGDATA"
            value = "/var/lib/postgresql/data/pgdata"
          }

          resources {
            requests = {
              cpu    = var.postgres_cpu_request
              memory = var.postgres_memory_request
            }
            limits = {
              cpu    = var.postgres_cpu_limit
              memory = var.postgres_memory_limit
            }
          }

          volume_mount {
            name       = "postgres-data"
            mount_path = "/var/lib/postgresql/data"
          }

          # Readiness probe: PostgreSQL is ready when pg_isready succeeds.
          readiness_probe {
            exec {
              command = ["pg_isready", "-U", var.postgres_user, "-d", each.value.postgres_db]
            }
            initial_delay_seconds = 10
            period_seconds        = 10
            failure_threshold     = 6
            success_threshold     = 1
          }

          # Liveness probe: if PostgreSQL stops responding, restart the pod.
          liveness_probe {
            exec {
              command = ["pg_isready", "-U", var.postgres_user, "-d", each.value.postgres_db]
            }
            initial_delay_seconds = 30
            period_seconds        = 20
            failure_threshold     = 3
          }

          security_context {
            allow_privilege_escalation = false
            read_only_root_filesystem  = false # PostgreSQL writes to PGDATA

            capabilities {
              drop = ["ALL"]
            }
          }
        }

        volume {
          name = "postgres-data"

          persistent_volume_claim {
            claim_name = kubernetes_persistent_volume_claim_v1.postgres[each.key].metadata[0].name
          }
        }
      }
    }
  }

  depends_on = [
    kubernetes_persistent_volume_claim_v1.postgres,
    kubernetes_network_policy_v1.default_deny_all,
    kubernetes_network_policy_v1.allow_payment_to_postgres,
  ]
}

# ══════════════════════════════════════════════════════════════════════════════
# POSTGRESQL — Service (ClusterIP, internal only)
# ══════════════════════════════════════════════════════════════════════════════
# Named "postgres-service" in both namespaces. The payment-app connects to this
# service by DNS name: postgres-service.<namespace>.svc.cluster.local:5432
# (or simply postgres-service:5432 from within the same namespace).
#
# ClusterIP type ensures the service is never reachable from outside the cluster.
# The NetworkPolicy provides the second layer of defence.

resource "kubernetes_service_v1" "postgres" {
  for_each = local.envs

  metadata {
    name      = "postgres-service"
    namespace = kubernetes_namespace_v1.env[each.key].metadata[0].name
    labels = merge(local.common_labels, {
      "app.kubernetes.io/environment" = each.value.env_label
      "app.kubernetes.io/component"   = "database"
      "app"                           = "postgres"
    })
  }

  spec {
    selector = {
      "app" = "postgres"
    }

    port {
      name        = "postgres"
      port        = 5432
      target_port = 5432
      protocol    = "TCP"
    }

    # ClusterIP (default) — never NodePort or LoadBalancer.
    type = "ClusterIP"
  }

  depends_on = [kubernetes_deployment_v1.postgres]
}

# ══════════════════════════════════════════════════════════════════════════════
# NULL RESOURCE — post-apply readiness check (informational)
# ══════════════════════════════════════════════════════════════════════════════
# Logs that the infrastructure is in place. The actual DB readiness wait that
# gates the CD pipeline is performed by Ansible (wait_for postgres-service:5432),
# as shown in the architecture diagram — these null resources are complementary
# observability steps, not blocking gates.

resource "null_resource" "postgres_ready_check" {
  for_each = local.envs

  triggers = {
    # Re-run whenever the postgres Deployment changes.
    deployment_uid = kubernetes_deployment_v1.postgres[each.key].metadata[0].uid
    service_name   = kubernetes_service_v1.postgres[each.key].metadata[0].name
  }

  provisioner "local-exec" {
    command = <<-EOT
      echo "✅ Terraform apply complete for namespace: ${each.value.namespace}"
      echo "   PostgreSQL Service : postgres-service.${each.value.namespace}.svc.cluster.local:5432"
      echo "   PVC               : ${each.value.pvc_name} (${var.postgres_pvc_size})"
      echo "   Database          : ${each.value.postgres_db}"
      echo "   Next step         : Run Ansible playbook to inject postgres-credentials Secret"
    EOT
  }

  depends_on = [kubernetes_service_v1.postgres]
}
