# ──────────────────────────────────────────────────────────────────────────────
# outputs.tf — cluster workspace
#
# Exposes cluster connection details consumed by:
#   • The app workspace (via terraform_remote_state)
#   • Operators (oc login, CI/CD secrets)
# ──────────────────────────────────────────────────────────────────────────────

# ── Cluster identity ───────────────────────────────────────────────────────────

output "cluster_id" {
  description = "IBM Cloud cluster ID."
  value       = module.ocp_cluster.cluster_id
}

output "cluster_name" {
  description = "Cluster name as registered in IBM Cloud."
  value       = module.ocp_cluster.cluster_name
}

output "cluster_crn" {
  description = "CRN of the provisioned ROKS cluster."
  value       = module.ocp_cluster.cluster_crn
}

output "ocp_version" {
  description = "Installed OpenShift version (e.g. 4.17.x_openshift)."
  value       = module.ocp_cluster.ocp_version
}

# ── API endpoints ──────────────────────────────────────────────────────────────

output "cluster_master_url" {
  description = <<-EOT
    Public OpenShift API server URL.
    Used by the app workspace (via remote state) as the Kubernetes provider host.
    Also use this as TF_VAR_cluster_url / TF_VAR_openshift_server in the app workspace.

    Login with: oc login --token=<sa-token> --server=<this value>
  EOT
  value       = module.ocp_cluster.master_url
}

output "private_service_endpoint_url" {
  description = "Private service endpoint URL (accessible only from within the VPC or via VPN/Direct Link)."
  value       = module.ocp_cluster.private_service_endpoint_url
}

output "public_service_endpoint_url" {
  description = "Public service endpoint URL."
  value       = module.ocp_cluster.public_service_endpoint_url
}

# ── Ingress ────────────────────────────────────────────────────────────────────

output "ingress_hostname" {
  description = <<-EOT
    Ingress subdomain assigned to the cluster.
    OpenShift Routes are reachable at: <route-name>.<namespace>.<ingress_hostname>
    The internal image registry default route is:
      default-route-openshift-image-registry.apps.<ingress_hostname>
  EOT
  value       = module.ocp_cluster.ingress_hostname
}

output "openshift_registry_route" {
  description = <<-EOT
    Hostname for the OpenShift internal image registry external route.
    Use this as TF_VAR_openshift_registry in the app workspace.
    Requires the default route to be enabled:
      oc patch configs.imageregistry.operator.openshift.io/cluster \
        --type merge -p '{"spec":{"defaultRoute":true}}'
  EOT
  value       = "default-route-openshift-image-registry.apps.${module.ocp_cluster.ingress_hostname}"
}

# ── VPC ────────────────────────────────────────────────────────────────────────

output "vpc_id" {
  description = "ID of the VPC created for the cluster."
  value       = ibm_is_vpc.this.id
}

output "vpc_name" {
  description = "Name of the VPC created for the cluster."
  value       = ibm_is_vpc.this.name
}

output "subnet_ids" {
  description = "IDs of the worker subnets (one per zone)."
  value       = ibm_is_subnet.this[*].id
}

# ── Worker pools ───────────────────────────────────────────────────────────────

output "workerpools" {
  description = "Worker pools provisioned on the cluster."
  value       = module.ocp_cluster.workerpools
}

# ── COS ────────────────────────────────────────────────────────────────────────

output "cos_crn" {
  description = "CRN of the COS instance used for the internal OpenShift image registry."
  value       = module.ocp_cluster.cos_crn
}

# ── Summary ───────────────────────────────────────────────────────────────────

output "summary" {
  description = "Human-readable summary of the cluster workspace apply result."
  value       = <<-EOT

    ════════════════════════════════════════════════════════
     Payment App — Cluster Workspace Apply Summary
    ════════════════════════════════════════════════════════

    Cluster
      Name      : ${module.ocp_cluster.cluster_name}
      ID        : ${module.ocp_cluster.cluster_id}
      OCP       : ${module.ocp_cluster.ocp_version}
      Master URL: ${module.ocp_cluster.master_url}

    VPC
      Name      : ${ibm_is_vpc.this.name}
      ID        : ${ibm_is_vpc.this.id}
      Subnets   : ${join(", ", ibm_is_subnet.this[*].id)}

    Ingress
      Domain    : ${module.ocp_cluster.ingress_hostname}
      Registry  : default-route-openshift-image-registry.apps.${module.ocp_cluster.ingress_hostname}

    Next steps
      1. Enable the registry default route:
           oc patch configs.imageregistry.operator.openshift.io/cluster \
             --type merge -p '{"spec":{"defaultRoute":true}}'

      2. Create a Terraform service account and obtain its token:
           oc create serviceaccount terraform-sa -n kube-system
           oc adm policy add-cluster-role-to-user cluster-admin \
             -z terraform-sa -n kube-system
           oc create token terraform-sa -n kube-system --duration=8760h

      3. Apply the app workspace:
           cd ../app
           cp .env.example .env   # fill in cluster_url, cluster_token, passwords
           terraform init && terraform apply

    ════════════════════════════════════════════════════════
  EOT
}
