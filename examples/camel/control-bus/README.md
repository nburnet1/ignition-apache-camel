# Control a running route

This example uses Camel Control Bus to suspend, resume, and inspect a timer route.

## Files

- Route: [`../control-bus.yaml`](../control-bus.yaml)
- Setup and inspection helper:
  [`../project/control_bus.py`](../project/control_bus.py)
- Installer: `scripts/camel-controlbus-example.py`

## Quick start

```sh
python3 scripts/camel-controlbus-example.py
```

Watch tags under `[default]CamelExamples/ControlBus/`:

| Tag | Result |
| --- | --- |
| `Heartbeat` | New timestamp every two seconds |
| `TickCount` | Advances with each timer exchange |
| `RouteState` | `Started` or `Suspended` |
| `Command` | Write `suspend`, `resume`, or `status` |

While suspended, `Heartbeat` and `TickCount` stop advancing. The status-monitor
route keeps running so `RouteState` can still report `Suspended`.
