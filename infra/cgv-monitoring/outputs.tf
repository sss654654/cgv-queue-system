# =============================================================================
# cgv-monitoring Outputs
# =============================================================================

output "sns_topic_arn" {
  description = "CloudWatch Alarm SNS Topic ARN"
  value       = aws_sns_topic.alerts.arn
}
