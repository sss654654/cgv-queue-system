# =============================================================================
# Stack 4: cgv-database (RDS MySQL + ElastiCache Redis)
# RDS: 공유 인스턴스 (dev/prod DB 분리는 SQL로)
# ElastiCache: Prod 전용 (dev는 Redis Pod 사용)
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

data "terraform_remote_state" "security" {
  backend = "s3"
  config = {
    bucket = "cgv-terraform-state"
    key    = "cgv-security/terraform.tfstate"
    region = "ap-northeast-2"
  }
}

# =============================================================================
# Variables
# =============================================================================
variable "db_password" {
  description = "RDS master password"
  type        = string
  sensitive   = true
}

variable "create_elasticache" {
  description = "ElastiCache 생성 여부 (Prod만 true)"
  type        = bool
  default     = true
}

variable "redis_auth_token" {
  description = "Redis AUTH token (→ 2.1 섹션 10.18)"
  type        = string
  sensitive   = true
}

# =============================================================================
# 1. RDS MySQL
# =============================================================================

# --- DB Subnet Group ---
resource "aws_db_subnet_group" "mysql" {
  name       = "cgv-db-subnet-group"
  subnet_ids = data.terraform_remote_state.vpc.outputs.db_private_subnet_ids

  tags = { Name = "cgv-db-subnet-group" }
}

# --- Parameter Group ---
resource "aws_db_parameter_group" "mysql" {
  family = "mysql8.0"
  name   = "cgv-mysql-params"

  parameter {
    name  = "slow_query_log"
    value = "1"
  }

  parameter {
    name  = "long_query_time"
    value = "1"    # 1초 이상 쿼리를 slow query로 기록
  }

  parameter {
    name  = "log_output"
    value = "TABLE"    # mysql.slow_log 테이블에 기록 (CloudWatch Logs 전송 가능)
  }

  tags = { Name = "cgv-mysql-params" }
}

# --- RDS Instance (공유) ---
resource "aws_db_instance" "mysql" {
  identifier = "cgv-mysql"

  engine         = "mysql"
  engine_version = "8.0"
  instance_class = "db.t3.micro" # $0.018/hr (~$0.43/day)

  allocated_storage = 20
  storage_type      = "gp3"

  db_name  = "cgv_dev" # 초기 DB. cgv_prod는 마이그레이션 스크립트로 생성 (→ 2.1 섹션 10.6 참고)
  username = "admin"
  password = var.db_password

  db_subnet_group_name   = aws_db_subnet_group.mysql.name
  vpc_security_group_ids = [data.terraform_remote_state.security.outputs.rds_sg_id]
  parameter_group_name   = aws_db_parameter_group.mysql.name
  publicly_accessible    = false
  multi_az               = false # 초기 배포. 첫 이벤트 D-7까지 true 전환 (비용 +$0.43/day)

  storage_encrypted   = true  # at-rest 암호화 (→ 2.1 섹션 10.18)
  deletion_protection = true  # terraform destroy 시 삭제 차단 (의도적 삭제 시 false로 변경 후 apply)

  backup_retention_period      = 7              # 7일간 자동 백업 → PITR(Point-in-Time Recovery) 가능
  backup_window                = "02:00-03:00"  # UTC 02시 (KST 11시) — maintenance_window와 겹치지 않게
  copy_tags_to_snapshot        = true           # 스냅샷에도 태그 복사 (비용 추적)
  skip_final_snapshot          = false          # destroy 시 최종 스냅샷 필수 생성
  final_snapshot_identifier    = "cgv-mysql-final"

  # multi_az = false 상태에서의 복원 전략:
  # 장애 시 자동 failover 없음 → AWS 자동 백업에서 새 인스턴스로 복원 (RTO ~15분)
  # RPO: 마지막 백업 시점까지 (자동 백업은 5분 간격 트랜잭션 로그)
  # 대기열 핵심 로직은 Redis(ElastiCache)에서 처리되므로 RDS 장애 영향은 좌석 예매 확정에 한정

  tags = { Name = "cgv-mysql" }
}

# =============================================================================
# 2. ElastiCache Redis (Prod 전용)
# =============================================================================

# --- ElastiCache Subnet Group ---
resource "aws_elasticache_subnet_group" "redis" {
  count = var.create_elasticache ? 1 : 0

  name       = "cgv-redis-subnet-group"
  subnet_ids = data.terraform_remote_state.vpc.outputs.db_private_subnet_ids

  tags = { Name = "cgv-redis-subnet-group" }
}

# --- Parameter Group ---
resource "aws_elasticache_parameter_group" "redis" {
  count = var.create_elasticache ? 1 : 0

  name   = "cgv-redis-params"
  family = "redis7"

  # 메모리 부족 시 TTL 설정된 키 중 만료 임박한 것부터 제거
  parameter {
    name  = "maxmemory-policy"
    value = "volatile-ttl"
  }

  tags = { Name = "cgv-redis-params" }
}

# --- Replication Group (Primary + Replica, Multi-AZ) ---
resource "aws_elasticache_replication_group" "redis" {
  count = var.create_elasticache ? 1 : 0

  replication_group_id = "cgv-redis-prod"
  description          = "CGV Queue System Redis - Multi-AZ"

  engine               = "redis"
  engine_version       = "7.0"
  node_type            = "cache.t3.small" # ~1.37GiB, $0.034/hr
  num_cache_clusters   = 2                # Primary(AZ-a) + Replica(AZ-c)

  automatic_failover_enabled = true  # Failover ~30초
  multi_az_enabled           = true

  subnet_group_name  = aws_elasticache_subnet_group.redis[0].name
  security_group_ids = [data.terraform_remote_state.security.outputs.elasticache_sg_id]
  parameter_group_name = aws_elasticache_parameter_group.redis[0].name

  transit_encryption_enabled = true                  # TLS in-transit (→ 2.1 섹션 10.18)
  auth_token                 = var.redis_auth_token   # Redis AUTH (→ 2.1 섹션 10.18)

  snapshot_retention_limit = 1
  snapshot_window          = "03:00-04:00"          # UTC (KST 12:00)
  maintenance_window       = "sun:04:00-sun:05:00"

  tags = {
    Name        = "cgv-redis-prod"
    Environment = "prod"
  }
}
