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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class Staffprofiles {

    private static final Logger LOGGER = LoggerFactory.getLogger(Staffprofiles.class);
    private static final String CONFIG_FILE_NAME = "config.json";
    private static final String DISGUISES_FILE_NAME = "disguises.json";

    private final Path dataDirectory;
    private final MojangApi mojangApi = new MojangApi();

    private final String permission;
    private final Map<String, UUID> profiles = new ConcurrentHashMap<>();
    private final Map<UUID, Disguise> disguises = new ConcurrentHashMap<>();

    private Staffprofiles(Path dataDirectory, String permission, Map<String, UUID> profiles,
                          Map<UUID, Disguise> disguises) {
        this.dataDirectory = dataDirectory;
        this.permission = permission;
        this.profiles.putAll(profiles);
        this.disguises.putAll(disguises);
    }

    public static Staffprofiles create(Path dataDirectory) throws Exception {
        ConfigurationData configuration = new ConfigurationLoader().load(dataDirectory);
        Map<String, UUID> profiles = parseProfiles(configuration.profiles());
        Map<UUID, Disguise> disguises = loadDisguises(dataDirectory.resolve(DISGUISES_FILE_NAME));
        return new Staffprofiles(dataDirectory, configuration.permission(), profiles, disguises);
    }

    LoginResponse login(LoginRequest loginRequest) {
        try {
            Disguise disguise = disguises.get(loginRequest.uuid());
            if (disguise == null) {
                return LoginResponse.ignore();
            }

            Optional<ProfileEntry> profile = findProfile(disguise.profileName());
            if (profile.isEmpty()) {
                // The disguise target no longer exists, stop disguising this player.
                clearDisguise(loginRequest.uuid());
                return LoginResponse.ignore();
            }

            LOGGER.info("User {} ({}) is logging in as {}", loginRequest.username(), loginRequest.uuid(), profile.get().name());
            return LoginResponse.allow(profile.get().uuid(), profile.get().name());
        } catch (Exception e) {
            LOGGER.warn("Failed to load login response from staffprofiles.", e);
            return LoginResponse.fail();
        }
    }

    String permission() {
        return permission;
    }

    Optional<UUID> lookupUuid(String username) {
        return mojangApi.lookupUuid(username);
    }

    Optional<List<ProfileProperty>> fetchProperties(UUID uuid) {
        return mojangApi.fetchProperties(uuid);
    }

    synchronized void addProfile(String name, UUID uuid) {
        profiles.put(name, uuid);
        saveProfiles();
    }

    synchronized boolean removeProfile(String name) {
        Optional<String> key = profiles.keySet().stream()
                .filter(existing -> existing.equalsIgnoreCase(name))
                .findFirst();
        if (key.isEmpty()) {
            return false;
        }

        profiles.remove(key.get());
        saveProfiles();
        return true;
    }

    Optional<ProfileEntry> findProfile(String name) {
        return profiles.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(entry -> new ProfileEntry(entry.getKey(), entry.getValue()))
                .findFirst();
    }

    List<ProfileEntry> profiles() {
        return profiles.entrySet().stream()
                .map(entry -> new ProfileEntry(entry.getKey(), entry.getValue()))
                .toList();
    }

    Collection<String> profileNames() {
        return profiles.keySet();
    }

    /**
     * Returns the disguise profile name for a player, resolving it from either their real
     * UUID or their current (possibly disguised) UUID.
     */
    Optional<String> currentDisguiseFor(UUID currentUuid) {
        return findRealUuid(currentUuid).map(realUuid -> disguises.get(realUuid).profileName());
    }

    /**
     * Returns the active disguise for a player, including the player's real UUID and name alongside
     * the disguise profile name.
     */
    Optional<DisguiseInfo> disguiseInfoFor(UUID currentUuid) {
        return findRealUuid(currentUuid)
                .map(realUuid -> {
                    Disguise disguise = disguises.get(realUuid);
                    return new DisguiseInfo(realUuid, disguise.realName(), disguise.profileName());
                });
    }

    synchronized void setDisguiseFor(UUID currentUuid, String realName, String profileName) {
        UUID realUuid = findRealUuid(currentUuid).orElse(currentUuid);
        // If the player is already disguised, their current name is a disguise name; keep the real name we already know.
        String resolvedRealName = disguises.containsKey(realUuid) ? disguises.get(realUuid).realName() : realName;
        disguises.put(realUuid, new Disguise(resolvedRealName, profileName));
        saveDisguises();
    }

    synchronized boolean clearDisguiseFor(UUID currentUuid) {
        return findRealUuid(currentUuid)
                .map(this::clearDisguise)
                .orElse(false);
    }

    private synchronized boolean clearDisguise(UUID realUuid) {
        boolean removed = disguises.remove(realUuid) != null;
        if (removed) {
            saveDisguises();
        }
        return removed;
    }

    /**
     * Finds the real UUID of a player whose current UUID may be a disguise UUID, by
     * scanning the active disguises for the entry whose profile UUID matches.
     */
    private Optional<UUID> findRealUuid(UUID currentUuid) {
        return disguises.entrySet().stream()
                .filter(entry -> findProfile(entry.getValue().profileName())
                        .map(ProfileEntry::uuid)
                        .map(currentUuid::equals)
                        .orElse(false))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    private static Map<String, UUID> parseProfiles(Map<String, String> rawProfiles) {
        Map<String, UUID> result = new LinkedHashMap<>();
        rawProfiles.forEach((name, uuidString) -> {
            try {
                result.put(name, UUID.fromString(uuidString));
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Invalid UUID '{}' for profile '{}', ignoring", uuidString, name);
            }
        });
        return result;
    }

    private static Map<UUID, Disguise> loadDisguises(Path path) {
        if (!Files.exists(path)) {
            return new HashMap<>();
        }

        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonObject json = new JsonParser().parse(reader).getAsJsonObject();
            Map<UUID, Disguise> result = new HashMap<>();
            json.entrySet().forEach(entry -> {
                try {
                    UUID uuid = UUID.fromString(entry.getKey());
                    JsonElement value = entry.getValue();
                    if (value.isJsonObject()) {
                        // Current format: realUuid -> { "realName": ..., "profileName": ... }
                        JsonObject disguiseJson = value.getAsJsonObject();
                        String realName = disguiseJson.has("realName") ? disguiseJson.get("realName").getAsString() : null;
                        String profileName = disguiseJson.get("profileName").getAsString();
                        result.put(uuid, new Disguise(realName, profileName));
                    } else {
                        // Legacy format: realUuid -> profileName
                        result.put(uuid, new Disguise(null, value.getAsString()));
                    }
                } catch (IllegalArgumentException e) {
                    LOGGER.warn("Invalid UUID '{}' in disguises file, ignoring", entry.getKey());
                }
            });
            return result;
        } catch (Exception e) {
            LOGGER.warn("Failed to load disguises from {}", path, e);
            return new HashMap<>();
        }
    }

    private void saveProfiles() {
        JsonObject root = new JsonObject();
        root.addProperty("permission", permission);

        JsonObject profilesJson = new JsonObject();
        profiles.forEach((name, uuid) -> profilesJson.addProperty(name, uuid.toString()));
        root.add("profiles", profilesJson);

        writeJson(dataDirectory.resolve(CONFIG_FILE_NAME), root);
    }

    private void saveDisguises() {
        JsonObject json = new JsonObject();
        disguises.forEach((uuid, disguise) -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("realName", disguise.realName());
            entry.addProperty("profileName", disguise.profileName());
            json.add(uuid.toString(), entry);
        });
        writeJson(dataDirectory.resolve(DISGUISES_FILE_NAME), json);
    }

    private void writeJson(Path path, JsonObject json) {
        try {
            Files.createDirectories(path.getParent());
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            Files.writeString(path, gson.toJson(json), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("Failed to save {}", path, e);
        }
    }

}
