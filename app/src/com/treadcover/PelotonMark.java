package com.treadcover;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;

/**
 * Faint Peloton mark in the upper right of Watch, behind the tiles.
 * Path is the Peloton symbol, viewBox 0 0 24 24.
 */
public final class PelotonMark extends View {
    private final Paint background = new Paint();
    private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path symbol = new Path();
    private final Matrix matrix = new Matrix();
    private int drawnWidth;
    private int drawnHeight;

    public PelotonMark(Context context) {
        super(context);
        setClickable(false);
        setFocusable(false);
        mark.setStyle(Paint.Style.FILL);
        mark.setColor(Color.WHITE);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        drawnWidth = 0;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        if (drawnWidth != w || drawnHeight != h) {
            rebuild(w, h);
        }
        canvas.drawRect(0, 0, w, h, background);
        int layer = canvas.saveLayerAlpha(0, 0, w, h, 18);
        canvas.drawPath(symbol, mark);
        canvas.restoreToCount(layer);
    }

    private void rebuild(int w, int h) {
        drawnWidth = w;
        drawnHeight = h;
        background.setShader(new LinearGradient(
                0, 0, 0, h,
                Color.rgb(16, 19, 26),
                Color.rgb(7, 8, 11),
                Shader.TileMode.CLAMP));

        // About a quarter of the screen, sitting in the upper right.
        float size = (float) Math.sqrt(w * (double) h * 0.25);
        float left = w - size - w * 0.02f;
        float top = h * 0.012f;
        symbol.set(PelotonPath.create());
        matrix.setScale(size / 24f, size / 24f);
        matrix.postTranslate(left, top);
        symbol.transform(matrix);
    }
}
