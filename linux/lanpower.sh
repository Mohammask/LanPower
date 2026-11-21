#!/usr/bin/env bash

# ==========================================================
# LanPower Linux Agent - Smart Installer & Manager
# Compatible with local execution & `curl -fsSL ... | sudo bash`
#
# NOTE: this file MUST keep Unix (LF) line endings.
# Made with <3 by Mohammask - https://github.com/Mohammask/LanPower
# ==========================================================

AGENT_BIN="/usr/local/bin/lan-power-agent.py"
CONFIG_DIR="/etc/lan-power-agent"
CONFIG_FILE="$CONFIG_DIR/config.json"
FW_STATE_FILE="$CONFIG_DIR/firewall.rules"
AGENT_SERVICE="/etc/systemd/system/lan-power-agent.service"
WOL_SERVICE="/etc/systemd/system/lan-power-wol.service"
FW_SUMMARY="Not configured"

# Colors (safe fallback if terminal doesn't support colors)
if [ -t 1 ]; then
  RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; BOLD='\033[1m'; GRAY='\033[90m'; NC='\033[0m'
else
  RED=''; GREEN=''; YELLOW=''; BLUE=''; BOLD=''; GRAY=''; NC=''
fi

info()    { echo -e "${BLUE}[INFO]${NC} $1"; }
success() { echo -e "${GREEN}[OK]${NC} $1"; }
warn()    { echo -e "${YELLOW}[WARN]${NC} $1"; }
err()     { echo -e "${RED}[ERROR]${NC} $1"; }

# read_input PROMPT VARNAME
# Returns 0 on success and 1 on EOF / unreadable input (Ctrl+D, closed stdin, no TTY).
# Every caller MUST handle a non-zero return so that no loop can ever spin forever.
read_input() {
  local _prompt="$1"
  local _var="$2"
  local _line="" _rc=0

  # Prefer the controlling terminal (needed for `curl ... | sudo bash`),
  # but only if it can really be opened (readable file mode is not enough).
  if [ -r /dev/tty ] && { : < /dev/tty; } 2>/dev/null; then
    IFS= read -r -p "$_prompt" _line < /dev/tty || _rc=$?
  else
    printf '%s' "$_prompt" >&2
    IFS= read -r _line || _rc=$?
  fi

  # EOF with nothing typed => report failure to the caller.
  if [ "$_rc" -ne 0 ] && [ -z "$_line" ]; then
    printf -v "$_var" '%s' ""
    echo "" >&2
    return 1
  fi

  _line=${_line%$'\r'}
  printf -v "$_var" '%s' "$_line"
  return 0
}

# On EOF the safe answer is always "no" (nothing gets installed / removed by accident).
confirm_yes() {
  local _prompt="$1"
  local _ans=""
  read_input "$_prompt [Y/n]: " _ans || return 1
  case "$_ans" in
    [nN]|[nN][oO]) return 1 ;;
    *) return 0 ;;
  esac
}

confirm_no() {
  local _prompt="$1"
  local _ans=""
  read_input "$_prompt [y/N]: " _ans || return 1
  case "$_ans" in
    [yY]|[yY][eE][sS]) return 0 ;;
    *) return 1 ;;
  esac
}

require_root() {
  if [ "${EUID:-$(id -u)}" -ne 0 ]; then
    err "Root privileges are required. Please run with sudo."
    exit 1
  fi
}

check_systemd() {
  if ! command -v systemctl >/dev/null 2>&1 || [ ! -d /run/systemd/system ]; then
    err "systemd is required but was not detected on this system."
    return 1
  fi
  return 0
}

is_installed() {
  [ -f "$AGENT_BIN" ] && [ -f "$CONFIG_FILE" ] && [ -f "$AGENT_SERVICE" ]
}

detect_pkg_manager() {
  if command -v apt-get >/dev/null 2>&1; then echo "apt"
  elif command -v dnf >/dev/null 2>&1; then echo "dnf"
  elif command -v yum >/dev/null 2>&1; then echo "yum"
  elif command -v pacman >/dev/null 2>&1; then echo "pacman"
  elif command -v zypper >/dev/null 2>&1; then echo "zypper"
  else echo "unknown"
  fi
}

install_package() {
  local _pkg="$1"
  local _pm
  _pm=$(detect_pkg_manager)
  case "$_pm" in
    apt)    apt-get update -qq && apt-get install -y "$_pkg" ;;
    dnf)    dnf install -y "$_pkg" ;;
    yum)    yum install -y "$_pkg" ;;
    pacman) pacman -Sy --noconfirm "$_pkg" ;;
    zypper) zypper --non-interactive install "$_pkg" ;;
    *)
      err "Unsupported package manager. Please install '$_pkg' manually."
      return 1
      ;;
  esac
}

get_config_val() {
  local _key="$1"
  if [ ! -f "$CONFIG_FILE" ] || ! command -v python3 >/dev/null 2>&1; then
    echo ""
    return
  fi
  python3 -c '
import json, sys
try:
    with open(sys.argv[1], "r", encoding="utf-8") as f:
        val = json.load(f).get(sys.argv[2], "")
        print("" if val is None else val)
except Exception:
    print("")
' "$CONFIG_FILE" "$_key" 2>/dev/null
}

is_port_free() {
  local _port="$1"
  python3 -c '
import socket, sys
port = int(sys.argv[1])
c = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
c.settimeout(0.3)
try:
    if c.connect_ex(("127.0.0.1", port)) == 0:
        sys.exit(1)
finally:
    c.close()

s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
try:
    s.bind(("0.0.0.0", port))
    sys.exit(0)
except OSError:
    sys.exit(1)
finally:
    s.close()
' "$_port" 2>/dev/null
}

