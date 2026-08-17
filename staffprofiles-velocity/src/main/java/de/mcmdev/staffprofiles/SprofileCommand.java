/*
 * staffprofiles
 * Copyright (C) 2025 MCMDEV
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.mcmdev.staffprofiles;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.jorel.commandapi.CommandAPICommand;
import dev.jorel.commandapi.arguments.ArgumentSuggestions;
import dev.jorel.commandapi.arguments.StringArgument;
import net.kyori.adventure.text.Component;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

final class SprofileCommand {

    private final ProxyServer proxyServer;
    private final Object plugin;
    private final Staffprofiles staffprofiles;

    SprofileCommand(ProxyServer proxyServer, Object plugin, Staffprofiles staffprofiles) {
        this.proxyServer = proxyServer;
        this.plugin = plugin;
        this.staffprofiles = staffprofiles;
    }

    void register() {
        new CommandAPICommand("sprofile")
                .withSubcommand(new CommandAPICommand("add")
                        .withPermission(staffprofiles.permission())
                        .withArguments(new StringArgument("username")
                                .replaceSuggestions(ArgumentSuggestions.stringCollection(info -> onlinePlayerNames())))
                        .withOptionalArguments(new StringArgument("uuid"))
                        .executes((sender, args) -> {
                            String username = (String) args.get("username");
                            Optional<String> uuidString = args.getOptional("uuid").map(String.class::cast);
                            if (uuidString.isPresent()) {
                                addProfileWithUuid(sender, username, uuidString.get());
                            } else {
                                addProfileAsync(sender, username);
                            }
                        }))
                .withSubcommand(new CommandAPICommand("remove")
                        .withPermission(staffprofiles.permission())
                        .withArguments(new StringArgument("username")
                                .replaceSuggestions(ArgumentSuggestions.stringCollection(info -> staffprofiles.profileNames())))
                        .executes((sender, args) -> {
                            String username = (String) args.get("username");
                            boolean removed = staffprofiles.removeProfile(username);
                            sender.sendMessage(Component.text(removed
                                    ? "Removed profile " + username
                                    : "No profile found for " + username));
                        }))
                .withSubcommand(new CommandAPICommand("login")
                        .withPermission(staffprofiles.permission())
                        .withArguments(new StringArgument("username")
                                .replaceSuggestions(ArgumentSuggestions.stringCollection(info -> staffprofiles.profileNames())))
                        .executesPlayer((player, args) -> {
                            String username = (String) args.get("username");
                            Optional<ProfileEntry> profile = staffprofiles.findProfile(username);
                            if (profile.isEmpty()) {
                                player.sendMessage(Component.text("No profile found for " + username));
                                return;
                            }

                            staffprofiles.setDisguiseFor(player.getUniqueId(), profile.get().name());
                            player.disconnect(Component.text("Reconnecting as " + profile.get().name() + "..."));
                        }))
                .withSubcommand(new CommandAPICommand("off")
                        .executesPlayer((player, args) -> {
                            boolean removed = staffprofiles.clearDisguiseFor(player.getUniqueId());
                            if (!removed) {
                                player.sendMessage(Component.text("You are not currently disguised."));
                                return;
                            }

                            player.disconnect(Component.text("Reconnecting with your real profile..."));
                        }))
                .withSubcommand(new CommandAPICommand("status")
                        .executesPlayer((player, args) -> {
                            Optional<String> disguise = staffprofiles.currentDisguiseFor(player.getUniqueId());
                            if (disguise.isEmpty()) {
                                player.sendMessage(Component.text("You are not currently disguised."));
                                return;
                            }

                            String profileName = disguise.get();
                            Optional<ProfileEntry> profile = staffprofiles.findProfile(profileName);
                            if (profile.isPresent()) {
                                ProfileEntry entry = profile.get();
                                player.sendMessage(Component.text("You are disguised as " + entry.name() + " (" + entry.uuid() + ")."));
                            } else {
                                player.sendMessage(Component.text("You are disguised as " + profileName + " (profile no longer exists)."));
                            }
                        }))
                .withSubcommand(new CommandAPICommand("list")
                        .withPermission(staffprofiles.permission())
                        .executes((sender, args) -> {
                            if (staffprofiles.profiles().isEmpty()) {
                                sender.sendMessage(Component.text("No profiles configured."));
                                return;
                            }

                            sender.sendMessage(Component.text("Configured profiles:"));
                            staffprofiles.profiles().forEach(profile ->
                                    sender.sendMessage(Component.text(profile.name() + " -> " + profile.uuid())));
                        }))
                .register();
    }

    private void addProfileWithUuid(CommandSource sender, String username, String uuidString) {
        UUID uuid;
        try {
            uuid = UUID.fromString(uuidString);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Component.text("Invalid UUID: " + uuidString));
            return;
        }

        staffprofiles.addProfile(username, uuid);
        sender.sendMessage(Component.text("Added profile " + username + " -> " + uuid));
    }

    private void addProfileAsync(CommandSource sender, String username) {
        proxyServer.getScheduler().buildTask(plugin, () -> {
            UUID uuid = staffprofiles.lookupUuid(username).orElseGet(UUID::randomUUID);
            staffprofiles.addProfile(username, uuid);
            sender.sendMessage(Component.text("Added profile " + username + " -> " + uuid));
        }).schedule();
    }

    private Collection<String> onlinePlayerNames() {
        return proxyServer.getAllPlayers().stream().map(Player::getUsername).toList();
    }

}
