terraform {
    backend "s3" {
        bucket = "iam-root-dev"
        key = "global/s3/terraform.tfstate"
        region = "ap-northeast-2"
        dynamodb_table = "terraform-locks"
        encrypt = true
    }
}

/*
terraform {
  backend "local" {
    path = "terraform.tfstate"
  }
}
*/

/*
역할: Terraform 원격 상태(tfstate) 를 저장할 S3 버킷·경로 설정.
언제 쓰나: terraform init -backend-config=backend.hcl 할 때.
내용 포인트:
bucket(이미 존재해야 함), key(경로), region, encrypt=true

주의: 같은 스택 코드로 이 S3를 만들 수 없음(먼저 만들어 둬야 함).
*/