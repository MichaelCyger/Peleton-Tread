package com.treadcover;

import android.graphics.Path;

/** Peloton symbol. viewBox 0 0 24 24. */
public final class PelotonPath {
    private PelotonPath() {
    }

    public static Path create() {
        Path path = new Path();
        path.moveTo(17.7283f, 5.7642f);
        path.rLineTo(1.7307f, -3.0066f);
        path.rCubicTo(0.5045f, -0.8803f, 0.2077f, -2.0077f, -0.6725f, -2.5121f);
        path.rCubicTo(-0.8802f, -0.5044f, -2.0077f, -0.2077f, -2.5121f, 0.6725f);
        path.rLineTo(-1.7407f, 3.0066f);
        path.rCubicTo(-3.699f, -1.167f, -7.843f, 0.3462f, -9.8606f, 3.8473f);
        path.rCubicTo(-1.2857f, 2.2253f, -1.444f, 4.7869f, -0.6626f, 7.032f);
        path.rLineTo(3.2044f, -5.5583f);
        path.rCubicTo(0.732f, -1.2759f, 1.9286f, -2.1858f, 3.3528f, -2.5715f);
        path.rCubicTo(1.4242f, -0.3857f, 2.9078f, -0.188f, 4.1836f, 0.5539f);
        path.rCubicTo(2.6308f, 1.523f, 3.5407f, 4.9055f, 2.0176f, 7.5363f);
        path.rCubicTo(-1.523f, 2.6308f, -4.8957f, 3.5407f, -7.5364f, 2.0176f);
        path.rLineTo(1.8396f, -3.1846f);
        path.rCubicTo(0.8803f, 0.5044f, 2.0077f, 0.2077f, 2.5122f, -0.6726f);
        path.rCubicTo(0.5044f, -0.8802f, 0.2076f, -2.0077f, -0.6726f, -2.512f);
        path.rCubicTo(-0.8802f, -0.5045f, -2.0077f, -0.2078f, -2.5121f, 0.6725f);
        path.rLineTo(-5.855f, 10.1572f);
        path.rCubicTo(-0.5044f, 0.8803f, -0.2077f, 2.0077f, 0.6725f, 2.5121f);
        path.rCubicTo(0.8802f, 0.5044f, 2.0077f, 0.2077f, 2.5121f, -0.6725f);
        path.lineTo(9.47f, 20.0754f);
        path.rCubicTo(3.699f, 1.167f, 7.843f, -0.3462f, 9.8606f, -3.8473f);
        path.rCubicTo(2.0176f, -3.4913f, 1.256f, -7.833f, -1.6022f, -10.4639f);
        path.close();
        return path;
    }
}
