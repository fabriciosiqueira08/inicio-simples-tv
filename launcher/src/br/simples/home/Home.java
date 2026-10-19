package br.simples.home;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.FocusFinder;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// Tela inicial "Ember Showroom": vitrine (hero) do app em foco + fileiras de cartões 16:9.
// ponytail: recarrega a lista a cada onResume em vez de ouvir PACKAGE_ADDED; ~50 apps, é instantâneo.
public class Home extends Activity {

    static final int NIGHT = 0xFF151619, CARD = 0xFF1E2024, HAIRLINE = 0xFF34353D;
    static final int EMBER = 0xFFED3207, EMBER_DEEP = 0xFFBF2604;
    static final int TEXT = 0xFFF4F4F5, TEXT2 = 0xFFB4B5BC, TEXT3 = 0xFF8E8F98; // contraste sobre NIGHT: 16.5, 8.8, 5.6
    static final int COLS = 5;
    static final String NOMES = "nomes";
    static final int TV = 0, PHONE = 1, SYSTEM = 2;

    static class App {
        final String label, key;
        final Drawable art;
        final boolean banner;
        final int kind;
        final Intent intent;

        App(String label, String key, Drawable art, boolean banner, int kind, Intent intent) {
            this.label = label;
            this.key = key;
            this.art = art;
            this.banner = banner;
            this.kind = kind;
            this.intent = intent;
        }
    }

    private static Typeface light, regular, medium, bold;

    private final List<App> apps = new ArrayList<>();
    private final Map<String, Integer> tints = new HashMap<>();
    private SharedPreferences uso;

    private int pad, gapIn, innerW, innerH, snapEdge;
    private ScrollView scroll;
    private LinearLayout content, heroText;
    private TextView heroName, heroMeta;
    private TextView greetingView;
    private ImageView artBack, artFront;
    private String greeting, focusKey, focusSection, heroKey;
    private Card pendingSnap;

    private GradientDrawable wash;
    private final int[] washColors = new int[2];
    private int washFrom, washTo, washColor;
    private ValueAnimator washAnim;

