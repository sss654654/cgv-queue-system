
# Gitlab 서브넷용 ECR 엔드포인트
resource "aws_vpc_endpoint" "gitlab_ecr_dkr" {
  vpc_id            = aws_vpc.dev_vpc.id
  service_name      = "com.amazonaws.ap-northeast-2.ecr.dkr"
  vpc_endpoint_type = "Interface"
  subnet_ids        = [aws_subnet.gitlab_private_subnet.id]
  security_group_ids = [aws_security_group.gitlab_sg.id] # Gitlab 보안 그룹 사용
  tags = {
    Name = "dev-gitlab-ecr-dkr-endpoint"
  }
}

resource "aws_vpc_endpoint" "gitlab_ecr_api" {
  vpc_id            = aws_vpc.dev_vpc.id
  service_name      = "com.amazonaws.ap-northeast-2.ecr.api"
  vpc_endpoint_type = "Interface"
  subnet_ids        = [aws_subnet.gitlab_private_subnet.id]
  security_group_ids = [aws_security_group.gitlab_sg.id]
  tags = {
    Name = "dev-gitlab-ecr-api-endpoint"
  }
}


# EKS Fargate 서브넷용 ECR 엔드포인트
# 이 엔드포인트는 EKS Fargate가 ECR에서 이미지를 당겨올 때 사용됩니다.
# NAT를 타지 않게 하기 위함입니다.
resource "aws_vpc_endpoint" "eks_fargate_ecr_dkr" {
  vpc_id            = aws_vpc.dev_vpc.id
  service_name      = "com.amazonaws.ap-northeast-2.ecr.dkr"
  vpc_endpoint_type = "Interface"
  subnet_ids        = [aws_subnet.eks_fargate_private_subnet_a.id]
  security_group_ids = [aws_security_group.eks_fargate_sg.id] # EKS Fargate 보안 그룹 사용
  tags = {
    Name = "dev-eks-fargate-ecr-dkr-endpoint"
  }
}
  
resource "aws_vpc_endpoint" "eks_fargate_ecr_api" {
  vpc_id            = aws_vpc.dev_vpc.id
  service_name      = "com.amazonaws.ap-northeast-2.ecr.api"
  vpc_endpoint_type = "Interface"
  subnet_ids        = [aws_subnet.eks_fargate_private_subnet_a.id]
  security_group_ids = [aws_security_group.eks_fargate_sg.id]
  tags = {
    Name = "dev-eks-fargate-ecr-api-endpoint"
  }
}

# EKS Fargate 서브넷용 S3 게이트웨이 엔드포인트
# EKS Fargate가 S3와 통신할 때 사용됩니다.
resource "aws_vpc_endpoint" "s3_gateway_endpoint" {
  vpc_id = aws_vpc.dev_vpc.id
  service_name = "com.amazonaws.ap-northeast-2.s3"
  route_table_ids = [aws_route_table.eks_fargate_private_route_table_a.id]
  tags = {
    Name = "dev-s3-gateway-endpoint"
  }
}
/*
s3 게이트웨이 엔드포인트 왜씀?

EKS Fargate와 S3 게이트웨이 엔드포인트의 관계
EKS Fargate는 S3 게이트웨이 엔드포인트가 필요할 수 있습니다. EKS Fargate는 ECR로 이미지를 가져오는 것 외에도 
다양한 이유로 AWS 서비스와 통신해야 할 수 있기 때문입니다.

애플리케이션 데이터 저장: EKS Fargate에서 실행되는 애플리케이션이 S3에 로그 파일, 백업, 또는 기타 데이터를 저장할 수 있습니다. 
예를 들어, 웹 애플리케이션의 업로드 파일을 S3에 저장하는 것이 일반적입니다.

Terraform 상태 파일: Fargate Pod이 Terraform 상태 파일을 S3에 저장하고 관리해야 하는 경우, S3와의 연결이 필요합니다.

CloudWatch 로그: Fargate Pod의 로그가 CloudWatch로 전송되는 과정에서 S3 버킷에 저장될 수 있습니다.

따라서, EKS Fargate가 S3와 통신해야 하는 상황을 대비하여 S3 게이트웨이 엔드포인트를 구성하는 것은 안전하고 효율적인 설계입니다. 
게이트웨이 엔드포인트는 VPC의 라우팅 테이블을 통해 S3로의 트래픽을 프라이빗하게 라우팅하여 NAT 게이트웨이를 사용하지 않게 
함으로써 비용을 절감하고 보안을 강화합니다.
*/