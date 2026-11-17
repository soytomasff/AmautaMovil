package com.example.amautamovil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Keeps the session and CSRF token in process memory only. Never persists credentials. */
final class AmautaApi {
    static final class ApiException extends Exception {
        final int status;
        ApiException(int status, String message) { super(message); this.status = status; }
    }

    private static final AmautaApi INSTANCE = new AmautaApi();
    static AmautaApi get() { return INSTANCE; }

    private final Map<String, HttpCookie> cookies = new LinkedHashMap<>();
    private String csrfToken;

    private AmautaApi() { }

    synchronized void clear() {
        cookies.clear();
        csrfToken = null;
    }

    synchronized boolean hasSession() { return cookies.containsKey("JSESSIONID"); }

    synchronized void fetchCsrf() throws IOException, JSONException, ApiException {
        csrfToken = request("GET", "api/usuarios/csrf", null, false).getString("token");
    }

    synchronized JSONObject login(String username, String password) throws IOException, JSONException, ApiException {
        clear();
        fetchCsrf();
        JSONObject body = new JSONObject();
        body.put("username", username);
        body.put("password", password);
        try {
            JSONObject user = request("POST", "api/usuarios/login", body, true);
            csrfToken = null;
            return user;
        } catch (IOException | JSONException | ApiException error) {
            clear();
            throw error;
        }
    }

    synchronized JSONObject me() throws IOException, JSONException, ApiException {
        return request("GET", "api/usuarios/me", null, false);
    }

    synchronized JSONArray rooms() throws IOException, JSONException, ApiException {
        return new JSONArray(requestText("GET", "api/habitaciones", null, false));
    }

    synchronized JSONArray stays() throws IOException, JSONException, ApiException {
        return new JSONArray(requestText("GET", "api/registros", null, false));
    }

    synchronized JSONObject checkIn(JSONObject data) throws IOException, JSONException, ApiException {
        fetchCsrf();
        JSONObject result = request("POST", "api/recepcion/check-in", data, true);
        csrfToken = null;
        return result;
    }

    synchronized JSONObject checkoutConfiguration() throws IOException, JSONException, ApiException {
        return request("GET", "api/check-out/configuracion", null, false);
    }

    synchronized JSONObject programDeparture(long stayId, String planned)
            throws IOException, JSONException, ApiException {
        fetchCsrf();
        JSONObject body = new JSONObject();
        body.put("salidaPrevista", planned);
        JSONObject result = request("PUT", "api/check-out/estadias/" + stayId
                + "/salida-prevista", body, true);
        csrfToken = null;
        return result;
    }

    synchronized JSONArray stayMovements(long stayId) throws IOException, JSONException, ApiException {
        return new JSONArray(requestText("GET", "api/check-out/estadias/" + stayId
                + "/movimientos", null, false));
    }

    synchronized JSONArray guests() throws IOException, JSONException, ApiException {
        return new JSONArray(requestText("GET", "api/huespedes", null, false));
    }

    synchronized JSONArray reservations() throws IOException, JSONException, ApiException {
        return new JSONArray(requestText("GET", "reservas", null, false));
    }

    synchronized JSONObject reservation(long id) throws IOException, JSONException, ApiException {
        String result = requestText("GET", "reservas/" + id, null, false);
        if (result.isEmpty() || result.equals("null")) throw new ApiException(404, "");
        return new JSONObject(result);
    }

    synchronized JSONObject saveReservation(long id, JSONObject data)
            throws IOException, JSONException, ApiException {
        requireReservationValidation();
        fetchCsrf();
        JSONObject result = request(id > 0 ? "PUT" : "POST",
                id > 0 ? "reservas/" + id : "reservas", data, true);
        csrfToken = null;
        return result;
    }

    synchronized JSONObject cancelReservation(long id, JSONObject reservation)
            throws IOException, JSONException, ApiException {
        requireReservationValidation();
        fetchCsrf();
        JSONObject body = new JSONObject(reservation.toString());
        body.put("estado", "CANCELADO");
        JSONObject result = request("PUT", "reservas/" + id, body, true);
        csrfToken = null;
        return result;
    }

    private void requireReservationValidation() throws IOException, ApiException {
        // The previous controller returned 200 with JSON null for an unknown ID.
        // The validated controller returns 404. Block writes until that code is running.
        try {
            requestText("GET", "reservas/0", null, false);
        } catch (ApiException response) {
            if (response.status == 404) return;
            throw response;
        }
        throw new ApiException(426, "Backend de reservas pendiente de reinicio");
    }

