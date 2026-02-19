#!/bin/bash
# =============================================================================
# CGV 인프라 관리 스크립트
# 6-Stack Terraform + K8s YAML 일괄 관리
#
# 사용법:
#   ./infra.sh plan                  # 전체 스택 Plan (변경 사항 미리보기)
#   ./infra.sh apply                 # 전체 스택 순서대로 배포
#   ./infra.sh destroy               # 전체 스택 역순으로 삭제
#   ./infra.sh plan   <stack-name>   # 특정 스택만 Plan
#   ./infra.sh apply  <stack-name>   # 특정 스택만 배포
#   ./infra.sh destroy <stack-name>  # 특정 스택만 삭제
#   ./infra.sh status                # 전체 상태 확인
#   ./infra.sh k8s                   # K8s YAML 적용 (EKS 배포 후)
#
# 의존성 순서:
#   s3-dynamodb → cgv-vpc → cgv-security → cgv-database → cgv-eks → cgv-ecr
#   → kubectl apply (k8s/)
#
# 사전 요구사항:
#   - AWS CLI 설정 완료 (aws configure)
#   - Terraform >= 1.5.0
#   - kubectl (k8s 명령 시)
#   - cgv-database 배포 시: export TF_VAR_db_password='비밀번호'
#                           export TF_VAR_redis_auth_token='토큰'
#   - cgv-vpc 배포 시:      export TF_VAR_alert_email='이메일'
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# --- 스택 순서 (의존성 체인) ---
STACKS=(
  "s3-dynamodb"
  "cgv-vpc"
  "cgv-security"
  "cgv-database"
  "cgv-eks"
  "cgv-ecr"
)

# --- K8s YAML 적용 순서 ---
K8S_YAMLS=(
  "karpenter-ec2nodeclass.yaml"
  "karpenter-nodepool-infra.yaml"
  "karpenter-nodepool-dev.yaml"
  "karpenter-nodepool-prod-base.yaml"
  "karpenter-nodepool-prod-burst.yaml"
  "redis-dev.yaml"
)

# --- 색상 ---
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

# =============================================================================
# 유틸리티 함수
# =============================================================================
log_header() {
  echo ""
  echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
  echo -e "${BOLD}  $1${NC}"
  echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
}

log_step() {
  echo -e "\n${CYAN}▶ [$1] $2${NC}"
}

log_ok() {
  echo -e "${GREEN}✓ $1${NC}"
}

log_warn() {
  echo -e "${YELLOW}⚠ $1${NC}"
}

log_err() {
  echo -e "${RED}✗ $1${NC}"
}

usage() {
  echo "사용법: $0 {plan|apply|destroy|status|k8s} [stack-name]"
  echo ""
  echo "명령어:"
  echo "  plan    [stack]   Terraform Plan (변경 사항 미리보기)"
  echo "  apply   [stack]   Terraform Apply (배포)"
  echo "  destroy [stack]   Terraform Destroy (삭제)"
  echo "  status            전체 스택 상태 확인"
  echo "  k8s               K8s YAML kubectl apply"
  echo ""
  echo "스택 목록 (의존성 순서):"
  for i in "${!STACKS[@]}"; do
    echo "  $((i+1)). ${STACKS[$i]}"
  done
  echo "  7. k8s (kubectl)"
  echo ""
  echo "예시:"
  echo "  $0 plan                    # 전체 Plan"
  echo "  $0 apply                   # 전체 배포"
  echo "  $0 apply cgv-vpc           # cgv-vpc만 배포"
  echo "  $0 destroy                 # 전체 삭제 (역순)"
  echo ""
  echo "환경변수:"
  echo "  TF_VAR_db_password   cgv-database 배포 시 필수"
  exit 1
}

# 스택 이름 유효성 검사
validate_stack() {
  local target=$1
  for stack in "${STACKS[@]}"; do
    if [ "$stack" = "$target" ]; then
      return 0
    fi
  done
  log_err "알 수 없는 스택: $target"
  echo "사용 가능한 스택: ${STACKS[*]}"
  exit 1
}

