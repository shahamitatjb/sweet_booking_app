package com.jb.service;

import lombok.Getter;

/** A validation failure tied to one request field, so the UI can highlight that input. */
@Getter
public class FieldValidationException extends IllegalArgumentException {
    private final String field;

    public FieldValidationException(String field, String message) {
        super(message);
        this.field = field;
    }
}
