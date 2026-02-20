variable "cluster_name" {
  description = "EKS 클러스터 이름 (Karpenter 태그용)"
  type        = string
  default     = "cgv-cluster"
}

variable "alert_email" {
  description = "Budget alarm notification email"
  type        = string
  default     = ""
}
