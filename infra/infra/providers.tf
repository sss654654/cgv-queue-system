# Provider는 AWS, Azure, Google Cloud 같은 클라우드 서비스나 Kubernetes, GitHub 같은 
# 서비스와 상호작용하는 플러그인
provider "aws" {
  region = "ap-northeast-2"
}

# 보안적 요소 고려
# profile
# assume_role -> role_arn / session_name

/*
어떤 계정/리전에 배포할지 변수로 관리

로컬 WSL에서 aws configure --profile dev로 자격증명 사용
*/