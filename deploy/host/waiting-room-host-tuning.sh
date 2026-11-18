#!/bin/bash
# =====================================================================================
# 대기열(waiting-room)이 도는 서버의 호스트 설정 — 순간 부하 때 NIC 수신 링 넘침 줄이기
# =====================================================================================
# 하는 일 (부팅할 때마다, 앱 컨테이너보다 먼저):
#   1) NIC 수신 링 칸 수를 RX_RING으로 (기본 2048. NIC 최대보다 크면 최대로)
#   2) ksoftirqd/N(커널이 미뤄 둔 패킷 받기 일을 하는 스레드)의 nice를 KSOFTIRQD_NICE로 (기본 -20)
#   둘 다 재부팅하면 사라지는 설정이라 systemd 서비스로 매 부팅 다시 적용한다.
#
# 근거: 대기열 서비스만 띄운 EC2 c7i.large(2 vCPU, Amazon Linux 2023)에서 순간 부하 3만·4만 측정.
#   - 수신 링 1024 → 2048: 2만 명 몰림에서 k6 재전송 약 45% → 0~7%
#   - ksoftirqd nice 0 → -20: 3만 명 몰림에서 링 넘침 1,000건 넘은 회차 18회 중 8회 → 5회 중 0회(최대 424건).
#     처리 속도·응답 시간이 나빠지지 않음
#
# 아직 확인하지 않은 것 (적용 전에 볼 것):
#   - 팀 서버 vm-edge(m7i.large, Ubuntu 24.04)는 대기열과 gateway·user·performance·Caddy 등이 함께 돈다.
#     이 설정은 서버 전체에 걸리고, 다른 서비스에 미치는 영향은 재지 않았다.
#   - m7i.large의 링 최대값. 서버에서 `ethtool -g <NIC>`의 Pre-set maximums를 본다(넘으면 최대값으로 맞춘다).
#   - Ubuntu에서 실제로 돌려 보지 않았다(Amazon Linux 2023에서 설치·재부팅 확인).
#
# 쓰는 법 (root 권한, 파일로 실행 — curl ... | bash 는 자기 자신을 복사할 수 없어 안 됨):
#   설치·적용  sudo bash waiting-room-host-tuning.sh
#              주의: 링 크기를 바꾸면 NIC가 1~수 초 다시 붙어 그 서버의 연결이 흔들린다. 트래픽이 적을 때 실행한다.
#              EC2 사용자 데이터로 처음 부팅 때 실행해도 된다(파일로 저장한 뒤 bash로 실행).
#   확인       sudo bash waiting-room-host-tuning.sh check      (기대값과 다르면 종료 코드 1)
#   되돌리기   sudo bash waiting-room-host-tuning.sh uninstall  (서비스 삭제 + nice 0, 링은 재부팅 때 기본값)
#   값 바꾸기  sudo RX_RING=4096 KSOFTIRQD_NICE=-10 bash waiting-room-host-tuning.sh
#              → /etc/waiting-room-host-tuning.conf 에 저장되어 부팅 때도 그 값을 쓴다.
#
# 참고: ksoftirqd는 네트워크 말고 타이머 등 다른 softirq 일도 한다. 그 일도 함께 우선순위가 오른다.
# 실행 기록: journalctl -u waiting-room-host-tuning
# =====================================================================================
set -euo pipefail

NAME=waiting-room-host-tuning
BIN=/usr/local/sbin/$NAME
UNIT=/etc/systemd/system/$NAME.service
CONF=/etc/$NAME.conf

# 저장된 값 → 환경 변수 → 기본값 순 (설치 때 준 환경 변수가 저장된 값보다 우선)
_rx=${RX_RING:-}; _nice=${KSOFTIRQD_NICE:-}
[ -f "$CONF" ] && . "$CONF"
RX_RING=${_rx:-${RX_RING:-2048}}
KSOFTIRQD_NICE=${_nice:-${KSOFTIRQD_NICE:--20}}

log() { echo "[$NAME] $*"; }
die() { echo "[$NAME] 실패: $*" >&2; exit 1; }

nic() { ip -o -4 route show default | awk '{print $5; exit}'; }
# ethtool -g 출력에서 "Pre-set maximums"(최대) 또는 "Current hardware settings"(지금) 블록의 RX 값
ring() { ethtool -g "$1" | awk -v k="$2" '$0 ~ k {f=1} f && /^RX:/ {print $2; exit}'; }
ksoftirqd_pids() { pgrep -x 'ksoftirqd/[0-9]+' || true; }