# 스택별 필수 환경변수 확인
check_required_vars() {
  local stack=$1

  if [ "$stack" = "cgv-database" ]; then
    if [ -z "${TF_VAR_db_password:-}" ]; then
      log_err "TF_VAR_db_password 환경변수가 설정되지 않았습니다."
      echo "  export TF_VAR_db_password='your-secure-password'"
      exit 1
    fi
    if [ -z "${TF_VAR_redis_auth_token:-}" ]; then
      log_err "TF_VAR_redis_auth_token 환경변수가 설정되지 않았습니다."
      echo "  export TF_VAR_redis_auth_token='your-redis-auth-token'"
      exit 1
    fi
  fi

  if [ "$stack" = "cgv-vpc" ] && [ -z "${TF_VAR_alert_email:-}" ]; then
    log_err "TF_VAR_alert_email 환경변수가 설정되지 않았습니다."
    echo "  export TF_VAR_alert_email='your-email@example.com'"
    exit 1
  fi
}

# =============================================================================
# Terraform 실행
# =============================================================================
tf_init() {
  local stack_dir=$1
  if [ ! -d "${stack_dir}/.terraform" ]; then
    echo "  terraform init..."
    terraform -chdir="$stack_dir" init -input=false > /dev/null 2>&1
    log_ok "init 완료"
  fi
}

tf_plan() {
  local stack=$1
  local stack_dir="${SCRIPT_DIR}/${stack}"

  log_step "$stack" "Plan"
  check_required_vars "$stack"
  tf_init "$stack_dir"
  terraform -chdir="$stack_dir" plan -input=false
}

tf_apply() {
  local stack=$1
  local stack_dir="${SCRIPT_DIR}/${stack}"

  log_step "$stack" "Apply"
  check_required_vars "$stack"
  tf_init "$stack_dir"
  terraform -chdir="$stack_dir" apply -auto-approve -input=false
  log_ok "$stack 배포 완료"
}

tf_destroy() {
  local stack=$1
  local stack_dir="${SCRIPT_DIR}/${stack}"

  log_step "$stack" "Destroy"

  if [ ! -d "${stack_dir}/.terraform" ]; then
    log_warn "$stack: .terraform 없음 (init 안 됨, 스킵)"
    return 0
  fi

  # s3-dynamodb는 prevent_destroy 때문에 경고
  if [ "$stack" = "s3-dynamodb" ]; then
    log_warn "s3-dynamodb는 prevent_destroy 설정됨 — State 버킷 삭제는 수동으로"
  fi

  terraform -chdir="$stack_dir" destroy -auto-approve -input=false
  log_ok "$stack 삭제 완료"
}

# =============================================================================
# 전체 스택 작업
# =============================================================================
plan_all() {
  log_header "전체 Plan (6 Stacks)"

  local changes=0
  local no_changes=0

  for stack in "${STACKS[@]}"; do
    tf_plan "$stack"
  done

  log_header "Plan 완료"
  echo -e "위 Plan 결과를 확인한 후 ${GREEN}./infra.sh apply${NC} 로 배포하세요."
}

apply_all() {
  log_header "전체 배포 시작 (6 Stacks + K8s)"

  echo -e "${YELLOW}배포 순서:${NC}"
  for i in "${!STACKS[@]}"; do
    echo "  $((i+1)). ${STACKS[$i]}"
  done
  echo "  7. k8s (kubectl apply)"
  echo ""

  read -p "계속하시겠습니까? (y/N): " confirm
  if [[ ! "$confirm" =~ ^[yY]$ ]]; then
    echo "취소되었습니다."
    exit 0
  fi

  local start_time=$SECONDS

  for stack in "${STACKS[@]}"; do
    tf_apply "$stack"
  done

  # EKS 배포 후 kubeconfig 업데이트 + K8s YAML 적용
  apply_k8s_resources

  local elapsed=$(( SECONDS - start_time ))
  local minutes=$(( elapsed / 60 ))
  local seconds=$(( elapsed % 60 ))

  log_header "전체 배포 완료! (${minutes}분 ${seconds}초)"
}

