# =============================================================================
# Stack 1: s3-dynamodb (부트스트랩)
# Terraform State 저장소 — 최초 1회 생성, Local Backend
# =============================================================================

terraform {
  required_version = ">= 1.5.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  # 부트스트랩이므로 Local Backend (S3가 아직 없음)
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

# --- S3 Bucket: Terraform State 저장소 ---
# ⚠️ S3 버킷 이름은 글로벌 유일해야 함
# "cgv-terraform-state"가 이미 사용 중이면 아래 이름 변경 후
# 모든 backend.tf의 bucket 값도 동일하게 수정할 것
# 예: "cgv-terraform-state-{AWS_ACCOUNT_ID}"
resource "aws_s3_bucket" "terraform_state" {
  bucket = "cgv-terraform-state"

  # 실수로 terraform destroy 시 State 파일 보호
  lifecycle {
    prevent_destroy = true
  }

  tags = {
    Name = "cgv-terraform-state"
  }
}

# 버전 관리 활성화 (State 파일 이전 버전 복구 가능)
resource "aws_s3_bucket_versioning" "terraform_state" {
  bucket = aws_s3_bucket.terraform_state.id

  versioning_configuration {
    status = "Enabled"
  }
}

# 서버 측 암호화 (State에 민감 정보 포함 가능)
resource "aws_s3_bucket_server_side_encryption_configuration" "terraform_state" {
  bucket = aws_s3_bucket.terraform_state.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# 퍼블릭 액세스 차단
resource "aws_s3_bucket_public_access_block" "terraform_state" {
  bucket = aws_s3_bucket.terraform_state.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# --- DynamoDB Table: Terraform State Lock ---
# 동시에 terraform apply 실행 방지
resource "aws_dynamodb_table" "terraform_lock" {
  name         = "cgv-terraform-lock"
  billing_mode = "PAY_PER_REQUEST" # 사용량 기반 (고정 비용 없음)
  hash_key     = "LockID"

  attribute {
    name = "LockID"
    type = "S"
  }

  tags = {
    Name = "cgv-terraform-lock"
  }
}

# --- Outputs ---
output "state_bucket_name" {
  description = "Terraform State S3 버킷 이름"
  value       = aws_s3_bucket.terraform_state.id
}

output "lock_table_name" {
  description = "Terraform State Lock DynamoDB 테이블 이름"
  value       = aws_dynamodb_table.terraform_lock.name
}