# The probe talks to 127.0.0.1 and must never be routed through a system proxy
# (http_proxy / https_proxy variables), hence the empty ProxyHandler.
wait_agent_ready() {
  local _port="$1"
  local _i
  for _i in 1 2 3 4 5 6; do
    sleep 1
    if python3 -c 'import json,sys,urllib.request; o=urllib.request.build_opener(urllib.request.ProxyHandler({})); d=json.loads(o.open(f"http://127.0.0.1:{sys.argv[1]}/status",timeout=2).read().decode("utf-8")); sys.exit(0 if d.get("service")=="lan-power-agent" else 1)' "$_port" >/dev/null 2>&1; then
      return 0
    fi
  done
  return 1
}

detect_default_iface() {
  local _iface="" _p _name
  if command -v ip >/dev/null 2>&1; then
    _iface=$(ip -o -4 route show to default 2>/dev/null | awk '{print $5}' | head -n1)
  fi
  if [ -z "$_iface" ]; then
    for _p in /sys/class/net/*; do
      _name=$(basename "$_p")
      if [ "$_name" != "lo" ] && [ -d "$_p" ]; then
        _iface="$_name"
        break
      fi
    done
  fi
  echo "$_iface"
}

get_iface_ip() {
  local _iface="$1"
  local _ip=""
  if command -v ip >/dev/null 2>&1; then
    _ip=$(ip -o -4 addr show "$_iface" 2>/dev/null | awk '{print $4}' | cut -d/ -f1 | head -n1)
  fi
  echo "${_ip:-no-ip}"
}

get_iface_label() {
  local _iface="$1"
  if [ ! -e "/sys/class/net/$_iface/device" ]; then
    echo "Virtual"
    return
  fi

  local _type="Ethernet"
  if [ -d "/sys/class/net/$_iface/wireless" ] || [ -d "/sys/class/net/$_iface/phy80211" ]; then
    _type="Wi-Fi"
  fi

  local _devpath=""
  _devpath=$(readlink -f "/sys/class/net/$_iface/device" 2>/dev/null || true)
  if echo "$_devpath" | grep -q "/usb"; then
    echo "$_type / USB"
  else
    echo "$_type"
  fi
}

# ----------------------------------------------------------
# Firewall helpers: LAN-only rules that are recorded in
# $FW_STATE_FILE (backend|subnet|port) so that stale rules can be
# removed when the port changes and when LanPower is uninstalled.
# ----------------------------------------------------------

# Prints the IPv4 subnet(s) (CIDR, one per line) of the given interface.
# Safe fallback when they cannot be determined: the private RFC1918 ranges
# only - the port is never opened to "any" source.
get_iface_subnets() {
  local _iface="$1"
  local _cidrs="" _out=""
  if command -v ip >/dev/null 2>&1 && command -v python3 >/dev/null 2>&1; then
    _cidrs=$(ip -o -4 addr show dev "$_iface" 2>/dev/null | awk '{print $4}')
    if [ -n "$_cidrs" ]; then
      # shellcheck disable=SC2086
      _out=$(python3 -c '
import ipaddress, sys
seen = []
for arg in sys.argv[1:]:
    try:
        net = ipaddress.ip_interface(arg).network
    except ValueError:
        continue
    if net.prefixlen >= 32 or net.is_loopback:
        continue
    if str(net) not in seen:
        seen.append(str(net))
print("\n".join(seen))
' $_cidrs 2>/dev/null)
    fi
  fi
  if [ -z "$_out" ]; then
    _out=$'10.0.0.0/8\n172.16.0.0/12\n192.168.0.0/16'
  fi
  printf '%s\n' "$_out"
}

fw_ufw_active() {
  command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -qi "Status: active"
}

fw_firewalld_active() {
  command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state >/dev/null 2>&1
}

fw_firewalld_zone() {
  local _z=""
  _z=$(firewall-cmd --get-zone-of-interface="$1" 2>/dev/null | head -n1)
  case "$_z" in
    ""|*[!A-Za-z0-9_.-]*) _z="" ;;
  esac
  if [ -z "$_z" ]; then
    _z=$(firewall-cmd --get-default-zone 2>/dev/null | head -n1)
  fi
  printf '%s' "$_z"
}

fw_rich_rule() {
  printf 'rule family="ipv4" source address="%s" port port="%s" protocol="tcp" accept' "$1" "$2"
}

# fw_rule_add KEY SUBNET PORT   (KEY = "ufw" or "firewalld:<zone>")
fw_rule_add() {
  local _key="$1" _subnet="$2" _port="$3"
  case "$_key" in
    ufw)
      ufw allow from "$_subnet" to any port "$_port" proto tcp comment "LanPower Agent" </dev/null >/dev/null 2>&1 ||
        ufw allow from "$_subnet" to any port "$_port" proto tcp </dev/null >/dev/null 2>&1
      ;;
    firewalld:*)
      firewall-cmd --permanent --zone="${_key#firewalld:}" --add-rich-rule="$(fw_rich_rule "$_subnet" "$_port")" </dev/null >/dev/null 2>&1
      ;;
    *) return 1 ;;
  esac
}

# fw_rule_remove KEY SUBNET PORT
fw_rule_remove() {
  local _key="$1" _subnet="$2" _port="$3"
  case "$_key" in
    ufw)
      command -v ufw >/dev/null 2>&1 || return 1
      ufw --force delete allow from "$_subnet" to any port "$_port" proto tcp </dev/null >/dev/null 2>&1
      ;;
    firewalld:*)
      command -v firewall-cmd >/dev/null 2>&1 || return 1
      firewall-cmd --permanent --zone="${_key#firewalld:}" --remove-rich-rule="$(fw_rich_rule "$_subnet" "$_port")" </dev/null >/dev/null 2>&1
      ;;
    *) return 1 ;;
  esac
}

fw_record() {
  local _line="$1|$2|$3"
  mkdir -p "$CONFIG_DIR"
  touch "$FW_STATE_FILE"
  chmod 600 "$FW_STATE_FILE"
  grep -qxF "$_line" "$FW_STATE_FILE" 2>/dev/null || echo "$_line" >> "$FW_STATE_FILE"
}

# fw_prune_recorded KEEP_KEY KEEP_PORT KEEP_SUBNETS
# Removes every recorded rule except the ones matching (KEEP_KEY, KEEP_PORT, one of KEEP_SUBNETS).
# Uninstall calls it with KEEP_KEY=none so that every recorded rule is removed.
fw_prune_recorded() {
  local _keep_key="$1" _keep_port="$2" _keep_subnets="$3"
  [ -f "$FW_STATE_FILE" ] || return 0

  local _tmp _key _subnet _port _reload=0
  _tmp=$(mktemp) || return 0

  while IFS='|' read -r _key _subnet _port; do
    [ -n "$_key" ] || continue
    if [ "$_key" = "$_keep_key" ] && [ "$_port" = "$_keep_port" ] && printf '%s\n' "$_keep_subnets" | grep -qxF "$_subnet"; then
      echo "$_key|$_subnet|$_port" >> "$_tmp"
    else
      fw_rule_remove "$_key" "$_subnet" "$_port" || true
      case "$_key" in
        firewalld:*) _reload=1 ;;
      esac
    fi
  done < "$FW_STATE_FILE"

  if [ "$_reload" -eq 1 ] && command -v firewall-cmd >/dev/null 2>&1; then
    firewall-cmd --reload >/dev/null 2>&1 || true
  fi

  if [ -s "$_tmp" ]; then
    cat "$_tmp" > "$FW_STATE_FILE"
    chmod 600 "$FW_STATE_FILE"
  else
    rm -f "$FW_STATE_FILE"
  fi
  rm -f "$_tmp"
}

# fw_all_recorded KEY PORT SUBNETS - true if every subnet already has a recorded rule.
fw_all_recorded() {
  local _key="$1" _port="$2" _subnets="$3" _s
  [ -f "$FW_STATE_FILE" ] || return 1
  [ -n "$_subnets" ] || return 1
  for _s in $_subnets; do
    grep -qxF "$_key|$_s|$_port" "$FW_STATE_FILE" || return 1
  done
  return 0
}

is_private_lan_subnet() {
  python3 -c 'import ipaddress, sys; net = ipaddress.ip_network(sys.argv[1], strict=False); allowed = [ipaddress.ip_network(c) for c in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10", "169.254.0.0/16")]; sys.exit(0 if net.version == 4 and any(net.subnet_of(a) for a in allowed) else 1)' "$1" 2>/dev/null
}

# configure_firewall IFACE PORT  - UFW / firewalld, LAN subnet(s) of IFACE only.
configure_firewall() {
  local _iface="$1" _port="$2"
  local _key="" _name="" _subnets="" _s _zone _added=0

  if fw_ufw_active; then
    _key="ufw"; _name="UFW"
  elif fw_firewalld_active; then
    _zone=$(fw_firewalld_zone "$_iface")
    _key="firewalld:$_zone"; _name="firewalld"
  fi

  _subnets=$(get_iface_subnets "$_iface")

  # Drop rules recorded by earlier runs that no longer match (old port, other subnet, other firewall).
  fw_prune_recorded "${_key:-none}" "$_port" "$_subnets"

  if [ -z "$_key" ]; then
    FW_SUMMARY="No active firewall detected (nothing changed)"
    return 0
  fi

  warn "$_name firewall is active. Port $_port/tcp must be allowed from your LAN."
  info "LAN subnet(s): $(printf '%s' "$_subnets" | tr '\n' ' ')"
  if confirm_yes "Allow TCP port $_port from the LAN subnet(s) only through $_name now?"; then
    for _s in $_subnets; do
      if ! is_private_lan_subnet "$_s"; then
        warn "Subnet $_s is not a private LAN subnet. Skipping automatic firewall rule."
        continue
      fi
      if fw_rule_add "$_key" "$_s" "$_port"; then
        fw_record "$_key" "$_s" "$_port"
        _added=$((_added + 1))
      else
        warn "Could not add $_name rule for $_s. Allow $_port/tcp from that subnet manually."
      fi
    done
    case "$_key" in
      firewalld:*) firewall-cmd --reload >/dev/null 2>&1 || true ;;
    esac
    if [ "$_added" -gt 0 ]; then
      success "$_name rule(s) added for port $_port/tcp (LAN only: $(printf '%s' "$_subnets" | tr '\n' ' '))."
      FW_SUMMARY="$_name: port $_port/tcp allowed from LAN only"
    else
      FW_SUMMARY="Not opened (rule creation failed) - allow $_port/tcp from your LAN manually"
    fi
  else
    if fw_all_recorded "$_key" "$_port" "$_subnets"; then
      FW_SUMMARY="$_name: existing LAN-only rule for $_port/tcp kept"
    else
      FW_SUMMARY="Not opened (declined) - allow $_port/tcp from your LAN manually"
    fi
  fi
}

# ----------------------------------------------------------
# Config writers (PIN travels through the environment, never through argv;
# files are created with 0600 permissions).
# ----------------------------------------------------------

# write_initial_config PYTHON_BIN PORT TOKEN IFACE MAC
write_initial_config() {
  local _py="$1" _port="$2" _token="$3" _iface="$4" _mac="$5"
  ( umask 077
    mkdir -p "$CONFIG_DIR"
    LANPOWER_TOKEN="$_token" "$_py" - "$CONFIG_FILE" "$_port" "$_iface" "$_mac" << 'PY_CFG'
import json, os, sys
path = sys.argv[1]
cfg = {
    "port": int(sys.argv[2]),
    "token": os.environ.get("LANPOWER_TOKEN", ""),
    "interface": sys.argv[3],
    "mac": sys.argv[4],
}
tmp = path + ".tmp"
fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
with os.fdopen(fd, "w", encoding="utf-8") as f:
    json.dump(cfg, f, indent=2)
os.chmod(tmp, 0o600)
os.replace(tmp, path)
PY_CFG
  )
  local _rc=$?
  chmod 700 "$CONFIG_DIR" 2>/dev/null || true
  chmod 600 "$CONFIG_FILE" 2>/dev/null || true
  return "$_rc"
}

# update_config_token NEW_TOKEN
update_config_token() {
  local _token="$1"
  ( umask 077
    LANPOWER_TOKEN="$_token" python3 - "$CONFIG_FILE" << 'PY_UPD'
import json, os, sys
path = sys.argv[1]
with open(path, "r", encoding="utf-8") as f:
    cfg = json.load(f)
cfg["token"] = os.environ.get("LANPOWER_TOKEN", "")
tmp = path + ".tmp"
fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
with os.fdopen(fd, "w", encoding="utf-8") as f:
    json.dump(cfg, f, indent=2)
os.chmod(tmp, 0o600)
os.replace(tmp, path)
PY_UPD
  )
}

write_agent_script() {
  cat << 'PY_EOF' > "$AGENT_BIN"
#!/usr/bin/env python3
import hmac
import json
import os
import shutil
import socket
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CONFIG_PATH = "/etc/lan-power-agent/config.json"
MAX_FAILS = 5
LOCKOUT_SECONDS = 60
MAX_TRACKED_IPS = 500        # soft cap for the failed-attempt table
FAIL_IDLE_SECONDS = 600      # partial failure counters are forgotten after 10 idle minutes

AUTH_LOCK = threading.Lock()
FAILED_ATTEMPTS = {}  # ip -> {"count": int, "locked_until": float, "last_seen": float}


def load_config():
    try:
        with open(CONFIG_PATH, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception as exc:
        sys.stderr.write(f"Failed to load {CONFIG_PATH}: {exc}\n")
        sys.exit(1)


CONFIG = load_config()
PORT = int(CONFIG.get("port", 8888))
TOKEN = str(CONFIG.get("token", "") or "")
INTERFACE = str(CONFIG.get("interface", "") or "")
SAVED_MAC = str(CONFIG.get("mac", "") or "").upper()
SYSTEMCTL = shutil.which("systemctl") or "/usr/bin/systemctl"


def read_mac():
    if INTERFACE:
        path = f"/sys/class/net/{INTERFACE}/address"
        if os.path.exists(path):
            try:
                with open(path, "r", encoding="utf-8") as f:
                    return f.read().strip().upper()
            except Exception:
                pass
    return SAVED_MAC


def get_lockout_remaining(ip):
    now = time.monotonic()
    with AUTH_LOCK:
        entry = FAILED_ATTEMPTS.get(ip)
        if not entry:
            return 0
        remaining = entry["locked_until"] - now
        if remaining > 0:
            return int(remaining) + 1
        if entry["locked_until"] > 0:
            FAILED_ATTEMPTS.pop(ip, None)
        return 0


def _purge_failures_locked(now):
    """Drop stale tracking records. AUTH_LOCK must be held by the caller.

    Active lockouts are never dropped: expired lockouts and idle partial counters
    go first, then (only if still over the cap) the oldest partial counters.
    """
    for ip in list(FAILED_ATTEMPTS):
        entry = FAILED_ATTEMPTS[ip]
        locked_until = entry.get("locked_until", 0.0)
        if locked_until > 0:
            if locked_until <= now:
                del FAILED_ATTEMPTS[ip]          # lockout is over
        elif now - entry.get("last_seen", 0.0) > FAIL_IDLE_SECONDS:
            del FAILED_ATTEMPTS[ip]              # old partial counter
    if len(FAILED_ATTEMPTS) > MAX_TRACKED_IPS:
        idle = sorted(
            (entry.get("last_seen", 0.0), ip)
            for ip, entry in FAILED_ATTEMPTS.items()
            if entry.get("locked_until", 0.0) <= 0
        )
        for _, ip in idle:
            if len(FAILED_ATTEMPTS) <= MAX_TRACKED_IPS:
                break
            del FAILED_ATTEMPTS[ip]


def record_auth_failure(ip):
    now = time.monotonic()
    with AUTH_LOCK:
        if len(FAILED_ATTEMPTS) > MAX_TRACKED_IPS:
            _purge_failures_locked(now)
        entry = FAILED_ATTEMPTS.get(ip)
        if entry is None:
            entry = {"count": 0, "locked_until": 0.0, "last_seen": now}
        entry["count"] += 1
        entry["last_seen"] = now
        if entry["count"] >= MAX_FAILS:
            entry["locked_until"] = now + LOCKOUT_SECONDS
            entry["count"] = 0
        FAILED_ATTEMPTS[ip] = entry


def clear_auth_failures(ip):
    with AUTH_LOCK:
        FAILED_ATTEMPTS.pop(ip, None)


def trigger_action(action):
    sys_action = "suspend" if action == "sleep" else action
    if os.path.exists(SYSTEMCTL):
        cmd = [SYSTEMCTL, sys_action]
    else:
        fallback = "pm-suspend" if sys_action == "suspend" else sys_action
        cmd = [shutil.which(fallback) or f"/usr/sbin/{fallback}"]
    try:
        subprocess.Popen(cmd)
    except Exception as exc:
        sys.stderr.write(f"Failed to run {cmd}: {exc}\n")


class ReusableHTTPServer(ThreadingHTTPServer):
    allow_reuse_address = True
    daemon_threads = True


class Handler(BaseHTTPRequestHandler):
    def setup(self):
        super().setup()
        self.request.settimeout(5.0)

    def log_message(self, format, *args):
        return

    def drain_body(self):
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if 0 < length <= 4096:
                self.rfile.read(length)
        except Exception:
            pass

    def send_json(self, code, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def is_authorized(self):
        if not TOKEN:
            return True
        provided = self.headers.get("X-Auth-Token", "")
        return hmac.compare_digest(provided.encode("utf-8"), TOKEN.encode("utf-8"))

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path == "/status":
            self.send_json(200, {
                "service": "lan-power-agent",
                "version": "1.0",
                "status": "online",
                "hostname": socket.gethostname(),
                "mac": read_mac(),
                "interface": INTERFACE,
                "auth_required": bool(TOKEN),
            })
        else:
            self.send_json(404, {"error": "not_found"})

    def do_POST(self):
        self.drain_body()
        path = self.path.split("?", 1)[0]

        if path not in ("/verify", "/poweroff", "/reboot", "/sleep"):
            self.send_json(404, {"error": "not_found"})
            return

        if self.headers.get("Origin") is not None:
            self.send_json(403, {"ok": False, "error": "browser_requests_not_allowed"})
            return

        client_ip = self.client_address[0]
        if TOKEN:
            retry_after = get_lockout_remaining(client_ip)
            if retry_after > 0:
                self.send_json(429, {
                    "ok": False,
                    "error": "too_many_attempts",
                    "retry_after": retry_after,
                })
                return

            if not self.is_authorized():
                record_auth_failure(client_ip)
                self.send_json(401, {"ok": False, "error": "unauthorized"})
                return

            clear_auth_failures(client_ip)

        if path == "/verify":
            self.send_json(200, {"ok": True, "authorized": True})
            return

        action = path.lstrip("/")
        self.send_json(200, {"ok": True, "action": action})
        threading.Timer(0.8, trigger_action, args=(action,)).start()


if __name__ == "__main__":
    try:
        server = ReusableHTTPServer(("0.0.0.0", PORT), Handler)
    except OSError as exc:
        sys.stderr.write(f"Cannot bind port {PORT}: {exc}\n")
        sys.exit(1)
    server.serve_forever()
PY_EOF
  chmod 755 "$AGENT_BIN"
}

option_install() {
  echo ""
  echo -e "${BOLD}=== Option 1: Full Setup & Installation ===${NC}"
  check_systemd || return

  # 1. Check Python 3 (Required)
  if ! command -v python3 >/dev/null 2>&1; then
    warn "Python 3 is NOT installed (required for the power agent)."
    if confirm_yes "Do you want to install python3 now?"; then
      if ! install_package python3; then
        err "Failed to install python3. Setup aborted."
        return
      fi
      success "python3 installed."
    else
      err "Cannot continue without python3. Setup cancelled."
      return
    fi
  else
    success "python3 is already installed ($(python3 --version 2>&1))."
  fi
  if ! python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 7) else 1)' 2>/dev/null; then
    err "Python 3.7+ is required (ThreadingHTTPServer is not available on older Python versions)."
    return 1
  fi
  local PYTHON_BIN
  PYTHON_BIN=$(command -v python3)

  # 2. Check ethtool (Optional but recommended for Wake-on-LAN)
  local HAS_ETHTOOL=1
  if ! command -v ethtool >/dev/null 2>&1; then
    warn "ethtool is NOT installed (recommended to keep Wake-on-LAN enabled)."
    if confirm_yes "Do you want to install ethtool now?"; then
      if install_package ethtool; then
        success "ethtool installed."
      else
        warn "Failed to install ethtool. Continuing without automated WoL setup."
        HAS_ETHTOOL=0
      fi
    else
      warn "Skipping ethtool. Wake-on-LAN persistence will be skipped."
      HAS_ETHTOOL=0
    fi
  else
    success "ethtool is already installed."
  fi

  # 3. Select Network Interface with type, IP, and MAC display
  local DEFAULT_IFACE
  DEFAULT_IFACE=$(detect_default_iface)
  local -a IFACES=()
  local _p _name
  for _p in /sys/class/net/*; do
    _name=$(basename "$_p")
    [ "$_name" = "lo" ] && continue
    [ -d "$_p" ] && IFACES+=("$_name")
  done

  if [ "${#IFACES[@]}" -eq 0 ]; then
    err "No network interfaces found."
    return
  fi

  # The default must be one of the listed (non-loopback) interfaces.
  local _found=0
  for _name in "${IFACES[@]}"; do
    [ "$_name" = "$DEFAULT_IFACE" ] && _found=1
  done
  [ "$_found" -eq 1 ] || DEFAULT_IFACE="${IFACES[0]}"

  info "Available network interfaces:"
  local _idx _ip _mac _lbl _mark
  for _idx in "${!IFACES[@]}"; do
    _name="${IFACES[$_idx]}"
    _ip=$(get_iface_ip "$_name")
    _mac=$(tr '[:lower:]' '[:upper:]' < "/sys/class/net/$_name/address" 2>/dev/null || echo "UNKNOWN")
    _lbl=$(get_iface_label "$_name")
    _mark=""
    [ "$_name" = "$DEFAULT_IFACE" ] && _mark=" (Default)"
    echo "  [$((_idx + 1))] $_name [$_lbl] - IP: $_ip | MAC: $_mac$_mark"
  done

  local IFACE="" _sel=""
  while true; do
    if ! read_input "Select interface number or name [Enter for $DEFAULT_IFACE]: " _sel; then
      err "Input closed (EOF). Setup cancelled."
      return
    fi
    if [ -z "$_sel" ]; then
      IFACE="$DEFAULT_IFACE"
      break
    elif [[ "$_sel" =~ ^[0-9]+$ ]] && [ "$_sel" -ge 1 ] && [ "$_sel" -le "${#IFACES[@]}" ]; then
      IFACE="${IFACES[$((_sel - 1))]}"
      break
    elif [ "$_sel" = "lo" ]; then
      err "The loopback interface 'lo' cannot be used. Choose a real network interface."
      continue
    elif [[ "$_sel" =~ ^[A-Za-z0-9_.:@-]+$ ]] && [ "$_sel" != "." ] && [ "$_sel" != ".." ] && [ "$_sel" != "lo" ] && [ -d "/sys/class/net/$_sel" ]; then
      IFACE="$_sel"
      break
    fi
    err "Invalid selection. Enter a number (1-${#IFACES[@]}) or a valid interface name."
  done

  local LOCAL_IP MAC_ADDR IFACE_LABEL
  LOCAL_IP=$(get_iface_ip "$IFACE")
  MAC_ADDR=$(tr '[:lower:]' '[:upper:]' < "/sys/class/net/$IFACE/address" 2>/dev/null || echo "UNKNOWN")
  IFACE_LABEL=$(get_iface_label "$IFACE")

  # 4. Choose and validate Port
  local EXISTING_PORT=""
  local AGENT_WAS_RUNNING=0
  if is_installed && systemctl is-active --quiet lan-power-agent.service; then
    AGENT_WAS_RUNNING=1
    EXISTING_PORT=$(get_config_val "port")
  fi

  local DEFAULT_PORT="${EXISTING_PORT:-8888}"
  local PORT=""
  while true; do
    if ! read_input "API Port [$DEFAULT_PORT]: " PORT; then
      err "Input closed (EOF). Setup cancelled."
      return
    fi
    PORT=${PORT:-$DEFAULT_PORT}

    if ! [[ "$PORT" =~ ^[0-9]+$ ]] || [ "$PORT" -lt 1 ] || [ "$PORT" -gt 65535 ]; then
      err "Invalid port. Please enter a number between 1 and 65535."
      continue
    fi

    if [ "$AGENT_WAS_RUNNING" -eq 1 ] && [ "$PORT" = "$EXISTING_PORT" ]; then
      info "Port $PORT is currently used by LanPower Agent and will be reused."
      break
    fi

    if is_port_free "$PORT"; then
      success "Port $PORT is available."
      break
    else
      err "Port $PORT is ALREADY IN USE by another application!"
      if command -v ss >/dev/null 2>&1; then
        ss -tlnp 2>/dev/null | grep -E ":$PORT\b" | head -n 3
      fi
      warn "Please choose a different port."
    fi
  done

  # 5. Optional PIN
  local TOKEN=""
  while true; do
    if ! read_input "Optional PIN/Token (press Enter for NO PIN): " TOKEN; then
      err "Input closed (EOF). Setup cancelled."
      return
    fi
    if [ -z "$TOKEN" ]; then
      break
    fi
    if ! printf '%s' "$TOKEN" | LC_ALL=C grep -qE '^[!-~]{4,64}$'; then
      err "PIN must be 4-64 printable ASCII characters with no spaces."
      continue
    fi
    if [ "${#TOKEN}" -lt 6 ]; then
      warn "PIN is shorter than 6 characters. At least 6 characters is recommended for stronger brute-force protection."
    fi
    break
  done
  if [ -z "$TOKEN" ]; then
    warn "NO PIN is set: anyone on your network who can reach port $PORT can power off or reboot this machine."
    warn "Using a PIN is strongly recommended (you can add one later with option 2)."
  fi

  # 6. Configure Wake-on-LAN safely (never crash on unsupported/virtual NICs)
  local WOL_SUMMARY="Skipped"
  if [ "$IFACE_LABEL" = "Virtual" ]; then
    warn "Interface '$IFACE' is [Virtual]. Skipping Wake-on-LAN."
    info "Status, Shutdown, and Reboot API will still work normally."
    WOL_SUMMARY="Skipped (Virtual)"
  elif [ "$HAS_ETHTOOL" -eq 1 ]; then
    local ETHTOOL_BIN ETH_OUT
    ETHTOOL_BIN=$(command -v ethtool)
    ETH_OUT=$("$ETHTOOL_BIN" "$IFACE" 2>/dev/null || true)

    # NOTE: both patterns are anchored; an unanchored "Wake-on:.*g" would also match
    # the "Supports Wake-on: pumbg" line and wrongly report WoL as already enabled.
    if echo "$ETH_OUT" | grep -qiE '^[[:space:]]*Supports Wake-on:[[:space:]]*[a-z]*g'; then
      if echo "$ETH_OUT" | grep -qiE '^[[:space:]]*Wake-on:[[:space:]]*[a-z]*g'; then
        success "Wake-on-LAN (g) is already enabled on $IFACE."
      else
        if "$ETHTOOL_BIN" -s "$IFACE" wol g 2>/dev/null; then
          success "Wake-on-LAN (g) enabled on $IFACE."
        else
          warn "Could not activate Wake-on-LAN immediately on $IFACE (continuing anyway)."
        fi
      fi

      cat << EOF > "$WOL_SERVICE"
[Unit]
Description=Enable Wake-on-LAN on $IFACE
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
ExecStart=-$ETHTOOL_BIN -s $IFACE wol g

[Install]
WantedBy=multi-user.target
EOF
      systemctl daemon-reload
      systemctl enable lan-power-wol.service >/dev/null 2>&1
      systemctl start lan-power-wol.service >/dev/null 2>&1 || true
      success "Persistent Wake-on-LAN service configured."
      WOL_SUMMARY="Enabled (Magic Packet 'g')"

      # Also preserve WoL in NetworkManager if active (prevents Ubuntu/Fedora from resetting it)
      if command -v nmcli >/dev/null 2>&1 && systemctl is-active --quiet NetworkManager 2>/dev/null; then
        local NM_CONN
        NM_CONN=$(nmcli -t -f DEVICE,NAME connection show --active 2>/dev/null | awk -F: -v dev="$IFACE" '$1 == dev {sub(/^[^:]*:/, ""); print; exit}')
        if [ -n "$NM_CONN" ]; then
          if nmcli connection modify "$NM_CONN" 802-3-ethernet.wake-on-lan magic >/dev/null 2>&1; then
            success "NetworkManager connection '$NM_CONN' updated to preserve Wake-on-LAN."
          fi
        fi
      fi
    else
      warn "Interface '$IFACE' [$IFACE_LABEL] does not report Wake-on-LAN (g) support."
      info "Skipping WoL service. Status, Shutdown, and Reboot API will still work normally."
      WOL_SUMMARY="Unsupported by driver"
    fi
  fi

  # 7. Save configuration JSON (PIN via environment variable, file mode 0600, dir mode 0700)
  if ! write_initial_config "$PYTHON_BIN" "$PORT" "$TOKEN" "$IFACE" "$MAC_ADDR"; then
    err "Failed to write $CONFIG_FILE."
    return
  fi

  # 8. Install Python Agent & systemd service
  write_agent_script

  cat << EOF > "$AGENT_SERVICE"
[Unit]
Description=LanPower Control JSON API Agent
After=network-online.target
Wants=network-online.target

[Service]
ExecStart=$PYTHON_BIN $AGENT_BIN
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF

  systemctl daemon-reload
  systemctl enable lan-power-agent.service >/dev/null 2>&1
  if ! systemctl restart lan-power-agent.service; then
    err "Failed to start lan-power-agent.service. Check logs: journalctl -u lan-power-agent -n 20"
    return
  fi

  # Live HTTP verification (matches Windows agent verification)
  if ! wait_agent_ready "$PORT"; then
    err "Agent service started, but http://127.0.0.1:$PORT/status did not respond."
    warn "Check logs: journalctl -u lan-power-agent -n 20"
    return
  fi

  # 9. Firewall (UFW & firewalld): LAN subnet(s) of the selected interface only,
  #    rules are recorded so they can be replaced on port change and removed on uninstall.
  configure_firewall "$IFACE" "$PORT"

  echo ""
  echo -e "${GREEN}========================================${NC}"
  echo -e "${BOLD} LanPower Setup Completed Successfully!${NC}"
  echo " Hostname : $(hostname)"
  echo " Interface: $IFACE [$IFACE_LABEL]"
  echo " IP       : $LOCAL_IP"
  echo " MAC      : $MAC_ADDR"
  echo " Port     : $PORT"
  echo " PIN Auth : $([ -n "$TOKEN" ] && echo "Enabled" || echo "Disabled (None)")"
  echo " Wake     : $WOL_SUMMARY"
  echo " Firewall : $FW_SUMMARY"
  echo " Test URL : http://${LOCAL_IP}:$PORT/status"
  echo -e "${GREEN}========================================${NC}"
}

option_change_pin() {
  echo ""
  echo -e "${BOLD}=== Option 2: Change / Remove PIN ===${NC}"
  if ! is_installed; then
    warn "LanPower Agent is NOT installed yet!"
    info "Please run Option 1 (Full Setup) first before setting a PIN."
    if confirm_yes "Would you like to run Option 1 (Full Setup) now?"; then
      option_install
    fi
    return
  fi

  if ! command -v python3 >/dev/null 2>&1; then
    err "python3 is missing. Please run Option 1 to repair the installation."
    return
  fi

  local CURRENT_TOKEN PORT
  CURRENT_TOKEN=$(get_config_val "token")
  PORT=$(get_config_val "port")
  info "Current PIN status: $([ -n "$CURRENT_TOKEN" ] && echo "Enabled" || echo "Disabled (None)")"

  local NEW_TOKEN=""
  while true; do
    if ! read_input "Enter new PIN (leave empty and press Enter to REMOVE PIN): " NEW_TOKEN; then
      err "Input closed (EOF). PIN left unchanged."
      return
    fi
    if [ -z "$NEW_TOKEN" ]; then
      break
    fi
    if ! printf '%s' "$NEW_TOKEN" | LC_ALL=C grep -qE '^[!-~]{4,64}$'; then
      err "PIN must be 4-64 printable ASCII characters with no spaces."
      continue
    fi
    if [ "${#NEW_TOKEN}" -lt 6 ]; then
      warn "PIN is shorter than 6 characters. At least 6 characters is recommended for stronger brute-force protection."
    fi
    break
  done
  if [ -z "$NEW_TOKEN" ]; then
    warn "NO PIN will be set: anyone on your network who can reach the agent port can power off or reboot this machine."
  fi

  if update_config_token "$NEW_TOKEN"; then
    chmod 600 "$CONFIG_FILE"
    systemctl restart lan-power-agent.service
    if wait_agent_ready "${PORT:-8888}"; then
      if [ -n "$NEW_TOKEN" ]; then
        success "PIN updated and agent restarted."
      else
        success "PIN removed (authentication disabled) and agent restarted."
      fi
    else
      err "Config updated, but agent did not respond after restart. Check: journalctl -u lan-power-agent -n 20"
    fi
  else
    err "Failed to update $CONFIG_FILE."
  fi
}

option_status() {
  echo ""
  echo -e "${BOLD}=== LanPower Status ===${NC}"
  if ! is_installed; then
    warn "Status: Not installed."
    return
  fi

  local PORT IFACE MAC TOKEN IP STATE IFACE_LABEL WOL_STATE
  PORT=$(get_config_val "port")
  IFACE=$(get_config_val "interface")
  MAC=$(get_config_val "mac")
  TOKEN=$(get_config_val "token")
  IP=$(get_iface_ip "$IFACE")
  IFACE_LABEL=$(get_iface_label "$IFACE")
  STATE=$(systemctl is-active lan-power-agent.service 2>/dev/null || true)
  STATE=${STATE:-inactive}

  WOL_STATE="Unknown"
  if command -v ethtool >/dev/null 2>&1 && [ -n "$IFACE" ]; then
    WOL_STATE=$(ethtool "$IFACE" 2>/dev/null | awk '/^[[:space:]]*Wake-on:/ {print $2}')
    WOL_STATE=${WOL_STATE:-Unsupported}
  fi

  echo " Agent Service : $STATE"
  echo " Hostname      : $(hostname)"
  echo " Interface     : $IFACE [$IFACE_LABEL] ($IP)"
  echo " MAC Address   : $MAC"
  echo " API Port      : $PORT"
  echo " PIN Auth      : $([ -n "$TOKEN" ] && echo "Enabled" || echo "Disabled")"
  echo " Wake-on-LAN   : $WOL_STATE (g = enabled)"
}

option_uninstall() {
  echo ""
  echo -e "${BOLD}=== Uninstall LanPower ===${NC}"
  if ! is_installed && [ ! -f "$WOL_SERVICE" ]; then
    warn "LanPower is not installed."
    return
  fi
  if ! confirm_no "Are you sure you want to completely remove LanPower Agent and its services?"; then
    info "Uninstall cancelled."
    return
  fi
  systemctl disable --now lan-power-agent.service >/dev/null 2>&1 || true
  systemctl disable --now lan-power-wol.service >/dev/null 2>&1 || true

  # Remove the firewall rules this script created (must run before CONFIG_DIR is deleted).
  if [ -f "$FW_STATE_FILE" ]; then
    fw_prune_recorded "none" "" ""
    success "Firewall rules created by LanPower were removed."
  fi

  rm -f "$AGENT_SERVICE" "$WOL_SERVICE" "$AGENT_BIN"
  rm -rf "$CONFIG_DIR"
  systemctl daemon-reload >/dev/null 2>&1 || true
  success "LanPower has been completely uninstalled."
}

main_menu() {
  require_root
  while true; do
    echo ""
    echo -e "${BOLD}========================================${NC}"
    echo -e "${BOLD}       LanPower Linux Agent Manager     ${NC}"
    echo -e "${BOLD}========================================${NC}"
    echo "  1) Install / Setup Everything"
    echo "  2) Change or Remove PIN"
    echo "  3) Show Status & Connection Info"
    echo "  4) Uninstall"
    echo "  0) Exit"
    echo -e "${GRAY}----------------------------------------${NC}"
    echo -e "${GRAY}  Made with <3 by Mohammask${NC}"
    echo -e "${GRAY}  github.com/Mohammask/LanPower${NC}"
    echo ""
    local CHOICE=""
    if ! read_input "Select an option [0-4]: " CHOICE; then
      echo ""
      info "Input closed (EOF). Exiting."
      exit 0
    fi
    case "$CHOICE" in
      1) option_install ;;
      2) option_change_pin ;;
      3) option_status ;;
      4) option_uninstall ;;
      0) echo "Bye!"; exit 0 ;;
      *) warn "Invalid option. Please choose 0-4." ;;
    esac
  done
}

main_menu