package br.simples.home;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// "Em alta na semana" do TMDB, só o que está num app de streaming instalado.
// Tudo de rede e disco roda numa thread; a tela só recebe a lista pronta (com as imagens já reduzidas).
// ponytail: uma consulta de provedores por título, em série (~100 a cada 6h); paralelizar se ficar lento.
final class Trending {

    static final String KEY = "tmdb";
    static final long FRESH = 6 * 3600_000L, RETRY = 30 * 60_000L;
    static final int PAGES = 5, KEEP = 20;

    // provedor do TMDB (assinatura no BR) -> app na TV; a ordem é a preferência quando há mais de um
    static final Object[][] PROVIDERS = {
            {119, "Prime Video", "com.amazon.amazonvideo.livingroom"},
            {2100, "Prime Video", "com.amazon.amazonvideo.livingroom"},
            {307, "Globoplay", "com.globo.globotv"},
            {1899, "HBO Max", "com.wbd.stream"},
            {350, "Apple TV", "com.apple.atve.androidtv.appletv"},
            {283, "Crunchyroll", "com.crunchyroll.crunchyroid"},
            {8, "Netflix", "com.netflix.ninja"},
            {1796, "Netflix", "com.netflix.ninja"},
            {337, "Disney+", "com.disney.disneyplus"},
    };

    static final class Title {
        String key, name, meta, image;
        int[] providers;
        Bitmap art;
        String app, pkg; // resolvidos na tela, conforme o que está instalado agora
    }

    private static List<Title> latest; // só a thread da tela lê e escreve
    private static volatile long fetchedAt, triedAt;
    private static volatile boolean running;
    private static final Handler ui = new Handler(Looper.getMainLooper());

    /** Lista em memória (pode ser vazia); null enquanto o cache do disco não foi lido. */
    static List<Title> latest() {
        return latest;
    }

    /** Chama done na thread da tela quando a lista mudar. Barato se não houver nada a fazer. */
    static void update(Context c, final int artW, final boolean force, final Runnable done) {
        final Context app = c.getApplicationContext();
        final String key = app.getSharedPreferences("uso", Context.MODE_PRIVATE).getString(KEY, "");
        long now = System.currentTimeMillis();
        boolean stale = now - fetchedAt > FRESH && now - triedAt > RETRY;
        if (running || (latest != null && !force && (key.isEmpty() || !stale))) return;
        running = true;
        new Thread(new Runnable() {
            public void run() {
                try {
                    if (latest == null) publish(readCache(app, artW), done);
                    long t = System.currentTimeMillis();
                    if (!key.isEmpty() && (force || (t - fetchedAt > FRESH && t - triedAt > RETRY))) {
                        triedAt = t;
                        List<Title> fresh = fetch(app, key, artW);
                        if (fresh != null) publish(fresh, done);
                    }
                } finally {
                    running = false;
                }
            }
        }, "tmdb").start();
    }

    private static void publish(final List<Title> list, final Runnable done) {
        ui.post(new Runnable() {
            public void run() {
                latest = list;
                done.run();
            }
        });
    }

    /** Primeiro app instalado (na ordem de preferência) que tem o título; false se nenhum. */
    static boolean resolve(Title t, PackageManager pm) {
        for (Object[] p : PROVIDERS) {
            int id = (Integer) p[0];
            for (int have : t.providers) {
                if (have != id) continue;
                String pkg = (String) p[2];
                if (launch(pm, pkg) == null) continue;
                t.app = (String) p[1];
                t.pkg = pkg;
                return true;
            }
        }
        return false;
    }

