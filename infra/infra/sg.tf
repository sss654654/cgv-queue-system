# Gitlab Test Public용 보안 그룹 생성
resource "aws_security_group" "test_public_gitlab_sg" {
  name        = "test-public-gitlab-sg"
  description = "Security group for Bastion Host"
  vpc_id      = aws_vpc.dev_vpc.id

  # gitlab SSH 접속 허용 (본인 IP와 Client VPN IP 대역에서만 허용)
  ingress {
    from_port   = 2222
    to_port     = 2222
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # SSH 접속 허용 (본인 IP와 Client VPN IP 대역에서만 허용)
  ingress {
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # 웹 인터페이스 접속 허용
  ingress {
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # HTTPS 접속 허용
  ingress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # 아웃바운드 트래픽 허용 (모든 곳으로)
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "test-public-gitlab-sg"
  }
}


# EKS Fargate 클러스터를 위한 보안 그룹
resource "aws_security_group" "eks_fargate_sg" {
  name        = "dev-eks-fargate-sg"
  description = "Security group for EKS Fargate cluster"
  vpc_id      = aws_vpc.dev_vpc.id

  # 클러스터 내/외부 통신 규칙을 추가 (필요에 따라 더 상세히 설정)
  ingress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = [aws_vpc.dev_vpc.cidr_block] # VPC 내부 통신 허용
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "dev-eks-fargate-sg"
  }
}


resource "aws_security_group" "db_sg" {
  name        = "dev-db-sg"
  description = "Security group for DB instances"
  vpc_id      = aws_vpc.dev_vpc.id

  # EKS 서브넷에서만 RDS 포트로의 접근을 허용
  ingress {
    from_port   = 5432 # 예시: PostgreSQL 포트
    to_port     = 5432
    protocol    = "tcp"
    # EKS가 위치한 서브넷의 CIDR 블록으로 제한
    cidr_blocks = [aws_subnet.eks_fargate_private_subnet_a.cidr_block]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "dev-db-sg"
  }
}


# Gitlab EC2 인스턴스를 위한 보안 그룹
resource "aws_security_group" "gitlab_sg" {
  name        = "dev-gitlab-sg"
  description = "Security group for Gitlab instance"
  vpc_id      = aws_vpc.dev_vpc.id

  ingress {
    from_port   = -1  # ICMP는 포트가 없으므로 -1
    to_port     = -1  # ICMP는 포트가 없으므로 -1
    protocol    = "icmp"
    security_groups = [aws_security_group.client_vpn_sg.id]
  }
  
  # SSH 접속 허용 (본인 IP와 Client VPN IP 대역에서만 허용)
  ingress {
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    security_groups = [aws_security_group.client_vpn_sg.id]
  }

  # 웹 인터페이스 접속 허용
  ingress {
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    security_groups = [aws_security_group.client_vpn_sg.id]
  }

  # HTTPS 접속 허용
  ingress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    security_groups = [aws_security_group.client_vpn_sg.id]
  }

  # 모든 아웃바운드 트래픽 허용
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "dev-gitlab-sg"
  }
}

# Client VPN 엔드포인트용 보안 그룹 생성
resource "aws_security_group" "client_vpn_sg" {
  name        = "dev-client-vpn-sg"
  description = "Security group for Client VPN endpoint"
  vpc_id      = aws_vpc.dev_vpc.id # Gitlab 인스턴스의 VPC ID와 동일하게 설정

  # 사용자의 공인 IP에서 VPN 포트 443으로의 인바운드 트래픽 허용
  ingress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["211.105.80.175/32"]
  }

  # VPN 클라이언트가 VPC 내부로 통신할 수 있도록 아웃바운드 트래픽 허용
 egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    # VPC의 CIDR 블록을 리스트 형태로 지정
    cidr_blocks = [aws_vpc.dev_vpc.cidr_block]
  }
  tags = {
    Name = "dev-client-vpn-sg"
  }
}
