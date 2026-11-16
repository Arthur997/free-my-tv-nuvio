package io.freemytv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Buttons for your own Home Assistant in the bottom drawer (Settings > Home content > Home Assistant, off by default).
 * The server's address, a long-lived access token (typed on the TV, never shown again) and the devices, picked from the
 * server's own list, are saved in the app's private settings. While the drawer is open the states are read (when it
 * opens, then every 30 s) and OK calls the device's service. The only host is the server you name, and no redirect is
 * followed (it would carry the token elsewhere). Locks, alarms, garage doors and gates are never offered: anyone in the
 * room can press a TV remote.
 */
final class HomeAssistant {
    private HomeAssistant() {}

    /** What a button can be, and what OK does with it (see service()). */
    static final List<String> DOMAINS = Collections.unmodifiableList(Arrays.asList(
            "light", "switch", "fan", "input_boolean", "cover", "scene", "script", "automation", "button", "input_button"));
    /** At most this many buttons: they share the drawer's row with the inputs and shortcuts. */
    static final int MAX = 6;
    /** While the drawer is open, the states are read again this often. */
    static final long REFRESH_MS = 30_000;
    /** The only covers offered, by device class: what shades a window (a garage door or a gate opens the house). */
    static final List<String> SAFE_COVERS = Collections.unmodifiableList(
            Arrays.asList("awning", "blind", "curtain", "shade", "shutter"));

    /** One chosen device. Its name and state change as the server answers. */
    static final class Control {
        final String entity, domain;
        volatile String name;
        volatile String state = "";   // as Home Assistant says it ("on", "off", "open", "unavailable"…); "" = not read

        Control(String entity, String name) {
            this.entity = entity;
            this.domain = entity.substring(0, entity.indexOf('.'));
            this.name = name.isEmpty() ? fallbackName(entity) : name;
        }

        /** Lit button: a light, switch or fan that is on, a cover that is open. Scenes, scripts and buttons stay lit. */
        boolean on() {
            if (state.isEmpty() || state.equals("unavailable") || state.equals("unknown")) return false;
            switch (domain) {
                case "light": case "switch": case "fan": case "input_boolean": case "automation":
                    return state.equals("on");
                case "cover":
                    return state.equals("open") || state.equals("opening");
                default:
                    return true;
            }
        }

