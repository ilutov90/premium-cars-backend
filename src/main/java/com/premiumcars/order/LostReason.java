package com.premiumcars.order;

/** Why a customer did not buy (a cancelled order); stored as its {@code name()}. */
public enum LostReason {
    PRICE,
    BOUGHT_ELSEWHERE,
    NO_ANSWER,
    CHANGED_MIND,
    OTHER
}
