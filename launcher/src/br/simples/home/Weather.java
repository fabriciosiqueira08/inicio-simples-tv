package br.simples.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

// Tempo agora da cidade configurada, pelo Open-Meteo (grátis, sem chave).
// A cidade vira coordenada uma vez; depois é uma consulta a cada 30 min, numa thread.
final class Weather {

    static final String CITY = "cidade";
    private static final String NAME = "cidade_nome", LAT = "lat", LON = "lon", NOW = "tempo";
    private static final long FRESH = 30 * 60_000L, SHOW = 6 * 3600_000L; // mais velho que 6h engana: some
    private static volatile boolean running;
    private static volatile long triedAt;
    private static final Handler ui = new Handler(Looper.getMainLooper());

    /** {cidade, "14°", "Neblina", "↑21° ↓12°"}, ou null sem cidade ou com dado velho. */
    static String[] current(SharedPreferences p) {
        String[] f = p.getString(NOW, "").split("\t");
        if (p.getString(CITY, "").isEmpty() || f.length != 4) return null;
        if (System.currentTimeMillis() - Long.parseLong(f[0]) > SHOW) return null;
        return new String[]{p.getString(NAME, ""), f[1], f[2], f[3]};
    }

    /** Troca a cidade (vazia desliga) e já busca. */
    static void setCity(Context c, String city, Runnable done) {
        prefs(c).edit().putString(CITY, city).remove(NAME).remove(LAT).remove(LON).remove(NOW).apply();
        triedAt = 0;
        done.run();
        update(c, done);
    }

    static void update(Context c, final Runnable done) {
        final SharedPreferences p = prefs(c);
        final String city = p.getString(CITY, "");
        long now = System.currentTimeMillis();
        if (running || city.isEmpty() || "?".equals(p.getString(LAT, "")) || now - triedAt < FRESH) return;
        String[] f = p.getString(NOW, "").split("\t");
        if (f.length == 4 && now - Long.parseLong(f[0]) < FRESH) return;
        running = true;
        triedAt = now;
        new Thread(new Runnable() {
            public void run() {
                try {
                    if (p.getString(LAT, "").isEmpty() && !geocode(p, city)) return;
                    JSONObject r = get("https://api.open-meteo.com/v1/forecast?latitude=" + p.getString(LAT, "")
                            + "&longitude=" + p.getString(LON, "")
                            + "&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min"
                            + "&timezone=auto&forecast_days=1");
                    JSONObject cur = r.getJSONObject("current"), day = r.getJSONObject("daily");
                    String line = System.currentTimeMillis() + "\t" + deg(cur.getDouble("temperature_2m")) + "\t"
                            + sky(cur.getInt("weather_code")) + "\t"
                            + "↑" + deg(day.getJSONArray("temperature_2m_max").getDouble(0))
                            + " ↓" + deg(day.getJSONArray("temperature_2m_min").getDouble(0));
                    p.edit().putString(NOW, line).apply();
                    ui.post(done);
                } catch (IOException | JSONException | RuntimeException e) {
                    // sem rede ou serviço fora: fica o último, e tenta de novo em 30 min
                } finally {
                    running = false;
                }
            }
        }, "tempo").start();
    }

    // "Campinas" / "Campinas SP" / "São José, Santa Catarina". O geocoding NÃO ordena por população (a 1ª
    // de muitos nomes é uma vila na Espanha): fica a mais populosa, entre as do estado/país depois da vírgula se houver.
    // "?" marca cidade não encontrada pra não insistir. ponytail: sigla de UF é só descartada, não filtra.
    private static boolean geocode(SharedPreferences p, String city) throws IOException, JSONException {
        String[] parts = city.split(",", 2);
        String name = parts[0].trim().replaceAll("\\s+\\p{L}{2}$", "");
        String hint = parts.length > 1 ? parts[1].trim().toLowerCase(Locale.ROOT) : "";
        JSONObject r = get("https://geocoding-api.open-meteo.com/v1/search?count=20&language=pt&format=json&name="
                + URLEncoder.encode(name, "UTF-8"));
        JSONArray all = r.optJSONArray("results");
        JSONObject g = null;
        for (int i = 0; all != null && i < all.length(); i++) {
            JSONObject o = all.getJSONObject(i);
            String where = (o.optString("admin1") + "|" + o.optString("country") + "|" + o.optString("country_code"))
                    .toLowerCase(Locale.ROOT);
            if (!hint.isEmpty() && !where.contains(hint)) continue;
            if (g == null || o.optLong("population") > g.optLong("population")) g = o;
        }
        if (g == null) {
            p.edit().putString(LAT, "?").apply();
            return false;
        }
        p.edit().putString(NAME, g.getString("name"))
                .putString(LAT, String.valueOf(g.getDouble("latitude")))
                .putString(LON, String.valueOf(g.getDouble("longitude"))).apply();
        return true;
    }

    private static String deg(double t) {
        return Math.round(t) + "°";
    }

    // códigos WMO do Open-Meteo, curtos pra caber na linha do relógio
    static String sky(int code) {
        if (code == 0) return "Céu limpo";
        if (code == 1) return "Poucas nuvens";
        if (code == 2) return "Meio nublado";
        if (code == 3) return "Nublado";
        if (code == 45 || code == 48) return "Neblina";
        if (code >= 51 && code <= 57) return "Garoa";
        if (code >= 61 && code <= 67) return "Chuva";
        if (code >= 80 && code <= 82) return "Pancadas";
        if (code >= 95) return "Tempestade";
        if (code >= 71 && code <= 86) return "Neve";
        return "";
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("uso", Context.MODE_PRIVATE);
    }

    private static JSONObject get(String url) throws IOException, JSONException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            return new JSONObject(new String(b.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            c.disconnect();
        }
    }
}
