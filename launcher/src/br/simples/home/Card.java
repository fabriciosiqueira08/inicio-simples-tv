package br.simples.home;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;

// Cartão 16:9 com o foco da casa: cresce, acende o anel ember (com folga do cartão) e eleva.
// ponytail: sombra neutra; brilho ember é só de CTA no DESIGN.md, aqui o anel é o sinal único.
final class Card extends FrameLayout {
    static final TimeInterpolator EXPO = new PathInterpolator(0.16f, 1f, 0.3f, 1f);

    final Home.App app;
    final String section;
    private final GradientDrawable ring = new GradientDrawable();
    private final float lift;
    private float p;
    private ValueAnimator anim;

    Card(Context c, Home.App app, String section, final int pad, final float radius) {
        super(c);
        this.app = app;
        this.section = section;
        float d = getResources().getDisplayMetrics().density;
        lift = 14 * d;
        setPadding(pad, pad, pad, pad);
        ring.setCornerRadius(radius + pad);
        ring.setStroke(Math.round(3 * d), Home.EMBER);
        ring.setAlpha(0);
        setBackground(ring);
        // a sombra sai do cartão de dentro, não do anel: a folga entre os dois fica limpa
        setOutlineProvider(new ViewOutlineProvider() {
            public void getOutline(View v, Outline o) {
                o.setRoundRect(pad, pad, v.getWidth() - pad, v.getHeight() - pad, radius);
            }
        });
        setFocusable(true);
        setFocusableInTouchMode(true); // se a TV acordar em touch mode, o foco inicial ainda pega
        setClickable(true);
        setDefaultFocusHighlightEnabled(false);
        apply();
    }

    // ponytail: sem camada offscreen por cartão; o alpha de repouso vale por elemento, diferença invisível
    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    protected void onFocusChanged(boolean gain, int dir, Rect prev) {
        super.onFocusChanged(gain, dir, prev);
        if (anim == null) {
            anim = new ValueAnimator();
            anim.setInterpolator(EXPO);
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                public void onAnimationUpdate(ValueAnimator a) {
                    p = (Float) a.getAnimatedValue();
                    apply();
                }
            });
        }
        anim.cancel();
        anim.setFloatValues(p, gain ? 1f : 0f);
        anim.setDuration(gain ? 200 : 150);
        anim.start();
    }

    @Override
    public void setPressed(boolean pressed) {
        super.setPressed(pressed);
        apply();
    }

    private void apply() {
        float s = (1f + 0.08f * p) * (isPressed() ? 0.96f : 1f);
        setScaleX(s);
        setScaleY(s);
        setAlpha(0.78f + 0.22f * p);
        setTranslationZ(lift * p);
        ring.setAlpha(Math.round(255 * p));
    }
}
