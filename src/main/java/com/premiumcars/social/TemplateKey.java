package com.premiumcars.social;

/**
 * Which text template: Facebook and Instagram share {@code FACEBOOK}, Viber has its own (and
 * {@code VIBER_SHORT}, the short text sent under the car's link in a "link + text" post), and mobile.bg's
 * "Допълнителна информация" has {@code MOBILE_BG}. Stored as {@code post_template.network}.
 */
public enum TemplateKey {
    FACEBOOK,
    VIBER,
    VIBER_SHORT,
    MOBILE_BG
}
