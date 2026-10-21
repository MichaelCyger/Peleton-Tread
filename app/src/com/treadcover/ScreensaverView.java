package com.treadcover;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * DVD-style screensaver. The Peloton mark spins a full turn every two seconds and
 * bounces off the edges of the Watch area. Landing in a corner bursts it, rains
 * confetti for five seconds, then starts it again from the center on a new heading.
 */
public final class ScreensaverView extends View {
    private static final int BOUNCE = 0;
    private static final int CELEBRATE = 1;
    private static final float SPIN_DEGREES_PER_SEC = 180f;
    private static final float SPEED = 300f;
    private static final float CELEBRATE_SEC = 5f;
    private static final int[] CONFETTI = {
            Color.rgb(255, 255, 255),
            Color.rgb(96, 205, 170),
            Color.rgb(255, 196, 87),
            Color.rgb(255, 98, 98),
            Color.rgb(130, 176, 255),
            Color.rgb(214, 160, 255)
    };

    private final Paint background = new Paint();
    private final Paint logoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path logo = new Path();
    private final RectF ink = new RectF();
    private final Matrix matrix = new Matrix();
    private final Random random = new Random();
    private final List<Bit> bits = new ArrayList<>();

    private int mode = BOUNCE;
    private boolean running;
    private boolean placed;
    private long lastNanos;
    private float cx;
    private float cy;
    private float vx;
    private float vy;
    private float rotation;
    private float halfW;
    private float halfH;
    private float halfBox;
    private float bounceFor;
    private float celebrateFor;
    private float previewHold;
    private boolean previewPending;
    private float logoScale = 1f;
    private int logoAlpha = 255;
    private int sizedW;
    private int sizedH;

    public ScreensaverView(Context context) {
        super(context);
        setClickable(true);
        setVisibility(GONE);
        logoPaint.setStyle(Paint.Style.FILL);
        logoPaint.setColor(Color.rgb(206, 210, 216));
        bitPaint.setStyle(Paint.Style.FILL);
    }

    public boolean isRunning() {
        return running;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        mode = BOUNCE;
        placed = false;
        lastNanos = 0;
        bits.clear();
        previewPending = false;
        previewHold = 0f;
        setVisibility(VISIBLE);
        setAlpha(0f);
        animate().alpha(1f).setDuration(350).start();
        postInvalidateOnAnimation();
    }

    /** Park the mark in a corner, burst it, rain confetti, then bounce again. */
    public void previewCorner() {
        animate().cancel();
        setAlpha(1f);
        previewPending = true;
        previewHold = 0f;
        if (getWidth() > 0 && halfBox > 0f) {
            snapToCorner();
        }
        invalidate();
    }

