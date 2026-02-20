# =============================================================================
# Stack 7: cgv-monitoring
# CloudWatch Alarms (NAT Gateway + ALB) + SNS Topic
# → 2.4 섹션 15 참고
#
# Prometheus/Grafana/AlertManager는 Helm으로 K8s 안에 설치 (→ 2.3/2.4)
# 여기서는 AWS 관리형 서비스 알림만 Terraform으로 관리한다.
# =============================================================================

terraform {
  required_version = ">= 1.5.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = "ap-northeast-2"

  default_tags {
    tags = {
      Project   = "cgv"
      ManagedBy = "terraform"
    }
  }
}

# --- Remote State 참조 ---
data "terraform_remote_state" "vpc" {
  backend = "s3"
  config = {
    bucket = "cgv-terraform-state"
    key    = "cgv-vpc/terraform.tfstate"
    region = "ap-northeast-2"
  }
}

# =============================================================================
# SNS Topic — CloudWatch Alarm → Slack 알림 경로
# Slack Webhook 연결은 AWS Chatbot 또는 Lambda로 구현 (별도 설정)
# =============================================================================
resource "aws_sns_topic" "alerts" {
  name = "cgv-cloudwatch-alerts"

  tags = { Name = "cgv-cloudwatch-alerts" }
}

# 이메일 구독 (선택)
resource "aws_sns_topic_subscription" "email" {
  count     = var.alert_email != "" ? 1 : 0
  topic_arn = aws_sns_topic.alerts.arn
  protocol  = "email"
  endpoint  = var.alert_email
}

# =============================================================================
# NAT Gateway CloudWatch Alarms (Dual NAT — AZ-a, AZ-c 각각)
# NAT Gateway는 AWS 관리형이므로 Prometheus가 수집 불가 → CloudWatch Alarm 필수
# =============================================================================

# --- AZ-a NAT Gateway 패킷 드롭 ---
resource "aws_cloudwatch_metric_alarm" "nat_gw_packet_drop_a" {
  alarm_name          = "cgv-nat-gw-a-packet-drop"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "PacketsDropCount"
  namespace           = "AWS/NATGateway"
  period              = 300
  statistic           = "Sum"
  threshold           = 100
  alarm_description   = "NAT Gateway AZ-a 패킷 드롭 발생 — 동시 연결 한계 또는 네트워크 장애"
  alarm_actions       = [aws_sns_topic.alerts.arn]

  dimensions = {
    NatGatewayId = data.terraform_remote_state.vpc.outputs.nat_gateway_a_id
  }

  tags = { Name = "cgv-nat-gw-a-packet-drop" }
}

# --- AZ-c NAT Gateway 패킷 드롭 ---
resource "aws_cloudwatch_metric_alarm" "nat_gw_packet_drop_c" {
  alarm_name          = "cgv-nat-gw-c-packet-drop"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "PacketsDropCount"
  namespace           = "AWS/NATGateway"
  period              = 300
  statistic           = "Sum"
  threshold           = 100
  alarm_description   = "NAT Gateway AZ-c 패킷 드롭 발생 — 동시 연결 한계 또는 네트워크 장애"
  alarm_actions       = [aws_sns_topic.alerts.arn]

  dimensions = {
    NatGatewayId = data.terraform_remote_state.vpc.outputs.nat_gateway_c_id
  }

  tags = { Name = "cgv-nat-gw-c-packet-drop" }
}

# =============================================================================
# ALB CloudWatch Alarms (prod 전용)
# ALB는 ALB Controller가 Ingress에서 동적 생성하므로 ARN suffix를 변수로 주입
# alb_arn_suffix == ""이면 ALB 알림은 생성하지 않음 (count = 0)
# =============================================================================

# --- ALB 5xx 에러율 ---
resource "aws_cloudwatch_metric_alarm" "alb_5xx_high" {
  count = var.alb_arn_suffix != "" ? 1 : 0

  alarm_name          = "cgv-alb-5xx-error-rate-high"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  threshold           = 5 # 5% 초과
  alarm_description   = "ALB 5xx 에러율이 5%를 초과했습니다. 백엔드 Pod 상태를 확인하세요."
  alarm_actions       = [aws_sns_topic.alerts.arn]

  metric_query {
    id          = "error_rate"
    expression  = "errors / requests * 100"
    label       = "5xx Error Rate %"
    return_data = true
  }

  metric_query {
    id = "errors"
    metric {
      metric_name = "HTTPCode_Target_5XX_Count"
      namespace   = "AWS/ApplicationELB"
      period      = 60
      stat        = "Sum"
      dimensions  = { LoadBalancer = var.alb_arn_suffix }
    }
  }

  metric_query {
    id = "requests"
    metric {
      metric_name = "RequestCount"
      namespace   = "AWS/ApplicationELB"
      period      = 60
      stat        = "Sum"
      dimensions  = { LoadBalancer = var.alb_arn_suffix }
    }
  }

  tags = { Name = "cgv-alb-5xx-high" }
}

# --- ALB 정상 타겟 수 (모든 Pod 비정상 시 critical) ---
resource "aws_cloudwatch_metric_alarm" "alb_healthy_hosts" {
  count = var.alb_target_group_arn_suffix != "" ? 1 : 0

  alarm_name          = "cgv-alb-no-healthy-hosts"
  comparison_operator = "LessThanThreshold"
  evaluation_periods  = 1
  metric_name         = "HealthyHostCount"
  namespace           = "AWS/ApplicationELB"
  period              = 60
  statistic           = "Minimum"
  threshold           = 1
  alarm_description   = "ALB에 정상 타겟이 없습니다. 모든 Pod가 비정상입니다."
  alarm_actions       = [aws_sns_topic.alerts.arn]

  dimensions = {
    TargetGroup  = var.alb_target_group_arn_suffix
    LoadBalancer = var.alb_arn_suffix
  }

  tags = { Name = "cgv-alb-no-healthy-hosts" }
}
