# ──────────────────────────────────────────────────────────────────────────────
# variables.tf — cluster workspace
#
# All variables required to provision the VPC and ROKS cluster on IBM Cloud.
# Sensitive values must be supplied via TF_VAR_* environment variables or a
# *.tfvars file — never hardcode them in source code.
# ──────────────────────────────────────────────────────────────────────────────

# ── IBM Cloud authentication ───────────────────────────────────────────────────

variable "ibmcloud_api_key" {
  description = "IBM Cloud IAM API key. Create one at: https://cloud.ibm.com/iam/apikeys"
  type        = string
  sensitive   = true
  # No default — must be supplied via TF_VAR_ibmcloud_api_key
}

# ── Naming prefix ──────────────────────────────────────────────────────────────

variable "prefix" {
  description = <<-EOT
    Short prefix prepended to every IBM Cloud resource name (VPC, subnets, cluster, …).
    Must be 3–12 lowercase alphanumeric characters or hyphens, and must start with a letter.
    Example: "payment" → resources named payment-vpc, payment-cluster, …
  EOT
  type        = string
  default     = "payment"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{2,11}$", var.prefix))
    error_message = "Prefix must be 3–12 lowercase alphanumeric/hyphen characters and start with a letter."
  }
}

# ── IBM Cloud region ───────────────────────────────────────────────────────────

variable "region" {
  description = <<-EOT
    IBM Cloud region where the VPC and cluster are created.
    Example values: us-south, us-east, eu-de, eu-gb, jp-tok, au-syd.
  EOT
  type        = string
  default     = "eu-de"

  validation {
    condition = contains(
      ["us-south", "us-east", "ca-tor", "eu-de", "eu-gb", "jp-tok", "jp-osa", "au-syd", "br-sao"],
      var.region
    )
    error_message = "Region must be a valid IBM Cloud multi-zone region."
  }
}

# ── Resource group ─────────────────────────────────────────────────────────────

variable "resource_group" {
  description = <<-EOT
    Name of the IBM Cloud resource group to deploy into.
    The API key must have Manager/Editor access to this resource group.
    Find existing groups at: https://cloud.ibm.com/account/resource-groups
  EOT
  type        = string
  default     = "france-aar"
}

# ── OpenShift version ──────────────────────────────────────────────────────────

variable "ocp_version" {
  description = <<-EOT
    OpenShift version to install on the cluster.
    Use the major.minor format only — e.g. "4.17".
    The module appends "_openshift" internally.
    List available versions with: ibmcloud ks versions --show-version openshift
  EOT
  type        = string
  default     = "4.17"
}

# ── Worker pool — machine type ─────────────────────────────────────────────────

variable "worker_machine_type" {
  description = <<-EOT
    VPC worker node machine type (flavor).
    Spec from IaCArchitecture.md: 4 vCPU / 16 GB RAM per node.
    Equivalent IBM Cloud VPC flavor: bx2.4x16.
    List available flavors:
      ibmcloud ks flavors --zone <region>-1 --provider vpc-gen2
  EOT
  type        = string
  default     = "bx2.4x16"
}


# ── Worker operating system ────────────────────────────────────────────────────

variable "worker_os" {
  description = <<-EOT
    Operating system for the worker nodes.
    Valid values for ROKS on VPC Gen2: "REDHAT_8_64", "RHEL_9_64".
  EOT
  type        = string
  default     = "REDHAT_8_64"

  validation {
    condition     = contains(["REDHAT_8_64", "RHEL_9_64"], var.worker_os)
    error_message = "worker_os must be REDHAT_8_64 or RHEL_9_64."
  }
}

# ── VPC networking ─────────────────────────────────────────────────────────────

variable "subnet_cidrs" {
  description = <<-EOT
    CIDR blocks for the three worker subnets, one per zone.
    Each CIDR must be a subset of the auto-generated address prefix for that zone.
    IBM Cloud auto-generates one /18 prefix per zone; the defaults below carve
    a /22 (1022 addresses) out of each prefix for eu-de:
      eu-de-1: 10.243.0.0/18  → subnet 10.243.0.0/22
      eu-de-2: 10.243.64.0/18 → subnet 10.243.64.0/22
      eu-de-3: 10.243.128.0/18→ subnet 10.243.128.0/22
    Override via TF_VAR_subnet_cidrs if you deploy to a different region.
  EOT
  type        = list(string)
  default     = ["10.243.0.0/22", "10.243.64.0/22", "10.243.128.0/22"]

  validation {
    condition     = length(var.subnet_cidrs) == 3
    error_message = "Exactly 3 subnet CIDRs must be provided (one per zone)."
  }
}

# ── Resource tags ──────────────────────────────────────────────────────────────

variable "resource_tags" {
  description = "User-defined tags applied to all IBM Cloud resources for cost tracking."
  type        = list(string)
  default     = ["project:payment-app", "managed-by:terraform"]

  validation {
    condition     = alltrue([for t in var.resource_tags : can(regex("^[A-Za-z0-9 _\\-.:]{1,128}$", t))])
    error_message = "Each tag must be ≤128 chars and contain only A-Z a-z 0-9 space _ - . :"
  }
}