    public void stop() {
        running = false;
        previewPending = false;
        previewHold = 0f;
        animate().cancel();
        setVisibility(GONE);
        bits.clear();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        sizedW = 0;
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) {
            if (running) {
                postInvalidateOnAnimation();
            }
            return;
        }
        float dt = frameDt();
        boolean relayout = sizedW != w || sizedH != h;
        float keepX = cx;
        float keepY = cy;
        boolean keep = placed && !previewPending && previewHold <= 0f;
        if (relayout) {
            layoutLogo(w, h);
        }
        if (previewPending && halfBox > 0f) {
            snapToCorner();
        } else if (!placed) {
            if (previewHold > 0f) {
                placed = true;
                if (relayout) {
                    placeFlushBottomRight();
                }
            } else if (keep) {
                cx = keepX;
                cy = keepY;
                placed = true;
                clampCenter();
            } else {
                placeInCenter();
            }
        } else if (relayout && previewHold > 0f) {
            placeFlushBottomRight();
        }
        if (mode == BOUNCE) {
            stepBounce(dt, w, h);
        } else {
            stepCelebrate(dt);
        }
        drawBackground(canvas, w, h);
        if (mode == BOUNCE || logoAlpha > 0) {
            drawLogo(canvas);
        }
        drawBits(canvas);
        if (running) {
            postInvalidateOnAnimation();
        }
    }

    private float frameDt() {
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0f : (now - lastNanos) / 1e9f;
        lastNanos = now;
        if (dt < 0f) {
            return 0f;
        }
        if (dt > 0.034f) {
            return 0.034f;
        }
        return dt;
    }

    private void layoutLogo(int w, int h) {
        sizedW = w;
        sizedH = h;
        background.setShader(new LinearGradient(
                0, 0, 0, h,
                Color.rgb(16, 19, 26),
                Color.rgb(7, 8, 11),
                Shader.TileMode.CLAMP));
        Path src = PelotonPath.create();
        RectF bounds = new RectF();
        src.computeBounds(bounds, true);
        float longer = Math.max(bounds.width(), bounds.height());
        float target = Math.min(w, h) * 0.20f;
        float scale = target / longer;
        matrix.setScale(scale, scale);
        matrix.postTranslate(-bounds.centerX() * scale, -bounds.centerY() * scale);
        logo.set(src);
        logo.transform(matrix);
        logo.computeBounds(ink, true);
        halfW = ink.width() / 2f;
        halfH = ink.height() / 2f;
        // Fixed box around the spinning mark, so a corner is a real DVD-logo hit
        // and the mark never draws past the edge.
        halfBox = (float) Math.hypot(halfW, halfH);
        placed = false;
    }

    private void placeInCenter() {
        float right = getWidth();
        float bottom = playBottom(getHeight());
        cx = right / 2f;
        cy = bottom / 2f;
        rotation = random.nextFloat() * 360f;
        randomHeading();
        bounceFor = 0f;
        logoScale = 1f;
        logoAlpha = 255;
        mode = BOUNCE;
        placed = true;
    }

    private void randomHeading() {
        float bottom = playBottom(getHeight());
        for (int i = 0; i < 40; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            if (Math.abs(Math.sin(angle)) < 0.38 || Math.abs(Math.cos(angle)) < 0.38) {
                continue;
            }
            float nvx = (float) (SPEED * Math.cos(angle));
            float nvy = (float) (SPEED * Math.sin(angle));
            float tx = nvx > 0f ? (getWidth() - halfBox - cx) / nvx : (cx - halfBox) / -nvx;
            float ty = nvy > 0f ? (bottom - halfBox - cy) / nvy : (cy - halfBox) / -nvy;
            if (tx > 0.4f && ty > 0.4f && Math.max(tx, ty) / Math.min(tx, ty) > 1.35f) {
                vx = nvx;
                vy = nvy;
                return;
            }
        }
        vx = SPEED * 0.82f;
        vy = -SPEED * 0.57f;
    }

    private void stepBounce(float dt, int w, int h) {
        if (previewHold > 0f) {
            previewHold -= dt;
            if (previewHold <= 0f) {
                explode();
            }
            return;
        }
        bounceFor += dt;
        rotation = (rotation + SPIN_DEGREES_PER_SEC * dt) % 360f;
        cx += vx * dt;
        cy += vy * dt;

        float left = cx - halfBox;
        float rightEdge = cx + halfBox;
        float top = cy - halfBox;
        float bottomEdge = cy + halfBox;
        float limitB = playBottom(h);
        boolean flipX = false;
        boolean flipY = false;

        if (left < 0f) {
            cx -= left;
            if (vx < 0f) {
                vx = -vx;
                flipX = true;
            }
        } else if (rightEdge > w) {
            cx -= rightEdge - w;
            if (vx > 0f) {
                vx = -vx;
                flipX = true;
            }
        }
        if (top < 0f) {
            cy -= top;
            if (vy < 0f) {
                vy = -vy;
                flipY = true;
            }
        } else if (bottomEdge > limitB) {
            cy -= bottomEdge - limitB;
            if (vy > 0f) {
                vy = -vy;
                flipY = true;
            }
        }
        // Both axes reverse on the same frame only when the box lands in a corner.
        if (flipX && flipY && bounceFor > 4f) {
            explode();
        }
    }

    private void explode() {
        mode = CELEBRATE;
        celebrateFor = 0f;
        logoScale = 1f;
        logoAlpha = 255;
        bits.clear();
        spawnShards(cx, cy);
        spawnConfetti(getWidth());
    }

    private void spawnShards(float x, float y) {
        for (int i = 0; i < 42; i++) {
            Bit bit = new Bit();
            double angle = random.nextDouble() * Math.PI * 2.0;
            float speed = 260f + random.nextFloat() * 780f;
            bit.x = x;
            bit.y = y;
            bit.vx = (float) Math.cos(angle) * speed;
            bit.vy = (float) Math.sin(angle) * speed;
            bit.w = 5f + random.nextFloat() * 8f;
            bit.h = bit.w * (0.6f + random.nextFloat());
            bit.rot = random.nextFloat() * 360f;
            bit.vr = -400f + random.nextFloat() * 800f;
            bit.color = i % 4 == 0 ? Color.rgb(96, 205, 170) : Color.rgb(230, 232, 236);
            bit.shard = true;
            bits.add(bit);
        }
    }

    private void spawnConfetti(int width) {
        for (int i = 0; i < 140; i++) {
            Bit bit = new Bit();
            bit.x = random.nextFloat() * width;
            bit.y = -30f - random.nextFloat() * 160f;
            bit.vx = -70f + random.nextFloat() * 140f;
            bit.vy = 40f + random.nextFloat() * 160f;
            bit.w = 8f + random.nextFloat() * 8f;
            bit.h = 14f + random.nextFloat() * 16f;
            bit.rot = random.nextFloat() * 360f;
            bit.vr = -220f + random.nextFloat() * 440f;
            bit.delay = random.nextFloat() * 3.2f;
            bit.phase = random.nextFloat() * 6.28f;
            bit.color = CONFETTI[random.nextInt(CONFETTI.length)];
            bit.shard = false;
            bits.add(bit);
        }
    }

    private void stepCelebrate(float dt) {
        celebrateFor += dt;
        if (celebrateFor < 0.42f) {
            float t = celebrateFor / 0.42f;
            logoScale = 1f + t * 0.85f;
            logoAlpha = (int) (255 * (1f - t));
        } else {
            logoAlpha = 0;
        }
        for (int i = 0; i < bits.size(); i++) {
            Bit bit = bits.get(i);
            if (celebrateFor < bit.delay) {
                continue;
            }
            if (!bit.shard) {
                bit.vy += 460f * dt;
                bit.x += (float) Math.sin(celebrateFor * 2.4 + bit.phase) * 28f * dt;
            }
            bit.x += bit.vx * dt;
            bit.y += bit.vy * dt;
            bit.rot += bit.vr * dt;
        }
        if (celebrateFor >= CELEBRATE_SEC) {
            bits.clear();
            placeInCenter();
        }
    }

    private void drawBackground(Canvas canvas, int w, int h) {
        canvas.drawRect(0, 0, w, h, background);
    }

    private void drawLogo(Canvas canvas) {
        canvas.save();
        canvas.translate(cx, cy);
        canvas.rotate(rotation);
        canvas.scale(logoScale, logoScale);
        logoPaint.setAlpha(logoAlpha);
        canvas.drawPath(logo, logoPaint);
        canvas.restore();
    }

    private void drawBits(Canvas canvas) {
        float fade = 1f;
        if (mode == CELEBRATE && celebrateFor > CELEBRATE_SEC - 0.35f) {
            fade = (CELEBRATE_SEC - celebrateFor) / 0.35f;
            if (fade < 0f) {
                fade = 0f;
            }
        }
        for (int i = 0; i < bits.size(); i++) {
            Bit bit = bits.get(i);
            if (celebrateFor < bit.delay) {
                continue;
            }
            float alpha = fade;
            if (bit.shard) {
                float age = celebrateFor - bit.delay;
                alpha *= Math.max(0f, 1f - age / 0.75f);
            }
            if (alpha <= 0f) {
                continue;
            }
            bitPaint.setColor(bit.color);
            bitPaint.setAlpha((int) (255 * alpha));
            canvas.save();
            canvas.translate(bit.x, bit.y);
            canvas.rotate(bit.rot);
            canvas.drawRoundRect(-bit.w / 2f, -bit.h / 2f, bit.w / 2f, bit.h / 2f, 2f, 2f, bitPaint);
            canvas.restore();
        }
    }

    /** The screensaver covers the whole display, including where the bars sit. */
    private float playBottom(int h) {
        return h;
    }

    private void snapToCorner() {
        previewPending = false;
        bits.clear();
        mode = BOUNCE;
        logoScale = 1f;
        logoAlpha = 255;
        placeFlushBottomRight();
        previewHold = 0.5f;
        placed = true;
    }

    private void placeFlushBottomRight() {
        double rad = Math.toRadians(rotation);
        float c = (float) Math.abs(Math.cos(rad));
        float s = (float) Math.abs(Math.sin(rad));
        float hw = halfW * c + halfH * s;
        float hh = halfW * s + halfH * c;
        cx = getWidth() - hw;
        cy = getHeight() - hh;
    }

    private void clampCenter() {
        float maxX = getWidth() - halfBox;
        float maxY = getHeight() - halfBox;
        if (cx < halfBox) {
            cx = halfBox;
        } else if (cx > maxX) {
            cx = maxX;
        }
        if (cy < halfBox) {
            cy = halfBox;
        } else if (cy > maxY) {
            cy = maxY;
        }
    }

    private static final class Bit {
        float x;
        float y;
        float vx;
        float vy;
        float w;
        float h;
        float rot;
        float vr;
        float delay;
        float phase;
        int color;
        boolean shard;
    }
}
