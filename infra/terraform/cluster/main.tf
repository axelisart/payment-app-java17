# ──────────────────────────────────────────────────────────────────────────────
# main.tf — cluster workspace
#
# Provisions the IBM Cloud infrastructure for the payment-app cluster:
#
#   1. Resource group lookup (data source)
#   2. VPC (ibm_is_vpc)
#   3. Public gateways — one per zone (ibm_is_public_gateway)
#   4. Subnets — one per zone (ibm_is_subnet)
#   5. ROKS cluster — module terraform-ibm-modules/base-ocp-vpc/ibm v3.90.0
#      • OpenShift 4.17
#      • 3 worker pools (one per zone) × 1 node = 3 nodes total
#        bx2.4x16 = 4 vCPU / 16 GB RAM each  ← IaCArchitecture.md spec
#
# ── Apply order ───────────────────────────────────────────────────────────────
#   terraform apply   ← this workspace first
#   cd ../app && terraform apply
# ──────────────────────────────────────────────────────────────────────────────

# ── Data: resolve resource group name → ID ────────────────────────────────────

data "ibm_resource_group" "rg" {
  name = var.resource_group
}

locals {
  zones = ["${var.region}-1", "${var.region}-2", "${var.region}-3"]
}

# ══════════════════════════════════════════════════════════════════════════════
# 1. VPC
# ══════════════════════════════════════════════════════════════════════════════

resource "ibm_is_vpc" "this" {
  name                      = "${var.prefix}-vpc"
  resource_group            = data.ibm_resource_group.rg.id
  address_prefix_management = "auto"
  tags                      = var.resource_tags
}

# ══════════════════════════════════════════════════════════════════════════════
# 2. Public gateways — one per zone
# ══════════════════════════════════════════════════════════════════════════════
# Worker nodes need outbound internet access to reach:
#   • IBM Container Registry (icr.io / quay.io)
#   • Red Hat subscription servers
#   • IBM Cloud monitoring and logging endpoints

resource "ibm_is_public_gateway" "this" {
  count          = 3
  name           = "${var.prefix}-pgw-${count.index + 1}"
  vpc            = ibm_is_vpc.this.id
  zone           = local.zones[count.index]
  resource_group = data.ibm_resource_group.rg.id
  tags           = var.resource_tags
}

# ══════════════════════════════════════════════════════════════════════════════
# 3. Subnets — one per zone
# ══════════════════════════════════════════════════════════════════════════════

resource "ibm_is_subnet" "this" {
  count           = 3
  name            = "${var.prefix}-subnet-${count.index + 1}"
  vpc             = ibm_is_vpc.this.id
  zone            = local.zones[count.index]
  ipv4_cidr_block = var.subnet_cidrs[count.index]
  public_gateway  = ibm_is_public_gateway.this[count.index].id
  resource_group  = data.ibm_resource_group.rg.id
  tags            = var.resource_tags
}

# ══════════════════════════════════════════════════════════════════════════════
# 4. ROKS Cluster — terraform-ibm-modules/base-ocp-vpc/ibm
# ══════════════════════════════════════════════════════════════════════════════
# • vpc_subnets: keyed by arbitrary zone labels (zone-1/2/3); each worker pool
#   entry references one key via subnet_prefix.
# • Three pool entries (one per zone) × workers_per_zone = 3 nodes total.
# • pool_name "default" is mandatory for the first pool — IBM Cloud assigns
#   that name automatically and Terraform matches it by name.

module "ocp_cluster" {
  source  = "registry.terraform.io/terraform-ibm-modules/base-ocp-vpc/ibm"
  version = ">= 3.90.0, < 4.0.0"

  # ── Identity ───────────────────────────────────────────────────────────────
  cluster_name      = "${var.prefix}-cluster"
  region            = var.region
  resource_group_id = data.ibm_resource_group.rg.id
  resource_tags     = var.resource_tags

  # ── OpenShift version ──────────────────────────────────────────────────────
  ocp_version = var.ocp_version

  # ── VPC reference ─────────────────────────────────────────────────────────
  vpc_id = ibm_is_vpc.this.id

  # ── Subnet metadata ────────────────────────────────────────────────────────
  # Keyed by "default" — referenced by the single worker pool via subnet_prefix.
  # All 3 subnets are listed under the same key so the pool spans all 3 zones.
  vpc_subnets = {
    default = [
      {
        id         = ibm_is_subnet.this[0].id
        zone       = ibm_is_subnet.this[0].zone
        cidr_block = ibm_is_subnet.this[0].ipv4_cidr_block
      },
      {
        id         = ibm_is_subnet.this[1].id
        zone       = ibm_is_subnet.this[1].zone
        cidr_block = ibm_is_subnet.this[1].ipv4_cidr_block
      },
      {
        id         = ibm_is_subnet.this[2].id
        zone       = ibm_is_subnet.this[2].zone
        cidr_block = ibm_is_subnet.this[2].ipv4_cidr_block
      },
    ]
  }

  # ── Worker pool ────────────────────────────────────────────────────────────
  # Single pool spanning all 3 zones, 1 node per zone = 3 nodes total.
  # IBM Cloud counts workers_per_zone × nb_zones = 1 × 3 = 3 ≥ 2 minimum.
  # IaCArchitecture.md: 3 × bx2.4x16 = 12 vCPU / 48 GB RAM.
  worker_pools = [
    {
      pool_name        = "default"
      machine_type     = var.worker_machine_type
      workers_per_zone = 1
      operating_system = var.worker_os
      subnet_prefix    = "default"
    },
  ]

  # ── COS instance (required for the internal image registry) ───────────────
  use_existing_cos = false

  # ── Endpoints ─────────────────────────────────────────────────────────────
  # Keep the public endpoint enabled for initial oc login and Terraform access.
  # Set to true once VPN / Direct Link access to the VPC is in place.
  disable_public_endpoint = false

  depends_on = [ibm_is_subnet.this]
}
