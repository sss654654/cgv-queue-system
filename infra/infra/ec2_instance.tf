/*
# public_subnet에 생성할 bastion_host용 인스턴스
resource "aws_instance" "bastion_host" {
  ami           = "ami-0897f20d7e803af8f" # Gitlab과 동일한 AMI
  instance_type = "t2.micro"
  subnet_id     = aws_subnet.public_subnet.id # 퍼블릭 서브넷에 생성
  vpc_security_group_ids = [aws_security_group.bastion_sg.id]
  associate_public_ip_address = true # 퍼블릭 IP 주소 할당

  tags = {
    Name = "dev-bastion-host"
  }
}

# Gitlab EC2 인스턴스 생성
resource "aws_instance" "gitlab_instance" {
  ami           = "ami-0897f20d7e803af8f" 
  instance_type = "t3.medium"
  subnet_id     = aws_subnet.gitlab_private_subnet.id # 프라이빗 서브넷에 생성
  vpc_security_group_ids =  [aws_security_group.gitlab_sg.id]
  associate_public_ip_address = false # 프라이빗 서브넷에 생성되므로 퍼블릭 IP는 필요 없음
  tags = {
    Name = "dev-gitlab-instance"
  }
}
*/

# EIP 생성
resource "aws_eip" "gitlab_eip" {
}

# EIP와 인스턴스 연결
resource "aws_eip_association" "gitlab_eip_assoc" {
  instance_id = aws_instance.public_gitlab.id
  allocation_id = aws_eip.gitlab_eip.id
}


# gitlab용 임시 pulic_subnet
resource "aws_instance" "public_gitlab" {
  ami           = "ami-0897f20d7e803af8f" # linux
  instance_type = "t3.medium"
  subnet_id     = aws_subnet.test_public_gitlab_subnet.id # 퍼블릭 서브넷에 생성
  vpc_security_group_ids = [aws_security_group.test_public_gitlab_sg.id]
  # associate_public_ip_address = true # 퍼블릭 IP 주소 할당

  key_name = "aws-one393"
  tags = {
    Name = "test-public-gitlab-host"
  }
    root_block_device {
    volume_size = 50
    volume_type = "gp3" # 또는 "gp3" 등
    delete_on_termination = false 
    # 인스턴스가 삭제될 때 연결된 EBS 볼륨이 함께 삭제되는 것을 방지
    # 다만 erraform은 aws_instance 리소스의 root_block_device를 보고 EBS 볼륨을 생성하고 인스턴스에 연결
    # 즉, destroy -> apply 진행하면 새 EBS가 새로운 인스턴스에 붙음(기존 EBS는 유지되긴 함)
  }
}

/*
# 독립적인 EBS 볼륨 리소스 정의
# 이 볼륨은 Terraform Import를 통해 기존 볼륨을 가져와야 함
resource "aws_ebs_volume" "gitlab_ebs_volume" {
  availability_zone = aws_subnet.test_public_gitlab_subnet.availability_zone
  size              = 50
  type              = "gp3"
  tags = {
    Name = "test-public-gitlab-volume"
  }
}

# 인스턴스와 EBS 볼륨 연결
resource "aws_volume_attachment" "gitlab_volume_attachment" {
  instance_id = aws_instance.public_gitlab.id
  volume_id   = aws_ebs_volume.gitlab_ebs_volume.id
  device_name = "/dev/sdh"  # 또는 다른 적절한 디바이스 이름
}
*/
