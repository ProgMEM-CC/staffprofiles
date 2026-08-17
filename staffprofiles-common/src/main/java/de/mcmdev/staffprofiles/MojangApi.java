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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves usernames to their Mojang-assigned UUIDs.
 */
final class MojangApi {

    private static final String LOOKUP_URL = "https://api.mojang.com/users/profiles/minecraft/%s";
    private static final String PROFILE_URL = "https://sessionserver.mojang.com/session/minecraft/profile/%s";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();

    /**
     * Looks up the UUID of the given username. Returns an empty optional if the
     * player does not exist or the request fails.
     */
    Optional<UUID> lookupUuid(String username) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(LOOKUP_URL.formatted(username)))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != HttpURLConnection.HTTP_OK) {
                return Optional.empty();
            }

            JsonObject json = new JsonParser().parse(response.body()).getAsJsonObject();
            return Optional.of(parseMojangUuid(json.get("id").getAsString()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static UUID parseMojangUuid(String id) {
        String dashed = id.replaceFirst(
                "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})",
                "$1-$2-$3-$4-$5"
        );
        return UUID.fromString(dashed);
    }

    /**
     * Fetches the profile properties (e.g. textures) for the given UUID from Mojang's
     * sessions server. Returns an empty optional if the request fails, and an empty
     * list if the profile doesn't exist or has no properties.
     */
    Optional<List<ProfileProperty>> fetchProperties(UUID uuid) {
        try {
            String id = uuid.toString().replace("-", "");
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(PROFILE_URL.formatted(id)))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == HttpURLConnection.HTTP_NO_CONTENT) {
                // Profile doesn't exist.
                return Optional.of(List.of());
            }
            if (response.statusCode() != HttpURLConnection.HTTP_OK) {
                return Optional.empty();
            }

            JsonArray properties = new JsonParser().parse(response.body()).getAsJsonObject().getAsJsonArray("properties");
            List<ProfileProperty> result = new ArrayList<>();
            for (JsonElement element : properties) {
                JsonObject property = element.getAsJsonObject();
                String name = property.get("name").getAsString();
                String value = property.get("value").getAsString();
                String signature = property.has("signature") ? property.get("signature").getAsString() : null;
                result.add(new ProfileProperty(name, value, signature));
            }
            return Optional.of(result);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

}
