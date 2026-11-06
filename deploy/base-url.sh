#!/usr/bin/env bash
# 与 McpResourceUri 的部署边界一致；不通过 DNS 判断 HTTP 是否回环。
ptmcp_ipv4_literal() {
  local part parts
  [[ $1 != *. ]] || return 1
  IFS=. read -r -a parts <<< "$1"
  [ "${#parts[@]}" -eq 4 ] || return 1
  for part in "${parts[@]}"; do
    [[ $part =~ ^[0-9]{1,3}$ ]] && ((10#$part <= 255)) || return 1
  done
}

ptmcp_ipv6_literal() {
  local address=$1 prefix suffix part parts compressed=0
  if [[ $address == *.* ]]; then
    ptmcp_ipv4_literal "${address##*:}" || return 1
    address=${address%:*}:0:0
  fi
  if [[ $address == *::* ]]; then
    compressed=1
    prefix=${address%%::*}
    suffix=${address#*::}
    [[ $suffix != *::* ]] || return 1
    address=$prefix
    [ -z "$suffix" ] || address=${address:+$address:}$suffix
  fi
  parts=()
  if [ -n "$address" ]; then
    [[ $address != :* && $address != *: ]] || return 1
    IFS=: read -r -a parts <<< "$address"
    for part in "${parts[@]}"; do
      [[ $part =~ ^[0-9a-fA-F]{1,4}$ ]] || return 1
    done
  fi
  if [ "$compressed" -eq 1 ]; then
    [ "${#parts[@]}" -lt 8 ]
  else
    [ "${#parts[@]}" -eq 8 ]
  fi
}

# 输出无尾斜杠的 BASE 与入口 HTTP_PORT。HTTPS 由外部 TLS 入口转到内部 8080。
ptmcp_parse_base_url() {
  local host port scheme label labels
  local pattern='^(https?)://(\[[0-9a-fA-F:.]+\]|[a-zA-Z0-9.-]+)(:([0-9]{1,5}))?/?$'
  [[ $1 =~ $pattern ]] || return 1
  scheme=${BASH_REMATCH[1]}
  host=${BASH_REMATCH[2]}
  port=${BASH_REMATCH[4]}
  if [[ $host == \[* ]]; then
    ptmcp_ipv6_literal "${host:1:${#host}-2}" || return 1
  elif [[ $host == *.* && $host =~ ^[0-9.]+$ ]]; then
    ptmcp_ipv4_literal "$host" || return 1
  else
    IFS=. read -r -a labels <<< "$host"
    for label in "${labels[@]}"; do
      [[ $label =~ ^[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?$ ]] || return 1
    done
    # java.net.URI 的多标签主机要求顶级标签以字母开头。
    label=${host%.}
    if [[ $label == *.* ]]; then
      [[ ${label##*.} =~ ^[a-zA-Z] ]] || return 1
    fi
  fi
  if [ -n "$port" ]; then
    port=$((10#$port))
    ((port >= 1 && port <= 65535)) || return 1
  fi
  if [ "$scheme" = http ]; then
    case "${host,,}" in localhost|127.0.0.1|'[::1]') ;; *) return 1 ;; esac
    HTTP_PORT=${port:-80}
  else
    HTTP_PORT=8080
  fi
  BASE=${1%/}
}
