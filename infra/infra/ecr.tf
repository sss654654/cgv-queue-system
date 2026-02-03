resource "aws_ecr_repository" "gitlab_ecr_repo" {
  name                 = "dev-gitlab-repo"
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }
}