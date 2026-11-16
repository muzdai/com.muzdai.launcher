package com.muzdai.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

/** A track's cover as a record: round, with faint grooves and a spindle hole. Turned by its owner while playing. */
final class DiscView extends View {
    private final Paint cover = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint groove = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hole = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Bitmap bitmap;

    DiscView(Context ctx) {
        super(ctx);
        groove.setStyle(Paint.Style.STROKE);
        hole.setColor(0xFF101010);
        rim.setStyle(Paint.Style.STROKE);
    }

    void setBitmap(Bitmap b) {
        bitmap = b;
        if (b != null) cover.setShader(new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        else cover.setShader(null);
        placeShader();
        invalidate();
    }

    boolean hasBitmap() {
        return bitmap != null;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        placeShader();
    }

    /** Centre-crops the cover into the circle. */
    private void placeShader() {
        if (bitmap == null || getWidth() == 0) return;
        float size = Math.min(getWidth(), getHeight());
        float scale = size / Math.min(bitmap.getWidth(), bitmap.getHeight());
        Matrix m = new Matrix();
        m.setScale(scale, scale);
        m.postTranslate((getWidth() - bitmap.getWidth() * scale) / 2f, (getHeight() - bitmap.getHeight() * scale) / 2f);
        cover.getShader().setLocalMatrix(m);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (bitmap == null) return;
        float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy);
        c.drawCircle(cx, cy, r, cover);
        float stroke = Math.max(1f, r * 0.03f);
        groove.setStrokeWidth(stroke);
        for (float f = 0.92f; f > 0.35f; f -= 0.14f) {
            groove.setColor(0x26000000);
            c.drawCircle(cx, cy, r * f, groove);
            groove.setColor(0x14FFFFFF);
            c.drawCircle(cx, cy, r * f - stroke, groove);
        }
        c.drawCircle(cx, cy, r * 0.16f, hole);
        rim.setStrokeWidth(stroke);
        rim.setColor(0x66FFFFFF);
        c.drawCircle(cx, cy, r * 0.16f, rim);
        rim.setColor(0x40FFFFFF);
        c.drawCircle(cx, cy, r - stroke / 2f, rim);
    }
}
