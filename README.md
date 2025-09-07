# 🛡️ GenAC — AntiCheat for Minecraft 1.21.4

GenAC is an anti-cheat plugin for Minecraft 1.21.4 servers.  
---

[![💾 Download](https://img.shields.io/badge/💾_Download-GenAC.jar-brightgreen?style=for-the-badge)](https://github.com/BalanceFiar/GenAC/releases/latest)
[![💖 Donate](https://img.shields.io/badge/💖_Donate-Support-orange?style=for-the-badge)](https://www.donationalerts.com/r/balancefiar)
[![✈️ Telegram](https://img.shields.io/badge/✈️_Telegram-Join-blue?style=for-the-badge)](https://t.me/genanticheat)


---

## 📥 Installation

1. Download **GenAC.jar**
2. Place it in your server `plugins` folder
3. Restart the server
4. Edit the configuration in `config.yml`

---

## ⚙️ Configuration (short version)

```yaml
general:
  debug: false
  language: "en"

alerts:
  staff-alerts: true
  console-alerts: true
  webhook-url: "" #soon

checks:
  killaurarotationa:
    enabled: true
    experimental: false
    punishment:
      kick:
        enabled: true
        threshold: 6
      ban:
        enabled: false
        threshold: 12
        duration: 3600

  killaurarotationb:
    enabled: true
    experimental: false
    punishment:
      kick:
        enabled: true
        threshold: 5
      ban:
        enabled: true
        threshold: 10
        duration: 3600
  wallinteract:
          enabled: true
          experimental: false
          punishment:
            kick:
              enabled: true
              threshold: 5
            ban:
              enabled: true
              threshold: 10
              duration: 3600






punishments:
  kick-message: "§cYou have been kicked for suspicious activity: {reason} ({violations} violations)"
  ban-command: "tempban {player} {duration} Suspicious activity: {reason} ({violations} violations)"

functions:

