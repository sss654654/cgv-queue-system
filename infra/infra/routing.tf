## 가용영역 a
# NAT 게이트웨이 생성을 위한 EIP (Elastic IP) 할당
resource "aws_eip" "nat_gateway_eip_a" {
  domain = "vpc"
  tags = {
    Name = "dev-nat-gateway-eip-a"
  }
}

# NAT 게이트웨이 생성
resource "aws_nat_gateway" "dev_nat_gateway_a" {
  allocation_id = aws_eip.nat_gateway_eip_a.id
  subnet_id     = aws_subnet.public_subnet_a.id
  tags = {
    Name = "dev-nat-gateway-a"
  }
}

# nat 들어갈 퍼블릭 서브넷의 퍼블릭 라우팅 테이블
resource "aws_route_table" "public_route_table_a" {
  vpc_id = aws_vpc.dev_vpc.id
  tags = {
    Name = "dev-public-route-table-a"
  }
}

# 퍼블릭 라우팅 테이블에 인터넷 게이트웨이 라우팅 추가
resource "aws_route" "public_internet_route_a" {
  route_table_id         = aws_route_table.public_route_table_a.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.dev_igw.id
}

# nat 들어갈 퍼블릭 서브넷과 퍼블릭 라우팅 테이블a 연결
resource "aws_route_table_association" "public_subnet_association_a" {
  subnet_id      = aws_subnet.public_subnet_a.id
  route_table_id = aws_route_table.public_route_table_a.id
}

# gitlab용 테스트 퍼블릭 서브넷과 퍼블릭 라우팅 테이블a 연결
# 어차피 테스트 용도니까 퍼블릭 라우팅 테이블a 두 서브넷에서 공유해서 사용
resource "aws_route_table_association" "test_public_gitlab_subnet_association" {
  subnet_id      = aws_subnet.test_public_gitlab_subnet.id
  route_table_id = aws_route_table.public_route_table_a.id
}


# 프라이빗 라우팅 테이블
resource "aws_route_table" "private_route_table" {
  vpc_id = aws_vpc.dev_vpc.id
  tags = {
    Name = "dev-private-route-table"
  }
}

# EKS Fargate 프라이빗 서브넷 전용 라우팅 테이블(a)
resource "aws_route_table" "eks_fargate_private_route_table_a" {
  vpc_id = aws_vpc.dev_vpc.id
  tags = {
    Name = "dev-eks-fargate-private-route-table-a"
  }
}

# EKS Fargate 프라이빗 서브넷 전용 라우팅 테이블에 NAT 게이트웨이 라우팅 추가
resource "aws_route" "eks_fargate_nat_route_a" {
  route_table_id         = aws_route_table.eks_fargate_private_route_table_a.id
  destination_cidr_block = "0.0.0.0/0"
  nat_gateway_id         = aws_nat_gateway.dev_nat_gateway_a.id
}

# EKS Fargate 프라이빗 서브넷과 라우팅 테이블 연결 
resource "aws_route_table_association" "eks_fargate_subnet_association_a" {
  subnet_id      = aws_subnet.eks_fargate_private_subnet_a.id
  route_table_id = aws_route_table.eks_fargate_private_route_table_a.id
}


# Gitlab 프라이빗 서브넷과 라우팅 테이블 연결
resource "aws_route_table_association" "gitlab_subnet_association" {
  subnet_id      = aws_subnet.gitlab_private_subnet.id
  route_table_id = aws_route_table.private_route_table.id
}

# DB 프라이빗 서브넷과 라우팅 테이블 연결
resource "aws_route_table_association" "db_subnet_association" {
  subnet_id      = aws_subnet.db_private_subnet.id
  route_table_id = aws_route_table.private_route_table.id
}


## 가용영역 c

# EKS Fargate 프라이빗 서브넷 전용 라우팅 테이블(c)
resource "aws_route_table" "eks_fargate_private_route_table_c" {
  vpc_id = aws_vpc.dev_vpc.id
  tags = {
    Name = "dev-eks-fargate-private-route-table-c"
  }
}

# EKS Fargate 프라이빗 서브넷 전용 라우팅 테이블에 NAT 게이트웨이 라우팅 추가
# 근데 비용 절감을 위해 NAT 게이트웨이 연결은 a의 퍼블릭 서브넷으로
resource "aws_route" "eks_fargate_nat_route_c" {
  route_table_id         = aws_route_table.eks_fargate_private_route_table_c.id
  destination_cidr_block = "0.0.0.0/0"
  nat_gateway_id         = aws_nat_gateway.dev_nat_gateway_a.id
}

# EKS Fargate 프라이빗 서브넷과 라우팅 테이블 연결(c)
resource "aws_route_table_association" "eks_fargate_subnet_association_c" {
  subnet_id      = aws_subnet.eks_fargate_private_subnet_c.id
  route_table_id = aws_route_table.eks_fargate_private_route_table_c.id
}
