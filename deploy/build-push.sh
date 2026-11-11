#!/usr/bin/env bash
# 지금 체크아웃한 커밋으로 서버 이미지 9개를 linux/amd64로 빌드해 ECR에 올린다. 태그는 커밋 SHA다.
# 사용: deploy/build-push.sh <ECR 레지스트리>
#   예: deploy/build-push.sh 123456789012.dkr.ecr.ap-northeast-2.amazonaws.com
# 먼저 ECR에 ticket/<모듈> 저장소를 만들고, aws ecr get-login-password | docker login 으로 로그인해 둔다.
# 서버가 x86(Ubuntu 24.04 x86_64)이라 Apple Silicon Mac에서도 amd64로 만든다. 에뮬레이션이라 첫 빌드가 느리다.
set -euo pipefail

registry="${1:?ECR 레지스트리 주소가 필요하다. 예: 123456789012.dkr.ecr.ap-northeast-2.amazonaws.com}"
root="$(git rev-parse --show-toplevel)"

# 이미지 태그가 커밋 SHA이므로, 커밋하지 않은 변경이 섞이면 태그와 실제 내용이 달라진다
if [[ -n "$(git -C "$root" status --porcelain)" ]]; then
  echo "커밋하지 않은 변경이 있어 멈춘다. 배포할 커밋을 깨끗하게 체크아웃한 뒤 다시 실행한다." >&2
  exit 1
fi

tag="$(git -C "$root" rev-parse HEAD)"
modules=(
  config-server
  eureka-server
  gateway-service
  user-service
  performance-service
  reservation-service
  waiting-room-service
  payment-service
  fake-pg
)

# Dockerfile의 빌드 단계는 MODULE을 쓰지 않아 모든 모듈이 같은 캐시를 쓴다. 첫 모듈만 Gradle 빌드가 돈다.
for module in "${modules[@]}"; do
  image="$registry/ticket/$module:$tag"
  echo "==> $image"
  docker buildx build \
    --platform linux/amd64 \
    --build-arg MODULE="$module" \
    --tag "$image" \
    --push \
    "$root/backend"
done

echo
echo "서버 .env에 넣을 값: IMAGE_TAG=$tag"
