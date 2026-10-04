package com.jb.service;

/**
 * Razorpay could not be reached or did not answer, so the payment state is unknown. Never a
 * reason to confirm a booking: the caller retries later (checkout retry, webhook or the
 * reconciliation job).
 */
public class GatewayUnavailableException extends RuntimeException {
    public GatewayUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
