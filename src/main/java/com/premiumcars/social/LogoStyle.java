package com.premiumcars.social;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** How the logo is drawn: header height (24..64 px), corner radius (0..32 px), edge feather (0..40 %); null = default. */
public record LogoStyle(
        @Min(24) @Max(64) Integer height,
        @Min(0) @Max(32) Integer radius,
        @Min(0) @Max(40) Integer feather) {

    public static final LogoStyle NONE = new LogoStyle(null, null, null);
}