    synchronized JSONObject guestByDocument(String document) throws IOException, JSONException, ApiException {
        return request("GET", "api/huespedes/dni/" + document, null, false);
    }

    synchronized JSONObject saveGuest(long id, JSONObject data) throws IOException, JSONException, ApiException {
        fetchCsrf();
        JSONObject result = request(id > 0 ? "PUT" : "POST",
                id > 0 ? "api/huespedes/" + id : "api/huespedes", data, true);
        csrfToken = null;
        return result;
    }

    synchronized void deleteGuest(long id) throws IOException, JSONException, ApiException {
        fetchCsrf();
        request("DELETE", "api/huespedes/" + id, null, true);
        csrfToken = null;
    }

    synchronized JSONArray roomMovements(long roomId) throws IOException, JSONException, ApiException {
        return new JSONArray(requestText("GET", "api/check-out/habitaciones/" + roomId + "/movimientos", null, false));
    }

    synchronized void confirmDeparture(long stayId) throws IOException, JSONException, ApiException {
        fetchCsrf();
        JSONObject body = new JSONObject();
        body.put("salidaReal", JSONObject.NULL); // Backend chooses current Peru time.
        request("POST", "api/check-out/estadias/" + stayId + "/confirmar", body, true);
        csrfToken = null;
    }

    synchronized void completeCleaning(long roomId) throws IOException, JSONException, ApiException {
        fetchCsrf();
        request("POST", "api/check-out/habitaciones/" + roomId + "/limpieza", new JSONObject(), true);
        csrfToken = null;
    }

    synchronized void finishMaintenance(JSONObject room) throws IOException, JSONException, ApiException {
        fetchCsrf();
        JSONObject body = new JSONObject();
        for (String key : new String[] {"codigo", "numero", "tipo", "capacidad", "precio"}) {
            body.put(key, room.opt(key));
        }
        body.put("estado", "LIBRE");
        request("PUT", "api/habitaciones/" + room.getLong("id"), body, true);
        csrfToken = null;
    }

    synchronized void logout() throws IOException, JSONException, ApiException {
        try {
            fetchCsrf();
            request("POST", "api/usuarios/logout", new JSONObject(), true);
        } finally {
            clear();
        }
    }

    private JSONObject request(String method, String path, JSONObject body, boolean withCsrf)
            throws IOException, JSONException, ApiException {
        String result = requestText(method, path, body, withCsrf);
        return result.isEmpty() ? new JSONObject() : new JSONObject(result);
    }

    private String requestText(String method, String path, JSONObject body, boolean withCsrf)
            throws IOException, ApiException {
        HttpURLConnection connection = (HttpURLConnection) new URL(BuildConfig.API_BASE_URL + path).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        if (!cookies.isEmpty()) {
            StringBuilder header = new StringBuilder();
            for (HttpCookie cookie : cookies.values()) {
                if (header.length() > 0) header.append("; ");
                header.append(cookie.getName()).append('=').append(cookie.getValue());
            }
            connection.setRequestProperty("Cookie", header.toString());
        }
        if (withCsrf && csrfToken != null) connection.setRequestProperty("X-XSRF-TOKEN", csrfToken);
        try {
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (java.io.OutputStream output = connection.getOutputStream()) {
                    output.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = connection.getResponseCode();
            List<String> setCookies = connection.getHeaderFields().get("Set-Cookie");
            if (setCookies == null) {
                for (Map.Entry<String, List<String>> entry : connection.getHeaderFields().entrySet()) {
                    if ("Set-Cookie".equalsIgnoreCase(entry.getKey())) setCookies = entry.getValue();
                }
            }
            if (setCookies != null) for (String line : setCookies) {
                for (HttpCookie cookie : HttpCookie.parse(line)) {
                    if (cookie.getMaxAge() == 0 || cookie.getValue().isEmpty()) cookies.remove(cookie.getName());
                    else cookies.put(cookie.getName(), cookie);
                }
            }
            if (status < 200 || status >= 300) {
                String message = "";
                try (InputStream error = connection.getErrorStream()) {
                    if (error != null) message = new JSONObject(new String(readAll(error), StandardCharsets.UTF_8))
                            .optString("message", "");
                } catch (JSONException ignored) { /* Some error responses have no JSON body. */ }
                throw new ApiException(status, message);
            }
            try (InputStream input = connection.getInputStream()) {
                return input == null ? "" : new String(readAll(input), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toByteArray();
    }
}
