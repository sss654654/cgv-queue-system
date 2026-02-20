# =============================================================================
# cgv-monitoring Variables
# =============================================================================

variable "alb_arn_suffix" {
  description = "ALB ARN suffix (ALB Controller가 생성한 ALB. kubectl get ingress로 확인 후 입력)"
  type        = string
  default     = "" # terraform apply -var='alb_arn_suffix=app/k8s-cgvprod-.../...'
}

variable "alb_target_group_arn_suffix" {
  description = "ALB Target Group ARN suffix (HealthyHostCount 알림용)"
  type        = string
  default     = "" # terraform apply -var='alb_target_group_arn_suffix=targetgroup/k8s-cgvprod-.../...'
}

variable "alert_email" {
  description = "CloudWatch Alarm 알림 이메일"
  type        = string
  default     = "" # SNS 이메일 구독용. Slack Webhook은 별도 Lambda 또는 AWS Chatbot으로 연결
}
