package com.gallery.app;

final class OcrEntity {
    static final int PHONE = 1;
    static final int URL = 2;
    static final int EMAIL = 3;

    final int type;
    final int from;
    final int to;
    final String value;

    OcrEntity(int type, int from, int to, String value) {
        this.type = type;
        this.from = from;
        this.to = to;
        this.value = value;
    }
}
