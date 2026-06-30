# Launching rpcsx games from an emulation frontend

rpcsx can be launched directly into a specific PS3 game by an Android emulation frontend
(Daijisho, ES-DE, Pegasus, Beacon, ...). The emulator activity is exported and accepts the
game either as the intent **data URI** (`ACTION_VIEW`) or as a **string extra**, matching the
conventions used across standalone Android emulators.

## Launch component

```
net.rpcsx.clanker/net.rpcsx.RPCSXActivity
```

The activity is `exported="true"`, so an explicit-component intent reaches it regardless of
action. It also declares an `ACTION_VIEW` intent-filter scoped to PS3 disc/package extensions
(`.iso`, `.pkg`) for file-manager "Open with" and implicit launches.

## Passing the game

Provide the game as **either**:

- the intent **data URI** — `-d <uri>` (used with `-a android.intent.action.VIEW`), or
- a string **extra**. The documented key is **`net.rpcsx.GAME`**; for convenience these
  keys other emulators use are also accepted, in priority order:
  `path`, `net.rpcsx.GAME`, `bootPath`, `game_dir`, `iso_uri`, `ROM`, `AutoStartFile`,
  `SelectedGame`, `game`.

The value may be a **raw filesystem path** (most reliable) or a **`content://` SAF URI**. For
a content URI the launching frontend must set `FLAG_GRANT_READ_URI_PERMISSION`; rpcsx maps the
common external-storage SAF URI back to a real path and best-effort-resolves others. rpcsx
boots from the same path the in-app library uses (the installed game folder, or an EBOOT/disc
file). Add `--activity-clear-task --activity-clear-top` so a re-launch starts the new game
cleanly while another is running.

## Examples

### adb / am

```
# data URI (raw path)
am start -n net.rpcsx.clanker/net.rpcsx.RPCSXActivity \
  -a android.intent.action.VIEW -d file:///storage/emulated/0/PS3/MyGame \
  --activity-clear-task --activity-clear-top

# string extra
am start -n net.rpcsx.clanker/net.rpcsx.RPCSXActivity \
  --es net.rpcsx.GAME /storage/emulated/0/PS3/MyGame \
  --activity-clear-task --activity-clear-top
```

### Daijisho (player "Start arguments")

```
-n net.rpcsx.clanker/net.rpcsx.RPCSXActivity
-a android.intent.action.VIEW
-d {file.uri}
--activity-clear-task --activity-clear-top
```

(or `--es net.rpcsx.GAME {file.path}` instead of `-d {file.uri}`)

### ES-DE (`es_systems.xml` + `es_find_rules.xml`)

```xml
<!-- es_systems.xml command -->
<command label="rpcsx (Standalone)">%EMULATOR_RPCSX% %ACTIVITY_CLEAR_TASK% %ACTIVITY_CLEAR_TOP% %ACTION%=android.intent.action.VIEW %DATA%=%ROMPROVIDER%</command>

<!-- es_find_rules.xml -->
<emulator name="RPCSX">
  <rule type="androidpackage">
    <entry>net.rpcsx.clanker/net.rpcsx.RPCSXActivity</entry>
  </rule>
</emulator>
```