        boolean unavailable() {
            return state.equals("unavailable");
        }
    }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "home-assistant");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile List<Control> controls = Collections.emptyList();
    private static volatile String controlsFor;
    private static volatile boolean fetching;
    private static volatile long readAt;

    /** On, with an address, a token and at least one device: only then does the drawer show it. */
    static boolean on() {
        return Prefs.homeAssistant() && !server().isEmpty() && !Prefs.haToken().isEmpty() && !Prefs.haEntities().isEmpty();
    }

    /** The chosen devices, in order (their names as last read). */
    static List<Control> controls() {
        String setting = Prefs.haEntities();
        if (!setting.equals(controlsFor)) {
            Map<String, String> names = Prefs.haNames();
            List<Control> out = new ArrayList<>();
            for (String e : setting.split(",")) {
                String entity = e.trim();
                int dot = entity.indexOf('.');
                if (dot <= 0 || !DOMAINS.contains(entity.substring(0, dot)) || out.size() == MAX) continue;
                String name = names.get(entity);
                out.add(new Control(entity, name == null ? "" : name));
            }
            controls = Collections.unmodifiableList(out);
            controlsFor = setting;
        }
        return controls;
    }

    static Control control(String entity) {
        for (Control c : controls()) if (c.entity.equals(entity)) return c;
        return null;
    }

    /** The drawer's buttons, one per chosen device; none when it's off. */
    static List<HomeApps.Item> items() {
        List<HomeApps.Item> out = new ArrayList<>();
        if (!on()) return out;
        for (Control ctl : controls()) out.add(HomeApps.homeAssistant(ctl, symbol(ctl.domain)));
        return out;
    }

    /** The symbol for a button (app/assets/symbols). */
    static String symbol(String domain) {
        switch (domain) {
            case "light": return "lightbulb";
            case "fan": return "mode_fan";
            case "cover": return "blinds";
            case "scene": return "sparkles";
            case "script": case "automation": return "play";
            default: return "toggle_on";
        }
    }

    // MARK: - Reading the states

    /** Reads the chosen devices' states and names, off the main thread; `done` runs on the main thread when they're in. */
    static void refresh(Runnable done) {
        if (!on() || fetching) return;
        fetching = true;
        final List<Control> list = controls();
        IO.execute(() -> {
            try {
                Map<String, JSONObject> states = states();
                for (Control ctl : list) {
                    JSONObject o = states.get(ctl.entity);
                    ctl.state = o == null ? "unavailable" : o.optString("state", "");
                    String name = o == null ? "" : friendlyName(o);
                    if (!name.isEmpty()) ctl.name = name;
                }
                readAt = System.currentTimeMillis();
            } catch (IOException e) {
                AppLog.w("home assistant: " + e.getMessage());
                for (Control ctl : list) ctl.state = "";   // dimmed, as not read, until the server answers again
            } finally {
                fetching = false;
            }
            MAIN.post(done);
        });
    }

    /** When the states were last read (0: never, or the server didn't answer). */
    static long readAt() {
        return readAt;
    }

    /** Every entity the server has, by id. */
    private static Map<String, JSONObject> states() throws IOException {
        try {
            JSONArray all = new JSONArray(request("GET", "/api/states", null));
            Map<String, JSONObject> out = new HashMap<>();
            for (int i = 0; i < all.length(); i++) {
                JSONObject o = all.optJSONObject(i);
                if (o != null) out.put(o.optString("entity_id"), o);
            }
            return out;
        } catch (JSONException e) {
            throw new IOException("unexpected answer from the server");
        }
    }

    /** What the settings can offer: {entity, name}, by name, only of DOMAINS and SAFE_COVERS. Off the main thread. */
    static List<String[]> available() throws IOException {
        List<String[]> out = new ArrayList<>();
        for (JSONObject o : states().values()) {
            String entity = o.optString("entity_id"), domain = entity.contains(".") ? entity.substring(0, entity.indexOf('.')) : "";
            if (!DOMAINS.contains(domain)) continue;
            JSONObject attributes = o.optJSONObject("attributes");
            if (domain.equals("cover")) {
                String dc = attributes == null ? "" : attributes.optString("device_class", "").toLowerCase(Locale.ROOT);
                if (!SAFE_COVERS.contains(dc)) continue;   // garage doors, gates, doors, and covers without a class
            }
            String name = friendlyName(o);
            out.add(new String[] {entity, name.isEmpty() ? fallbackName(entity) : name});
        }
        Collections.sort(out, (a, b) -> a[1].toLowerCase(Locale.ROOT).compareTo(b[1].toLowerCase(Locale.ROOT)));
        return out;
    }

    private static String friendlyName(JSONObject o) {
        JSONObject attributes = o.optJSONObject("attributes");
        return attributes == null ? "" : attributes.optString("friendly_name", "").trim();
    }

    // MARK: - OK on a button

    interface Result {
        void done(boolean ok);
    }

    /**
     * Calls the button's service: a light, switch, fan or cover toggles, a scene or script turns on, an automation runs,
     * a button is pressed. The button shows its new state at once, is read again a moment later, and goes back if the
     * call failed. `result` runs on the main thread (twice when it worked: now, and once read again).
     */
    static void activate(Control ctl, Result result) {
        if (!on() || ctl.unavailable()) {
            result.done(false);
            return;
        }
        final String before = ctl.state;
        if (toggles(ctl.domain)) ctl.state = ctl.domain.equals("cover") ? (ctl.on() ? "closed" : "open") : (ctl.on() ? "off" : "on");
        IO.execute(() -> {
            boolean ok;
            try {
                request("POST", "/api/services/" + service(ctl.domain), "{\"entity_id\":" + JSONObject.quote(ctl.entity) + "}");
                ok = true;
            } catch (IOException e) {
                AppLog.w("home assistant: " + ctl.entity + ": " + e.getMessage());
                ctl.state = before;
                ok = false;
            }
            final boolean worked = ok;
            MAIN.post(() -> result.done(worked));
            if (!ok) return;
            try {   // Home Assistant answers before the device has changed: read it once it has
                Thread.sleep(1200);
                JSONObject o = new JSONObject(request("GET", "/api/states/" + ctl.entity, null));
                ctl.state = o.optString("state", ctl.state);
            } catch (InterruptedException | IOException | JSONException ignored) {
            }
            MAIN.post(() -> result.done(true));
        });
    }

    private static boolean toggles(String domain) {
        return domain.equals("light") || domain.equals("switch") || domain.equals("fan")
                || domain.equals("input_boolean") || domain.equals("cover");
    }

    /** Domain/service for OK, as /api/services takes it. */
    static String service(String domain) {
        switch (domain) {
            case "light": case "switch": case "fan": case "input_boolean": case "cover":
                return "homeassistant/toggle";
            case "scene": return "scene/turn_on";
            case "script": return "script/turn_on";
            case "automation": return "automation/trigger";
            default: return domain + "/press";   // button, input_button
        }
    }

    // MARK: - The server

    /** "http://192.168.0.10:8123" as typed: a scheme added when it lacks one, no trailing slash; "" when it isn't a URL. */
    static String normalize(String typed) {
        String url = typed == null ? "" : typed.trim();
        if (url.isEmpty()) return "";
        if (!url.contains("://")) url = "http://" + url;
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        try {
            URL u = new URL(url);
            boolean web = u.getProtocol().equals("http") || u.getProtocol().equals("https");
            return web && !u.getHost().isEmpty() ? url : "";
        } catch (java.net.MalformedURLException e) {
            return "";
        }
    }

    private static String server() {
        return normalize(Prefs.haUrl());
    }

    /** "light.living_room" → "Living room", until the server gives its name. */
    private static String fallbackName(String entity) {
        String id = entity.substring(entity.indexOf('.') + 1).replace('_', ' ').trim();
        return id.isEmpty() ? entity : id.substring(0, 1).toUpperCase(Locale.ROOT) + id.substring(1);
    }

    /**
     * A plain-http server only on the home network (a private IP, a .local or dotless name): the token never leaves the
     * house unencrypted. Anything else needs https.
     */
    static boolean local(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa") || !h.contains(".")) return true;
        String[] p = h.split("\\.");
        if (p.length != 4) return false;
        try {
            int a = Integer.parseInt(p[0]), b = Integer.parseInt(p[1]);
            return a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168) || (a == 169 && b == 254);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String request(String method, String path, String body) throws IOException {
        String server = server(), token = Prefs.haToken();
        if (server.isEmpty() || token.isEmpty()) throw new IOException("no server or token");
        URL url = new URL(server + path);
        if (url.getProtocol().equals("http")) {
            if (!local(url.getHost())) throw new IOException("plain http only on the home network: use https");
            return LanHttp.request(url, method, token, body);
        }
        HttpURLConnection c = (HttpURLConnection) new URL(server + path).openConnection();
        c.setInstanceFollowRedirects(false);   // a redirect would carry the token to wherever it points
        c.setConnectTimeout(3000);
        c.setReadTimeout(8000);
        c.setRequestMethod(method);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("User-Agent", "FreeMyTV");
        try {
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            if (code == 401) throw new IOException("HTTP 401");
            if (code >= 300 && code < 400) throw new IOException("HTTP " + code + ": redirects aren't followed");
            if (code != 200 && code != 201) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }
}
