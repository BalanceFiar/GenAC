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
alerts:
  enabled: true
  console-alerts: true
  alert-format: "&8[&bGenAC&8] &f{player} &8failed &f{check} &8(&f{vl}&8) &8- &f{details}"

checks:
  wallhit:
    enabled: true
    max-violations: 5
  killaurarotationa:
    enabled: true
    max-violations: 10
  killaurarrotationb:
    enabled: true
    max-violations: 8
  killaurarotationc:
    enabled: true
    max-violations: 15
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