apply() {
  local IF max cur want i
  IF=$(nic); [ -n "$IF" ] || die "기본 경로의 NIC를 못 찾음"
  max=$(ring "$IF" 'Pre-set maximums'); cur=$(ring "$IF" 'Current hardware')
  [ -n "$max" ] && [ -n "$cur" ] || die "ethtool -g $IF 에서 RX 값을 못 읽음"
  want=$RX_RING
  if [ "$want" -gt "$max" ]; then log "RX_RING $want > NIC 최대 $max → $max로"; want=$max; fi
  if [ "$cur" != "$want" ]; then
    log "$IF 수신 링 $cur → $want (NIC가 잠깐 다시 붙음)"
    ethtool -G "$IF" rx "$want"
    for i in $(seq 30); do [ "$(cat "/sys/class/net/$IF/operstate")" = up ] && break; sleep 1; done
  else
    log "$IF 수신 링 이미 $want"
  fi
  for p in $(ksoftirqd_pids); do renice -n "$KSOFTIRQD_NICE" -p "$p" >/dev/null; done
  check
}

check() {
  local IF cur max bad=0 n
  IF=$(nic); cur=$(ring "$IF" 'Current hardware'); max=$(ring "$IF" 'Pre-set maximums')
  local want=$RX_RING; [ "$want" -gt "$max" ] && want=$max
  [ "$cur" = "$want" ] || bad=1
  log "$IF 수신 링 지금 $cur / 기대 $want / NIC 최대 $max"
  for p in $(ksoftirqd_pids); do
    n=$(ps -o ni= -p "$p" | tr -d ' ')
    [ "$n" = "$KSOFTIRQD_NICE" ] || bad=1
    log "$(ps -o comm= -p "$p") nice $n (기대 $KSOFTIRQD_NICE)"
  done
  systemctl is-enabled --quiet $NAME 2>/dev/null && log "서비스: 부팅 때 적용(enabled)" || { log "서비스: 설치 안 됨"; bad=1; }
  [ $bad = 0 ] && log "확인: 기대값과 같음" || { log "확인: 기대값과 다름"; return 1; }
}

do_install() {
  local self; self=$(readlink -f "$0")
  [ -f "$self" ] || die "파일로 실행해야 함 (지금 \$0=$0)"
  if ! command -v ethtool >/dev/null; then
    if command -v apt-get >/dev/null; then apt-get install -y ethtool; else dnf install -y ethtool; fi
  fi
  install -m 755 "$self" "$BIN"
  printf 'RX_RING=%s\nKSOFTIRQD_NICE=%s\n' "$RX_RING" "$KSOFTIRQD_NICE" > "$CONF"
  cat > "$UNIT" <<EOF
[Unit]
Description=대기열 서버 수신 링 넘침 줄이기 (수신 링 칸 수 + ksoftirqd nice)
After=network-online.target
Wants=network-online.target
# 링을 바꾸면 NIC가 잠깐 다시 붙으므로 앱 컨테이너(docker)보다 먼저
Before=docker.service

[Service]
Type=oneshot
ExecStart=$BIN apply
RemainAfterExit=yes

[Install]
WantedBy=multi-user.target
EOF
  systemctl daemon-reload
  systemctl enable $NAME
  log "설치함: $BIN, $UNIT, $CONF (RX_RING=$RX_RING KSOFTIRQD_NICE=$KSOFTIRQD_NICE)"
  systemctl restart $NAME || true   # 지금 바로 적용(oneshot이라 다시 실행). 실패해도 아래 상태를 보여 준다
  systemctl --no-pager --lines=20 status $NAME || true
}

do_uninstall() {
  systemctl disable --now $NAME 2>/dev/null || true
  rm -f "$UNIT" "$BIN" "$CONF"
  systemctl daemon-reload
  for p in $(ksoftirqd_pids); do renice -n 0 -p "$p" >/dev/null; done
  log "지움: 서비스·설정 삭제, ksoftirqd nice 0. 수신 링은 재부팅하면 기본값으로 돌아감"
}

[ "$(id -u)" = 0 ] || die "root로 실행 (sudo)"
case "${1:-install}" in
  install) do_install ;;
  apply) apply ;;
  check) check ;;
  uninstall) do_uninstall ;;
  *) die "모르는 명령: $1 (install | apply | check | uninstall)" ;;
esac
