# =============================================================================
# Stack 6: cgv-ecr
# ECR Repository + S3 CI Cache Bucket
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

data "aws_caller_identity" "current" {}

# =============================================================================
# 1. ECR Repository
# =============================================================================
resource "aws_ecr_repository" "app" {
  name                 = "cgv-queue-system"
  image_tag_mutability = "MUTABLE" # dev: latest 태그 재사용. prod 분리 시 IMMUTABLE 전환 (→ 2.3 CI/CD 참고)

  image_scanning_configuration {
    scan_on_push = true # Push 시 CVE 자동 스캔
  }

  tags = { Name = "cgv-queue-system" }
}

# 오래된 이미지 자동 정리 — untagged 30일 후 삭제
resource "aws_ecr_lifecycle_policy" "cleanup" {
  repository = aws_ecr_repository.app.name

  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Expire untagged images after 30 days"
      selection = {
        tagStatus   = "untagged"
        countType   = "sinceImagePushed"
        countUnit   = "days"
        countNumber = 30
      }
      action = { type = "expire" }
    }]
  })
}

# =============================================================================
# 2. S3 CI Cache Bucket (GitLab Runner Gradle 캐시)
# =============================================================================
resource "aws_s3_bucket" "ci_cache" {
  bucket = "cgv-ci-cache-${data.aws_caller_identity.current.account_id}"

  tags = {
    Name    = "cgv-ci-cache"
    Purpose = "GitLab Runner build cache"
  }
}

# 30일 후 캐시 자동 만료
resource "aws_s3_bucket_lifecycle_configuration" "ci_cache" {
  bucket = aws_s3_bucket.ci_cache.id

  rule {
    id     = "expire-cache"
    status = "Enabled"

    expiration {
      days = 30
    }
  }
}

# --- Outputs ---
output "ecr_repository_url" {
  description = "ECR Repository URL (Docker push/pull용)"
  value       = aws_ecr_repository.app.repository_url
}

output "ci_cache_bucket_name" {
  description = "CI Cache S3 Bucket 이름"
  value       = aws_s3_bucket.ci_cache.id
}
