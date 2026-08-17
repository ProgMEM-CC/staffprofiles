# staffprofiles

staffprofiles is a Minecraft plugin that lets staff members disguise themselves as a different
game profile (username and UUID) while playing, and switch back at any time.
It's an evolution of my proof-of-concept plugin [hostprofiles](https://github.com/MCMDEV/hostprofiles)
into a more practical, specialized solution for staff management.

## Non-technical example

The plugin essentially does the following when a player uses the `/sprofile` command:

1. An admin adds a profile (a username + UUID pair) to the plugin's config using `/sprofile add`
2. A staff member runs `/sprofile login <username>` to start using that profile
3. The player is disconnected and reconnects, and the server treats them as the profile's account
4. Running `/sprofile off` removes the disguise so the player rejoins as their real account

An example:
An admin runs `/sprofile add ProgMEM 00000000-0000-0000-0000-000000000000` to store a profile.
A staff member then runs `/sprofile login ProgMEM`, gets disconnected, and reconnects as `ProgMEM`.

## Configuration

The plugin is configured using a JSON file as opposed to a YAML file to reduce plugin jar size. Unfortunately, this
means that the plugin configuration file cannot contain any comments.

Please see the reference below to learn about available configuration options:

**permission**: The permission a player needs to use the `/sprofile` command \
**profiles**: A map of profile names to UUIDs. These are the profiles staff members can log in as.

Profiles can also be managed at runtime with the `/sprofile` command, which writes back to this file.
Active disguises are stored in a separate `disguises.json` file so they survive server restarts.

## Commands

`add`, `remove`, `login` and `list` require the configured `permission` (default `staffprofile`).
`off` and `status` are available to everyone, so any player can always check and remove their own disguise.

| Command | Description |
| --- | --- |
| `/sprofile add <username> [uuid]` | Adds a profile. If no UUID is given, the Mojang API is queried for the player's UUID; if the player doesn't exist, a random UUID is generated. |
| `/sprofile remove <username>` | Removes a profile. |
| `/sprofile login <username>` | Starts using the given profile. You are disconnected and reconnect as that profile. |
| `/sprofile off` | Stops using your current disguise. You are disconnected and reconnect as your real profile. Available to everyone. |
| `/sprofile status` | Shows whether you are currently disguised and, if so, as which profile. Available to everyone. |
| `/sprofile list` | Lists all configured profiles. |

Tab completion is provided for the subcommands and their arguments (online players for `add`, configured profiles for `remove` and `login`).

When logging in as a profile, the plugin fetches that profile's real skin from Mojang. Profiles that don't exist
on Mojang (e.g. ones created with a randomly generated UUID) will use the default skin.

## Additional notes

### LuckPerms warnings

When using this plugin, LuckPerms will send print warning messages such as these info the server log:
```
[LuckPerms] LuckPerms already has data for player 'MCMDEV' - but this data is stored under a different UUID.
[LuckPerms] 'MCMDEV' has previously used the unique ids [2fbe3e3e-50c3-482c-872b-28386fd91704] but is now connecting with '00000000-7777-0000-872b-28386fd91704'
[LuckPerms] The UUID the player is connecting with now is NOT Mojang-assigned (type 0). This implies that THIS server is not authenticating correctly, but one (or more) of the other servers/proxies in the network are.
[LuckPerms] If you're using BungeeCord/Velocity, please ensure that IP-Forwarding is setup correctly on all of your backend servers!
[17:09:39 WARN]: [LuckPerms] See here for more info: https://luckperms.net/wiki/Network-Installation#pre-setup
```
These warnings can be safely ignored if you have a secure setup. As the warning states, LuckPerms
noticed that data for the given name is already available under a different username, which is precisely what this plugin does.
Most well-made plugins, including LuckPerms, can handle this properly, as this scenario can actually occur naturally
when two players swap usernames.

LuckPerms [now supports](https://github.com/LuckPerms/LuckPerms/pull/4194) disabling this notice by starting your server using the `luckperms.suppress-uuid-mismatch-warning`
system property.

## Secure profiles

When having secure profiles enabled, sending chat messages when a staff profile is active will not work.
This can't be fixed because the plugin is essentially impersonating another account for which the client does not have
the cryptographic keys.