    static Intent launch(PackageManager pm, String pkg) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        return i;
    }

    // ---- rede (só na thread) ----

    private static List<Title> fetch(Context c, String key, int artW) {
        try {
            Set<Integer> mapped = new HashSet<>();
            for (Object[] p : PROVIDERS) mapped.add((Integer) p[0]);
            List<Title> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            File dir = new File(c.getFilesDir(), "tmdb");
            dir.mkdirs();
            for (int page = 1; page <= PAGES && out.size() < KEEP; page++) {
                JSONArray results = get(api("trending/all/week", key) + "&language=pt-BR&page=" + page).getJSONArray("results");
                for (int i = 0; i < results.length() && out.size() < KEEP; i++) {
                    JSONObject r = results.getJSONObject(i);
                    String type = r.optString("media_type");
                    String backdrop = r.optString("backdrop_path", "");
                    if (!("movie".equals(type) || "tv".equals(type)) || backdrop.isEmpty() || "null".equals(backdrop)) continue;
                    String id = type + "-" + r.getLong("id");
                    if (!seen.add(id)) continue; // a lista semanal repete título entre páginas

                    JSONObject br = get(api(type + "/" + r.getLong("id") + "/watch/providers", key))
                            .getJSONObject("results").optJSONObject("BR");
                    JSONArray flat = br == null ? null : br.optJSONArray("flatrate");
                    if (flat == null) continue;
                    List<Integer> ids = new ArrayList<>();
                    for (int k = 0; k < flat.length(); k++) {
                        int pid = flat.getJSONObject(k).getInt("provider_id");
                        if (mapped.contains(pid)) ids.add(pid);
                    }
                    if (ids.isEmpty()) continue;

                    Title t = new Title();
                    t.key = "tmdb:" + id;
                    t.name = r.optString("movie".equals(type) ? "title" : "name");
                    t.meta = meta(type, r);
                    t.providers = toArray(ids);
                    t.image = id + ".jpg";
                    File f = new File(dir, t.image);
                    if (!f.exists() && !download("https://image.tmdb.org/t/p/w780" + backdrop, f)) continue;
                    out.add(t);
                }
            }
            writeCache(c, out);
            fetchedAt = System.currentTimeMillis();
            // apaga imagens que saíram da lista
            Set<String> keep = new HashSet<>();
            for (Title t : out) keep.add(t.image);
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) if (!keep.contains(f.getName())) f.delete();
            for (Title t : out) t.art = decode(new File(dir, t.image), artW);
            return out;
        } catch (IOException | JSONException | RuntimeException e) {
            return null; // sem rede, chave inválida, TMDB fora: fica o que já estava
        }
    }

    private static String api(String path, String key) {
        return "https://api.themoviedb.org/3/" + path + "?api_key=" + key;
    }

    private static String meta(String type, JSONObject r) {
        StringBuilder s = new StringBuilder("movie".equals(type) ? "Filme" : "Série");
        String date = r.optString("movie".equals(type) ? "release_date" : "first_air_date", "");
        if (date.length() >= 4) s.append("  ·  ").append(date, 0, 4);
        double v = r.optDouble("vote_average", 0);
        if (v > 0) s.append("  ·  ★ ").append(String.format(new Locale("pt", "BR"), "%.1f", v));
        return s.toString();
    }

    private static JSONObject get(String url) throws IOException, JSONException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            copy(in, b);
            return new JSONObject(new String(b.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            c.disconnect();
        }
    }

    private static boolean download(String url, File dest) {
        File tmp = new File(dest.getPath() + ".part");
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                copy(in, out);
            } finally {
                c.disconnect();
            }
            return tmp.renameTo(dest); // nunca deixa imagem pela metade com o nome final
        } catch (IOException e) {
            tmp.delete();
            return false;
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
    }

    private static int[] toArray(List<Integer> l) {
        int[] a = new int[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }

    // ---- disco ----

    private static void writeCache(Context c, List<Title> list) throws JSONException, IOException {
        JSONArray arr = new JSONArray();
        for (Title t : list) {
            JSONArray p = new JSONArray();
            for (int id : t.providers) p.put(id);
            arr.put(new JSONObject().put("key", t.key).put("name", t.name).put("meta", t.meta)
                    .put("image", t.image).put("providers", p));
        }
        String json = new JSONObject().put("ts", System.currentTimeMillis()).put("titles", arr).toString();
        File f = new File(c.getFilesDir(), "trending.json"), tmp = new File(c.getFilesDir(), "trending.json.part");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) throw new IOException("cache");
    }

    private static List<Title> readCache(Context c, int artW) {
        List<Title> out = new ArrayList<>();
        File f = new File(c.getFilesDir(), "trending.json");
        if (!f.exists()) return out;
        try (InputStream in = new java.io.FileInputStream(f)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            copy(in, b);
            JSONObject root = new JSONObject(new String(b.toByteArray(), StandardCharsets.UTF_8));
            fetchedAt = root.getLong("ts");
            JSONArray arr = root.getJSONArray("titles");
            File dir = new File(c.getFilesDir(), "tmdb");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Title t = new Title();
                t.key = o.getString("key");
                t.name = o.getString("name");
                t.meta = o.getString("meta");
                t.image = o.getString("image");
                JSONArray p = o.getJSONArray("providers");
                t.providers = new int[p.length()];
                for (int k = 0; k < p.length(); k++) t.providers[k] = p.getInt(k);
                t.art = decode(new File(dir, t.image), artW);
                if (t.art != null) out.add(t);
            }
        } catch (IOException | JSONException e) {
            return Collections.emptyList(); // cache corrompido: a próxima busca reescreve
        }
        return out;
    }

    // a w780 do TMDB reduzida pra largura da vitrine, em RGB_565: ~200 KB por título em vez de ~1,4 MB
    private static Bitmap decode(File f, int width) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0) return null;
        o.inJustDecodeBounds = false;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        if (o.outWidth > width) {
            o.inScaled = true;
            o.inDensity = o.outWidth;
            o.inTargetDensity = width;
        }
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

}
