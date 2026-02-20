terraform {
  backend "s3" {
    bucket         = "cgv-terraform-state"
    key            = "cgv-monitoring/terraform.tfstate"
    region         = "ap-northeast-2"
    dynamodb_table = "cgv-terraform-lock"
    encrypt        = true
  }
}
