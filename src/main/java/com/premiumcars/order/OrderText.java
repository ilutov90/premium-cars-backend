package com.premiumcars.order;

final class OrderText {

    private OrderText() {
    }

    /** Trimmed; blank as null. */
    static String clean(String s) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        return t.isEmpty() ? null : t;
    }
}
