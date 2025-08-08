# GenAC

GenAC is a powerful anti-cheat plugin for Minecraft 1.16.5 servers, designed to detect and prevent various types of cheating behaviors with advanced detection algorithms.


## Installation

1. Download the latest GenAC.jar file
2. Place it in your server's `plugins` folder
3. Restart your server
4. Configure the plugin using the generated config file

## Configuration

The plugin generates a `config.yml` file with customizable settings for each check:

```yaml
# GenAC Configuration
general:
  enabled: true
  debug: false
  auto-update: true

alerts:
  enabled: true
  console-alerts: true
  broadcast-alerts: true
  alert-format: "&8[&bGenAC&8] &f{player} &8failed &f{check} &8(&f{vl}&8) &8- &f{details}"

checks:
  wallhit:
    enabled: true
    max-violations: 5
    experimental: false
  killaurarotationa:
    enabled: true
    max-violations: 10
    experimental: false
  killaurarotationb:
    enabled: true
    max-violations: 8
    experimental: true
  invmode:
    enabled: true
    max-violations: 5
    experimental: false


punishments:
  enabled: true
  kick-threshold: 20
  ban-threshold: 50
  kick-command: "kick {player} GenAC: Suspicious activity detected"
  ban-command: "ban {player} GenAC: Cheating detected"

database:
  enabled: false
  type: "sqlite"
  host: "localhost"
  port: 3306
  database: "genac"
  username: "root"
  password: ""

performance:
  max-checks-per-tick: 50
  async-processing: true
  thread-pool-size: 4
```

## Commands

- `/genac info` - Show plugin information
- `/genac checks` - List all checks and their status
- `/genac alerts [count]` - Show recent alerts
- `/genac reload` - Reload configuration
- `/genac toggle <check>` - Toggle a specific check
- `/genac violations <player>` - Show player violation levels

## Permissions

- `genac.admin` - Access to all GenAC commands
- `genac.alerts` - Receive alert notifications
- `genac.bypass` - Bypass all checks




## Technical Details



### Performance

- Lightweight and optimized for production servers
- Minimal impact on server performance
- Efficient data structures and algorithms
- Configurable check intervals and thresholds

## Support

For support, bug reports, or feature requests, please contact the development team.

## Version

Current version: 1.0.0
Compatible with: Minecraft 1.16.5 (Spigot/Paper)

