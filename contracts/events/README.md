# Event schemas

- `envelope.schema.json` wraps every event.
- Each event has one schema per major version: `<producer>/<name>.v<N>.schema.json`, for example `core/system.pinged.v1.schema.json` for `core.system.pinged`, version 1.
- A compatible change (a new optional field) edits the file in place. A breaking change adds a new version file; the old one stays until no consumer uses it.
- Payloads never contain names, phone numbers, emails, free text or health data.
