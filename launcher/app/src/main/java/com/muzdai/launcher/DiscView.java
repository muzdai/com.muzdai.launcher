package com.muzdai.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

/** A track's cover in a circle, centre-cropped, with a faint rim. Still: a turning cover shimmers on the TV. */
final class DiscView extends View {
    private final Paint cover = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Bitmap bitmap;

    DiscView(Context ctx) {
        super(ctx);
        rim.setStyle(Paint.Style.STROKE);
        rim.setColor(0x40FFFFFF);
    }

    void setBitmap(Bitmap b) {
        bitmap = b;
        cover.setShader(b == null ? null : new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        placeShader();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        placeShader();
    }

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
        float stroke = Math.max(1f, getResources().getDisplayMetrics().density);
        rim.setStrokeWidth(stroke);
        c.drawCircle(cx, cy, r - stroke / 2f, rim);
    }
}
