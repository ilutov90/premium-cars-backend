package com.premiumcars.importing;

/** The automatic import read a listing whose mileage is over the admin's limit: nothing was saved. */
public class MileageOverLimitException extends RuntimeException {

    private final int mileageKm;

    public MileageOverLimitException(int mileageKm, int limitKm) {
        super("Mileage " + mileageKm + " km is over the limit of " + limitKm + " km: skipped");
        this.mileageKm = mileageKm;
    }

    public int mileageKm() {
        return mileageKm;
    }
}
