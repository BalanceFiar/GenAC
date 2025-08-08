# 🛡️ GenAC — AntiCheat for Minecraft 1.16.5

GenAC is an anti-cheat plugin for Minecraft 1.16.5 servers.  
It detects and prevents common cheating methods with configurable checks and punishments.

---

[![💾 Download](https://img.shields.io/badge/💾_Download-GenAC.jar-brightgreen?style=for-the-badge)](#installation)
[![⚙️ Config](https://img.shields.io/badge/⚙️_Config-View-blue?style=for-the-badge)](#configuration)
[![💖 Donate](https://img.shields.io/badge/💖_Donate-Support-orange?style=for-the-badge)](https://www.donationalerts.com/r/balancefiar)

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

checks:
  wallhit:
    enabled: true
    punishment:
      kick:
        enabled: true
        threshold: 3
      ban:
        enabled: true
        threshold: 6
        duration: 1800

  reach:
    enabled: true
    cancel: true
    punishment:
      kick:
        enabled: true
        threshold: 5

  killaurarotationb:
    enabled: true
    punishment:
      kick:
        enabled: true
        threshold: 5
      ban:
        enabled: true
        threshold: 10

...
