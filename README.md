# LanPower

Lightweight local Wake-on-LAN, Sleep, Reboot, and Shutdown controller for Linux & Windows with a Material 3 Android app and zero external agent dependencies.

LanPower provides a unified, cross-platform power management agent designed for home labs, office workstations, and personal machines across your local network.

---

## Quick Install

### Linux (Ubuntu, Debian, Fedora, Arch, CentOS)
Run with root privileges:
```bash
curl -fsSL https://raw.githubusercontent.com/Mohammask/LanPower/main/linux/lanpower.sh | sudo bash
```

### Windows 10 / 11
Run from an elevated (Administrator) PowerShell prompt:
```powershell
irm https://raw.githubusercontent.com/Mohammask/LanPower/main/windows/lanpower.ps1 | iex
```

Both interactive scripts provide a CLI manager with options to install as a persistent system service, change or remove authentication PINs, inspect live adapter and WoL status, and cleanly uninstall.

---

## Security Architecture

LanPower is built with safety, privacy, and defensive posture in mind:

- **LAN-Only Firewall Rules:** The installer automatically configures UFW / firewalld on Linux and Windows Defender Firewall to restrict agent port access strictly to your local network subnet.
- **Optional PIN Authentication:** Protect actions with a custom PIN. Comparison uses constant-time validation (`hmac.compare_digest` in Python, bitwise XOR in PowerShell) to mitigate timing attacks.
- **Brute-Force Rate Limiting:** 5 consecutive failed authentication attempts automatically lock out the originating IP address for 60 seconds (HTTP `429 Too Many Requests` with `retry_after` countdown header).
- **Hardened Configuration Permissions:** The configuration storing the token is locked to file mode `0600` (directory `0700`) on Linux, and secured with strict SYSTEM and Administrator-only ACLs via `icacls` on Windows.
- **Trusted LAN Scope:** The agent uses lightweight local HTTP intended exclusively for trusted home or private local area networks. Do not expose the agent port to the public internet.

---

## API Endpoints

All actions respond with JSON payloads. When PIN authentication is enabled, pass your token in the `X-Auth-Token` request header.

| Method | Endpoint | Description | Sample Response |
|---|---|---|---|
| `GET` | `/status` | Agent health, hostname, MAC address, interface, and auth requirement | `{"service":"lan-power-agent","status":"online","hostname":"PC","mac":"...","auth_required":true}` |
| `POST` | `/verify` | Validate the configured PIN without performing any power action | `{"ok":true,"authorized":true}` |
| `POST` | `/sleep` | Put the host machine into sleep / suspend mode | `{"ok":true,"action":"sleep"}` |
| `POST` | `/reboot` | Safely reboot the operating system | `{"ok":true,"action":"reboot"}` |
| `POST` | `/poweroff` | Safely shut down the operating system | `{"ok":true,"action":"poweroff"}` |

---

## Android App

The companion LanPower Android application (Material 3) allows you to discover, manage, wake, sleep, reboot, and turn off your networked machines with a tap.

- Source code available in `android/`
- Prebuilt APKs available under [GitHub Releases](https://github.com/Mohammask/LanPower/releases)

---

## License

This project is licensed under the [MIT License](LICENSE).

---

Made with ❤️ by [Mohammask](https://github.com/Mohammask)