    private final Bitmap sample = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888);
    private final Canvas sampleCanvas = new Canvas(sample);
    private final int[] samplePx = new int[32 * 18];
    private final float[] hsv = new float[3];

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(Typeface tf, float sp, int color) {
        TextView t = new TextView(this);
        t.setTypeface(tf);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private static GradientDrawable round(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        uso = getSharedPreferences("uso", MODE_PRIVATE);
        saveNames(getIntent());
        if (bold == null) {
            AssetManager am = getAssets();
            light = Typeface.createFromAsset(am, "fonts/Ubuntu-Light.ttf");
            regular = Typeface.createFromAsset(am, "fonts/Ubuntu-Regular.ttf");
            medium = Typeface.createFromAsset(am, "fonts/Ubuntu-Medium.ttf");
            bold = Typeface.createFromAsset(am, "fonts/Ubuntu-Bold.ttf");
        }

        // grade de 5 colunas: margem de 60dp deixa até o anel do cartão ampliado fora do overscan (48dp)
        int margin = dp(60);
        pad = dp(6);
        gapIn = dp(24);
        innerW = (getResources().getDisplayMetrics().widthPixels - 2 * margin - (COLS - 1) * gapIn) / COLS;
        innerH = innerW * 9 / 16;
        int artW = 2 * innerW + gapIn, artH = artW * 9 / 16;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        // luz de projetor: a cor do app em foco vaza por trás da vitrine
        wash = new GradientDrawable();
        wash.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        wash.setGradientCenter(0.8f, 0.16f);
        wash.setGradientRadius(dp(540));
        wash.setColors(washColors);
        root.setBackground(new LayerDrawable(new Drawable[]{new ColorDrawable(NIGHT), wash}));

        LinearLayout hero = new LinearLayout(this);
        hero.setPadding(margin, dp(32), margin, 0);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);

        // mostrador: hora firme, dois-pontos ember, minuto leve | fio | dia da semana sobre a data
        LinearLayout clock = new LinearLayout(this);
        clock.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout time = new LinearLayout(this); // gravidade topo: só assim o LinearLayout alinha pela baseline
        time.addView(clockPart("HH", medium, 36, TEXT));
        TextView colon = text(medium, 36, EMBER);
        colon.setText(":");
        colon.setTranslationY(-dp(3)); // na Ubuntu os dois-pontos sentam baixo em relação aos dígitos
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.leftMargin = clp.rightMargin = dp(2);
        time.addView(colon, clp);
        TextClock mm = clockPart("mm", light, 36, TEXT);
        // a cada minuto o relógio muda; aproveita pra trocar a saudação sem timer próprio
        mm.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) { updateGreeting(); }
        });
        time.addView(mm);
        clock.addView(time);
        View rule = new View(this);
        rule.setBackgroundColor(HAIRLINE);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(Math.max(1, dp(1)), dp(30));
        rlp.leftMargin = rlp.rightMargin = dp(16);
        clock.addView(rule, rlp);
        LinearLayout when = new LinearLayout(this);
        when.setOrientation(LinearLayout.VERTICAL);
        TextClock weekday = clockPart("EEEE", medium, 12, TEXT2);
        weekday.setAllCaps(true);
        weekday.setLetterSpacing(0.14f);
        when.addView(weekday, new LinearLayout.LayoutParams(-2, -2)); // padrão vertical é MATCH_PARENT: cortava na largura da data
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-2, -2);
        wlp.topMargin = dp(5);
        when.addView(clockPart("d 'de' MMMM", regular, 15, TEXT), wlp);
        clock.addView(when);
        left.addView(clock);

        greetingView = text(medium, 19, TEXT);
        greetingView.setSingleLine(true);
        greetingView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, -2);
        glp.topMargin = dp(12);
        left.addView(greetingView, glp);
        TextView question = text(regular, 16, TEXT2);
        question.setText("O que vocês vão assistir hoje?");
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(-1, -2);
        qlp.topMargin = dp(5);
        left.addView(question, qlp);
        updateGreeting();
        left.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1));

        heroText = new LinearLayout(this);
        heroText.setOrientation(LinearLayout.VERTICAL);
        heroName = text(bold, 34, TEXT);
        heroName.setLetterSpacing(-0.03f);
        heroName.setSingleLine(true); // duas linhas não cabem com relógio e saudação na coluna
        heroName.setEllipsize(TextUtils.TruncateAt.END);
        heroText.addView(heroName);
        heroMeta = text(regular, 14, TEXT2);
        heroMeta.setSingleLine(true);
        heroMeta.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
        mlp.topMargin = dp(6);
        heroText.addView(heroMeta, mlp);
        left.addView(heroText);
        hero.addView(left, new LinearLayout.LayoutParams(3 * innerW + 2 * gapIn, artH));

        // a vitrine ocupa exatamente as duas últimas colunas da grade
        FrameLayout art = new FrameLayout(this);
        art.setBackground(round(CARD, dp(12)));
        art.setClipToOutline(true);
        GradientDrawable edge = new GradientDrawable();
        edge.setCornerRadius(dp(12));
        edge.setStroke(Math.max(1, dp(1)), HAIRLINE);
        art.setForeground(edge);
        artBack = new ImageView(this);
        artFront = new ImageView(this);
        setArt(artBack, null, false, CARD);
        setArt(artFront, null, false, CARD);
        art.addView(artBack, new FrameLayout.LayoutParams(-1, -1));
        art.addView(artFront, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(artW, artH);
        alp.leftMargin = gapIn;
        hero.addView(art, alp);
        root.addView(hero);

        scroll = new ScrollView(this) {
            // quem rola é snap(): a fileira em foco sempre ancora no topo da área
            @Override
            protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) { return 0; }

            // o ScrollView rolaria sem mover o foco e o perderia na borda; aqui só o foco anda
            @Override
            public boolean arrowScroll(int dir) {
                View next = FocusFinder.getInstance().findNextFocus(this, findFocus(), dir);
                if (next != null) next.requestFocus(dir);
                return true;
            }

            // em cima a fileira é sempre inteira (snap); só embaixo esmaece, como "tem mais"
            @Override
            protected float getTopFadingEdgeStrength() { return 0f; }
        };
        scroll.setFocusable(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(dp(48));
        snapEdge = dp(8);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        // folga de uma fileira no fim: sem ela o ScrollView trava antes da âncora e corta a fileira de cima
        content.setPadding(margin - pad, 0, margin - pad, innerH + 2 * pad + gapIn + snapEdge);
        content.setClipToPadding(false);
        content.setClipChildren(false);
        content.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (pendingSnap != null) {
                    snap(pendingSnap, false);
                    pendingSnap = null;
                }
            }
        });
        scroll.addView(content);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 0, 1);
        slp.topMargin = dp(24);
        root.addView(scroll, slp);

        setContentView(root);
    }

    private TextClock clockPart(String format, Typeface tf, float sp, int color) {
        TextClock t = new TextClock(this);
        t.setFormat24Hour(format);
        t.setFormat12Hour(format);
        t.setTypeface(tf);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private void updateGreeting() {
        if (greetingView == null) return; // o TextClock do minuto dispara antes da saudação existir
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String g = h >= 5 && h < 12 ? "Bom dia" : h >= 12 && h < 18 ? "Boa tarde" : "Boa noite";
        String nomes = uso.getString(NOMES, "");
        String t = nomes.isEmpty() ? g : g + ", " + nomes;
        if (t.equals(greeting)) return;
        greeting = t;
        greetingView.setText(t);
    }

    // nomes da saudação ficam no aparelho, não no código:
    // adb shell am start -n br.simples.home/.Home --es nomes "Ana e Beto"   (--es nomes "" apaga)
    private void saveNames(Intent i) {
        String n = i == null ? null : i.getStringExtra(NOMES);
        if (n == null) return;
        n = n.trim();
        if (n.length() > 40) n = n.substring(0, 40); // activity exportada: qualquer app pode mandar o extra
        uso.edit().putString(NOMES, n).apply();
        updateGreeting();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        saveNames(i);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        PackageManager pm = getPackageManager();
        Set<String> seen = new HashSet<>();
        seen.add(getPackageName());
        apps.clear();
        // TV primeiro (banner 16:9), depois apps de celular que só têm LAUNCHER
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                ActivityInfo ai = ri.activityInfo;
                if (!seen.add(ai.packageName)) continue;
                Drawable art = ai.loadBanner(pm);
                if (art == null) art = ai.applicationInfo.loadBanner(pm);
                boolean banner = art != null;
                if (art == null) art = ai.loadIcon(pm);
                Intent i = new Intent(Intent.ACTION_MAIN).addCategory(cat)
                        .setClassName(ai.packageName, ai.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                int kind = Intent.CATEGORY_LEANBACK_LAUNCHER.equals(cat) ? TV : PHONE;
                apps.add(new App(ri.loadLabel(pm).toString(), ai.packageName + "/" + ai.name, art, banner, kind, i));
            }
        }
        // Configurações quase nunca aparece como app na TV; garante um atalho
        Intent settings = new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ResolveInfo s = pm.resolveActivity(settings, 0);
        if (s != null && !seen.contains(s.activityInfo.packageName)) {
            apps.add(new App("Configurações", "settings", s.activityInfo.loadIcon(pm), false, SYSTEM, settings));
        }
        final Collator c = Collator.getInstance(new Locale("pt", "BR"));
        Collections.sort(apps, new Comparator<App>() {
            public int compare(App a, App b) { return c.compare(a.label, b.label); }
        });
        build();
    }

    private void build() {
        content.removeAllViews();
        List<Card> cards = new ArrayList<>();
        // Mais usados: só aparece depois do primeiro uso, nunca como fileira vazia
        final Map<String, Integer> n = new HashMap<>();
        List<App> top = new ArrayList<>();
        for (App a : apps) {
            int k = uso.getInt(a.key, 0);
            n.put(a.key, k);
            if (k > 0) top.add(a);
        }
        Collections.sort(top, new Comparator<App>() {
            public int compare(App a, App b) { return n.get(b.key) - n.get(a.key); }
        });
        if (top.size() > COLS) top = top.subList(0, COLS);
        if (!top.isEmpty()) section("Mais usados", null, top, cards);
        section("Todos os apps", String.valueOf(apps.size()), apps, cards);

        Card target = cards.isEmpty() ? null : cards.get(0);
        for (Card k : cards) {
            if (k.app.key.equals(focusKey) && k.section.equals(focusSection)) target = k;
        }
        if (target == null) {
            showEmpty();
            return;
        }
        pendingSnap = target;
        target.requestFocus();
    }

    private void section(String title, String count, List<App> list, List<Card> out) {
        if (list.isEmpty()) return;
        LinearLayout sec = new LinearLayout(this);
        sec.setOrientation(LinearLayout.VERTICAL);
        sec.setClipChildren(false);
        // rótulo de estrutura, não conteúdo: caixa alta pequena pra não competir com a vitrine
        TextView h = text(medium, 13, TEXT2);
        h.setAllCaps(true);
        h.setLetterSpacing(0.14f);
        if (count == null) {
            h.setText(title);
        } else {
            SpannableString sp = new SpannableString(title + "   " + count);
            sp.setSpan(new ForegroundColorSpan(TEXT3), title.length(), sp.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            h.setText(sp);
        }
        h.setPadding(pad, 0, 0, 0);
        sec.addView(h);
        LinearLayout row = null;
        for (int i = 0; i < list.size(); i++) {
            if (i % COLS == 0) {
                row = new LinearLayout(this) {
                    // fileira incompleta: sem isso a seta pro lado pula na diagonal pra outra fileira
                    @Override
                    public View focusSearch(View focused, int dir) {
                        if (dir != View.FOCUS_LEFT && dir != View.FOCUS_RIGHT) return super.focusSearch(focused, dir);
                        View v = FocusFinder.getInstance().findNextFocus(this, focused, dir);
                        return v != null ? v : focused;
                    }
                };
                row.setClipChildren(false);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                rlp.topMargin = i == 0 ? dp(10) : gapIn - 2 * pad;
                sec.addView(row, rlp);
            }
            Card c = card(list.get(i), title);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(innerW + 2 * pad, innerH + 2 * pad);
            if (i % COLS != 0) clp.leftMargin = gapIn - 2 * pad;
            row.addView(c, clp);
            out.add(c);
        }
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = content.getChildCount() == 0 ? 0 : dp(22);
        content.addView(sec, slp);
    }

    private final View.OnFocusChangeListener onFocus = new View.OnFocusChangeListener() {
        public void onFocusChange(View v, boolean has) {
            if (!has) return;
            Card c = (Card) v;
            focusKey = c.app.key;
            focusSection = c.section;
            showHero(c.app);
            if (pendingSnap == null) snap(c, true);
        }
    };

    private final View.OnClickListener onClick = new View.OnClickListener() {
        public void onClick(View v) {
            App a = ((Card) v).app;
            try {
                startActivity(a.intent);
                uso.edit().putInt(a.key, uso.getInt(a.key, 0) + 1).apply();
            } catch (RuntimeException e) { // ActivityNotFound, Security e afins: launcher nunca cai
                Toast.makeText(Home.this, "Não deu pra abrir " + a.label, Toast.LENGTH_SHORT).show();
            }
        }
    };

    private Card card(App a, String section) {
        Card c = new Card(this, a, section, pad, dp(12));
        FrameLayout face = new FrameLayout(this);
        face.setBackground(round(a.banner ? CARD : deep(a), dp(12)));
        face.setClipToOutline(true);
        if (a.banner) {
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setImageDrawable(copy(a.art));
            face.addView(img, new FrameLayout.LayoutParams(-1, -1));
        } else {
            // app sem banner: o ícone sozinho não diz o nome, então o cartão escreve
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER);
            col.setPadding(dp(10), 0, dp(10), 0);
            ImageView img = new ImageView(this);
            img.setImageDrawable(copy(a.art));
            col.addView(img, new LinearLayout.LayoutParams(dp(36), dp(36)));
            TextView name = text(medium, 14, TEXT);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setText(a.label);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
            nlp.topMargin = dp(8);
            col.addView(name, nlp);
            face.addView(col, new FrameLayout.LayoutParams(-1, -1));
        }
        c.addView(face, new FrameLayout.LayoutParams(-1, -1));
        c.setOnFocusChangeListener(onFocus);
        c.setOnClickListener(onClick);
        return c;
    }

    // o mesmo Drawable em duas Views briga por bounds; cada uso ganha sua cópia
    private static Drawable copy(Drawable d) {
        Drawable.ConstantState cs = d.getConstantState();
        return cs != null ? cs.newDrawable() : d;
    }

    // a rolagem só para em âncoras (topo de seção com título, ou topo de fileira), então a
    // primeira fileira visível é sempre inteira; escolhe a menor âncora que ainda mostra o foco
    private void snap(Card c, boolean smooth) {
        View row = (View) c.getParent();
        ViewGroup focusSec = (ViewGroup) row.getParent();
        int bottom = focusSec.getTop() + row.getBottom() + snapEdge;
        int vh = scroll.getHeight();
        int y = 0;
        search:
        for (int s = 0; s < content.getChildCount(); s++) {
            ViewGroup sec = (ViewGroup) content.getChildAt(s);
            for (int r = 1; r < sec.getChildCount(); r++) { // filho 0 é o título
                int a = Math.max(0, sec.getTop() + (r == 1 ? 0 : sec.getChildAt(r).getTop()) - snapEdge);
                y = a;
                if (a + vh >= bottom) break search;
            }
        }
        if (smooth) scroll.smoothScrollTo(0, y);
        else scroll.scrollTo(0, y);
    }

    private void showHero(App a) {
        int k = uso.getInt(a.key, 0);
        String kind = a.kind == TV ? "App de TV" : a.kind == PHONE ? "App de celular" : "Sistema";
        heroMeta.setText(k == 0 ? kind : kind + "  ·  aberto " + k + (k == 1 ? " vez" : " vezes"));
        // mesmo app (volta de um app, ou o mesmo nas duas seções): só o contador muda, sem piscar a vitrine
        if (a.key.equals(heroKey)) return;
        heroKey = a.key;
        heroName.setText(a.label);
        heroText.setAlpha(0f);
        heroText.setTranslationX(dp(12));
        heroText.animate().alpha(1f).translationX(0).setDuration(240).setInterpolator(Card.EXPO);

        // a arte nova entra por cima da anterior (troca B antes de A: A herda o drawable já solto)
        Drawable prev = artFront.getDrawable();
        boolean prevBanner = Boolean.TRUE.equals(artFront.getTag());
        int prevBg = ((ColorDrawable) artFront.getBackground()).getColor();
        setArt(artFront, copy(a.art), a.banner, a.banner ? CARD : deep(a));
        setArt(artBack, prev, prevBanner, prevBg);
        artFront.setAlpha(0f);
        artFront.animate().alpha(1f).setDuration(200).setInterpolator(Card.EXPO);

        washTo(tint(a));
    }

    private void showEmpty() {
        heroKey = null;
        heroName.setText("Nenhum app por aqui");
        heroMeta.setText("Instale um app e ele aparece aqui.");
        setArt(artFront, null, false, CARD);
        setArt(artBack, null, false, CARD);
        washTo(EMBER_DEEP);
    }

    // fundo opaco sempre: ícone sem banner não deixa a arte anterior aparecer por baixo
    private void setArt(ImageView v, Drawable d, boolean banner, int bg) {
        v.setTag(banner);
        v.setBackground(new ColorDrawable(bg));
        v.setScaleType(banner ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        int p = banner ? 0 : innerH / 2;
        v.setPadding(p, p, p, p);
        v.setImageDrawable(d);
    }

    private void washTo(int color) {
        if (washAnim == null) {
            washAnim = ValueAnimator.ofFloat(0f, 1f);
            washAnim.setDuration(320);
            washAnim.setInterpolator(Card.EXPO);
            washAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                public void onAnimationUpdate(ValueAnimator va) {
                    washColor = blend(washFrom, washTo, va.getAnimatedFraction());
                    washColors[0] = (washColor & 0xFFFFFF) | 0x80000000;
                    washColors[1] = washColor & 0xFFFFFF;
                    wash.setColors(washColors);
                }
            });
        }
        washAnim.cancel();
        washFrom = washColor;
        washTo = color;
        washAnim.start();
    }

    private static int blend(int a, int b, float f) {
        return Color.rgb(
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * f),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * f),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f));
    }

    // fundo de app sem banner: a cor dele bem escura, pra a grade não virar um mar de cinza
    private int deep(App a) {
        int t = tint(a);
        if (t == EMBER_DEEP) return CARD; // ícone cinza: sem cor própria, sem inventar ember
        Color.colorToHSV(t, hsv);
        hsv[1] = Math.min(hsv[1], 0.7f);
        hsv[2] = 0.24f;
        return Color.HSVToColor(hsv);
    }

    // cor dominante: média dos pixels pesada por saturação, num banner reduzido a 32x18
    private int tint(App a) {
        Integer cached = tints.get(a.key);
        if (cached != null) return cached;
        int out = EMBER_DEEP; // banner cinza ganha a luz da casa
        try {
            // sem ConstantState a "cópia" é o próprio drawable do cartão: devolve os bounds depois
            Drawable d = copy(a.art);
            Rect old = d.copyBounds();
            d.setBounds(0, 0, 32, 18);
            sample.eraseColor(0);
            d.draw(sampleCanvas);
            d.setBounds(old);
            sample.getPixels(samplePx, 0, 32, 0, 0, 32, 18);
            float r = 0, g = 0, b = 0, w = 0;
            for (int px : samplePx) {
                if (Color.alpha(px) < 128) continue;
                Color.colorToHSV(px, hsv);
                float k = hsv[1] * hsv[2];
                k *= k;
                r += k * Color.red(px);
                g += k * Color.green(px);
                b += k * Color.blue(px);
                w += k;
            }
            if (w > 4) {
                Color.colorToHSV(Color.rgb(Math.round(r / w), Math.round(g / w), Math.round(b / w)), hsv);
                hsv[1] = Math.min(hsv[1], 0.85f);
                hsv[2] = 0.55f; // brilho fixo: o nome em branco por cima nunca perde contraste
                out = Color.HSVToColor(hsv);
            }
        } catch (RuntimeException e) {
            // drawable exótico que não desenha fora da View: fica com a luz da casa
        }
        tints.put(a.key, out);
        return out;
    }

    @Override
    public void onBackPressed() {
        // é a tela inicial: voltar não sai daqui
    }
}
