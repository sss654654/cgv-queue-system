## VPC
# VPC 생성
resource "aws_vpc" "dev_vpc" {
  cidr_block = "10.0.0.0/16"
  enable_dns_hostnames = true
  enable_dns_support   = true
  tags = {
    Name = "dev-vpc"
  }
}

# 인터넷 게이트웨이 (IGW) 생성 및 VPC에 연결
resource "aws_internet_gateway" "dev_igw" {
  vpc_id = aws_vpc.dev_vpc.id
  tags = {
    Name = "dev-igw"
  }
}


## 가용영역 a

# NAT용 퍼블릭 서브넷 생성
resource "aws_subnet" "public_subnet_a" {
  vpc_id                  = aws_vpc.dev_vpc.id
  cidr_block              = "10.0.1.0/24"
  availability_zone       = "ap-northeast-2a"
  map_public_ip_on_launch = true # EC2 생성 시 퍼블릭 IP 자동 할당
  tags = {
    Name = "dev-public-subnet-a"
  }
}


# gitlab용 임시 퍼블릭 서브넷 생성
resource "aws_subnet" "test_public_gitlab_subnet" {
  vpc_id                  = aws_vpc.dev_vpc.id
  cidr_block              = "10.0.2.0/24"
  availability_zone       = "ap-northeast-2a"
  map_public_ip_on_launch = true # EC2 생성 시 퍼블릭 IP 자동 할당
  tags = {
    Name = "test-public-gitlab-subnet"
  }
}



# DB Private Subnet
resource "aws_subnet" "db_private_subnet" {
  vpc_id            = aws_vpc.dev_vpc.id
  cidr_block        = "10.0.20.0/24"
  availability_zone = "ap-northeast-2a"
  tags = {
    Name = "dev-db-private-subnet"
  }
}

# EKS Fargate Private Subnet (ap-northeast-2a)
resource "aws_subnet" "eks_fargate_private_subnet_a" {
  vpc_id            = aws_vpc.dev_vpc.id
  cidr_block        = "10.0.30.0/24"
  availability_zone = "ap-northeast-2a"
  tags = {
    Name = "dev-eks-fargate-private-subnet-a"
  }
}


# Gitlab Private Subnet
resource "aws_subnet" "gitlab_private_subnet" {
  vpc_id            = aws_vpc.dev_vpc.id
  cidr_block        = "10.0.40.0/24"
  availability_zone = "ap-northeast-2a"
  tags = {
    Name = "dev-gitlab-private-subnet"
  }
}

## 가용영역 2c
# EKS Fargate Private Subnet (ap-northeast-2c)
resource "aws_subnet" "eks_fargate_private_subnet_c" {
  vpc_id            = aws_vpc.dev_vpc.id
  cidr_block        = "10.0.31.0/24" # CIDR 블록을 다르게 설정
  availability_zone = "ap-northeast-2c"
  tags = {
    Name = "dev-eks-fargate-private-subnet-c"
  }
}

/*
# 가용영역 2c에 NAT 들어갈 퍼블릭 서브넷 생성(EKS 생성 시 필요)
resource "aws_subnet" "public_subnet_c" {
  vpc_id                  = aws_vpc.dev_vpc.id
  cidr_block              = "10.0.3.0/24"
  availability_zone       = "ap-northeast-2c"
  map_public_ip_on_launch = true # EC2 생성 시 퍼블릭 IP 자동 할당
  tags = {
    Name = "dev-public-subnet-c"
  }
}
*/