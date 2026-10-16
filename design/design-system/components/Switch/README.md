# Switch

An on/off setting that takes effect immediately (reminders, discreet mode, AI notes). Mobile only; admin settings use a checkbox with a Save button.

**Consumer provides:** a native checkbox with `role="switch"`, the setting name, and a meta line that states the current effect ("1 hour before, by push", "Off").

- Track 48×28, `radius` 14px; thumb 22px `surface-card`.
- On: `brand` track, thumb right. Off: `line-control` track, thumb left.
- The meta line repeats the state in words, so the switch never relies on colour.

Don't use a switch for something that needs a confirm or a save; use OptionCard and a Button.