destroy_all() {
  log_header "전체 삭제 (역순)"

  echo -e "${RED}주의: 모든 인프라가 삭제됩니다!${NC}"
  echo "삭제 순서 (역순):"
  for ((i=${#STACKS[@]}-1; i>=0; i--)); do
    echo "  $((${#STACKS[@]}-i)). ${STACKS[$i]}"
  done
  echo ""

  read -p "정말 삭제하시겠습니까? 'destroy'를 입력하세요: " confirm
  if [ "$confirm" != "destroy" ]; then
    echo "취소되었습니다."
    exit 0
  fi

  # K8s 리소스 먼저 삭제
  delete_k8s_resources

  # Terraform 역순 삭제
  for ((i=${#STACKS[@]}-1; i>=0; i--)); do
    tf_destroy "${STACKS[$i]}"
  done

  log_header "전체 삭제 완료"
}

# =============================================================================
# K8s 리소스 관리
# =============================================================================
apply_k8s_resources() {
  log_step "k8s" "kubeconfig 업데이트 + YAML 적용"

  aws eks update-kubeconfig \
    --name cgv-cluster \
    --region ap-northeast-2 \
    2>/dev/null

  log_ok "kubeconfig 업데이트 완료"

  # cgv-dev 네임스페이스 생성 (redis-dev.yaml에 필요)
  kubectl create namespace cgv-dev --dry-run=client -o yaml | kubectl apply -f -

  for yaml in "${K8S_YAMLS[@]}"; do
    echo "  kubectl apply -f ${yaml}"
    kubectl apply -f "${SCRIPT_DIR}/k8s/${yaml}"
  done

  log_ok "K8s 리소스 적용 완료"
}

delete_k8s_resources() {
  log_step "k8s" "K8s 리소스 삭제"

  # kubeconfig가 있는 경우에만 실행
  if ! kubectl cluster-info > /dev/null 2>&1; then
    log_warn "클러스터 접근 불가 — K8s 삭제 스킵"
    return 0
  fi

  # 역순으로 삭제
  for ((i=${#K8S_YAMLS[@]}-1; i>=0; i--)); do
    local yaml="${K8S_YAMLS[$i]}"
    echo "  kubectl delete -f ${yaml}"
    kubectl delete -f "${SCRIPT_DIR}/k8s/${yaml}" --ignore-not-found=true
  done

  log_ok "K8s 리소스 삭제 완료"
}

# =============================================================================
# 상태 확인
# =============================================================================
check_status() {
  log_header "인프라 상태 확인"

  for stack in "${STACKS[@]}"; do
    local stack_dir="${SCRIPT_DIR}/${stack}"

    if [ ! -d "${stack_dir}/.terraform" ]; then
      echo -e "  ${YELLOW}○${NC} ${stack} — init 안 됨"
      continue
    fi

    # terraform show로 리소스 수 확인
    local resource_count
    resource_count=$(terraform -chdir="$stack_dir" state list 2>/dev/null | wc -l || echo "0")

    if [ "$resource_count" -gt 0 ]; then
      echo -e "  ${GREEN}●${NC} ${stack} — ${resource_count}개 리소스 배포됨"
    else
      echo -e "  ${RED}○${NC} ${stack} — 배포 안 됨"
    fi
  done

  # K8s 상태
  echo ""
  if kubectl cluster-info > /dev/null 2>&1; then
    local nodepool_count
    nodepool_count=$(kubectl get nodepools --no-headers 2>/dev/null | wc -l || echo "0")
    echo -e "  ${GREEN}●${NC} k8s — NodePool ${nodepool_count}개"
  else
    echo -e "  ${YELLOW}○${NC} k8s — 클러스터 미접근"
  fi

  echo ""
}

# =============================================================================
# 메인
# =============================================================================
if [ $# -lt 1 ]; then
  usage
fi

ACTION=$1
TARGET=${2:-""}

case "$ACTION" in
  plan)
    if [ -n "$TARGET" ]; then
      validate_stack "$TARGET"
      tf_plan "$TARGET"
    else
      plan_all
    fi
    ;;
  apply)
    if [ -n "$TARGET" ]; then
      validate_stack "$TARGET"
      tf_apply "$TARGET"
    else
      apply_all
    fi
    ;;
  destroy)
    if [ -n "$TARGET" ]; then
      validate_stack "$TARGET"
      tf_destroy "$TARGET"
    else
      destroy_all
    fi
    ;;
  status)
    check_status
    ;;
  k8s)
    apply_k8s_resources
    ;;
  *)
    usage
    ;;
esac
