#!/usr/bin/env bash
# AWS CloudShell(콘솔 왼쪽 아래 터미널)에 붙여 넣어 한 번만 실행한다. 다시 실행해도 안전하다.
# GitHub Actions가 비밀 키 없이(OIDC) 배포하도록 권한과 이미지 저장소를 만든다.
#   1. ECR 저장소 nowstation-server(최근 이미지 10개만 보관)
#   2. GitHub OIDC 신원 공급자(GitHub이 "나는 이 저장소의 main 브랜치 작업"이라고 증명하는 통로)
#   3. GitHub용 역할 nowstation-github-deploy: 이 저장소 main에서만, ECR에 올리기 + 이 EC2에 명령 전달만 가능
#   4. EC2용 역할 nowstation-ec2: SSM 명령 받기 + ECR에서 이미지 받기 → 인스턴스에 연결
set -euo pipefail
REGION=ap-southeast-2
INSTANCE_ID=i-0fdef7d9c88a6553c
GITHUB_REPO=sinadsl1457/NowStation-Server
ECR_REPO=nowstation-server
ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
echo "계정 확인 완료, 리전 $REGION"

# 1. ECR
aws ecr describe-repositories --region $REGION --repository-names $ECR_REPO >/dev/null 2>&1 \
  || aws ecr create-repository --region $REGION --repository-name $ECR_REPO --image-scanning-configuration scanOnPush=true >/dev/null
aws ecr put-lifecycle-policy --region $REGION --repository-name $ECR_REPO --lifecycle-policy-text \
  '{"rules":[{"rulePriority":1,"description":"keep last 10","selection":{"tagStatus":"any","countType":"imageCountMoreThan","countNumber":10},"action":{"type":"expire"}}]}' >/dev/null
echo "1/4 ECR 저장소 준비"

# 2. GitHub OIDC
OIDC_ARN=arn:aws:iam::$ACCOUNT:oidc-provider/token.actions.githubusercontent.com
aws iam get-open-id-connect-provider --open-id-connect-provider-arn $OIDC_ARN >/dev/null 2>&1 \
  || aws iam create-open-id-connect-provider --url https://token.actions.githubusercontent.com --client-id-list sts.amazonaws.com >/dev/null
echo "2/4 GitHub OIDC 공급자 준비"

# 3. GitHub용 역할
cat > /tmp/gh-trust.json <<JSON
{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Federated":"$OIDC_ARN"},
 "Action":"sts:AssumeRoleWithWebIdentity",
 "Condition":{"StringEquals":{"token.actions.githubusercontent.com:aud":"sts.amazonaws.com",
  "token.actions.githubusercontent.com:sub":"repo:$GITHUB_REPO:ref:refs/heads/main"}}}]}
JSON
cat > /tmp/gh-policy.json <<JSON
{"Version":"2012-10-17","Statement":[
 {"Effect":"Allow","Action":"ecr:GetAuthorizationToken","Resource":"*"},
 {"Effect":"Allow","Action":["ecr:BatchCheckLayerAvailability","ecr:InitiateLayerUpload","ecr:UploadLayerPart","ecr:CompleteLayerUpload","ecr:PutImage","ecr:BatchGetImage"],
  "Resource":"arn:aws:ecr:$REGION:$ACCOUNT:repository/$ECR_REPO"},
 {"Effect":"Allow","Action":"ssm:SendCommand","Resource":[
   "arn:aws:ec2:$REGION:$ACCOUNT:instance/$INSTANCE_ID",
   "arn:aws:ssm:$REGION::document/AWS-RunShellScript"]},
 {"Effect":"Allow","Action":"ssm:GetCommandInvocation","Resource":"*"}]}
JSON
aws iam get-role --role-name nowstation-github-deploy >/dev/null 2>&1 \
  || aws iam create-role --role-name nowstation-github-deploy --assume-role-policy-document file:///tmp/gh-trust.json >/dev/null
aws iam update-assume-role-policy --role-name nowstation-github-deploy --policy-document file:///tmp/gh-trust.json
aws iam put-role-policy --role-name nowstation-github-deploy --policy-name deploy --policy-document file:///tmp/gh-policy.json
echo "3/4 GitHub용 역할 준비"

# 4. EC2용 역할
cat > /tmp/ec2-trust.json <<'JSON'
{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}
JSON
aws iam get-role --role-name nowstation-ec2 >/dev/null 2>&1 \
  || aws iam create-role --role-name nowstation-ec2 --assume-role-policy-document file:///tmp/ec2-trust.json >/dev/null
aws iam attach-role-policy --role-name nowstation-ec2 --policy-arn arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore
aws iam attach-role-policy --role-name nowstation-ec2 --policy-arn arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly
aws iam get-instance-profile --instance-profile-name nowstation-ec2 >/dev/null 2>&1 || {
  aws iam create-instance-profile --instance-profile-name nowstation-ec2 >/dev/null
  aws iam add-role-to-instance-profile --instance-profile-name nowstation-ec2 --role-name nowstation-ec2
  sleep 10 # 새 프로필이 퍼질 때까지
}
CURRENT=$(aws ec2 describe-iam-instance-profile-associations --region $REGION --filters Name=instance-id,Values=$INSTANCE_ID \
  --query 'IamInstanceProfileAssociations[0].IamInstanceProfile.Arn' --output text)
if [ "$CURRENT" = "None" ] || [ -z "$CURRENT" ]; then
  aws ec2 associate-iam-instance-profile --region $REGION --instance-id $INSTANCE_ID --iam-instance-profile Name=nowstation-ec2 >/dev/null
fi
echo "4/4 EC2 역할 연결"

echo
echo "끝. 아래 줄을 Claude에게 알려 주세요(비밀 값 아님):"
echo "AWS_DEPLOY_ROLE_ARN=arn:aws:iam::$ACCOUNT:role/nowstation-github-deploy"
