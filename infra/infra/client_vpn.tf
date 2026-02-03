/*
# Client VPN 엔드포인트 생성
resource "aws_ec2_client_vpn_endpoint" "dev_client_vpn" {
  description            = "Development Client VPN Endpoint"
  server_certificate_arn = "arn:aws:acm:ap-northeast-2:732739477448:certificate/70c4a1a6-3111-42fd-891e-a1bf7a3423db"
  client_cidr_block      = "172.31.0.0/22"
  transport_protocol     = "tcp"
  split_tunnel           = true
  security_group_ids     = [aws_security_group.client_vpn_sg.id] # 이 줄을 추가
  vpc_id                 = aws_vpc.dev_vpc.id
  authentication_options {
    type                       = "certificate-authentication"
    root_certificate_chain_arn = "arn:aws:acm:ap-northeast-2:732739477448:certificate/f4193e57-764c-4782-b858-230a93c38160"
  }

  connection_log_options {
    enabled = false
  }

  tags = {
    Name = "dev-client-vpn"
  }
}

# Client VPN 엔드포인트에 타겟 네트워크 연결 (Gitlab 프라이빗 서브넷)
resource "aws_ec2_client_vpn_network_association" "gitlab_subnet_association" {
  client_vpn_endpoint_id = aws_ec2_client_vpn_endpoint.dev_client_vpn.id
  subnet_id              = aws_subnet.gitlab_private_subnet.id
}

# 인가 규칙 (Gitlab 보안 그룹)
resource "aws_ec2_client_vpn_authorization_rule" "gitlab_access_rule" {
  client_vpn_endpoint_id = aws_ec2_client_vpn_endpoint.dev_client_vpn.id
  description            = "Allow access to Gitlab private subnet"
  target_network_cidr    = aws_subnet.gitlab_private_subnet.cidr_block
  authorize_all_groups   = true
}
*/