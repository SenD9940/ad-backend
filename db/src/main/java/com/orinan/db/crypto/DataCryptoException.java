package com.orinan.db.crypto;

public class DataCryptoException extends RuntimeException {

    public DataCryptoException(String message) {
        super(message);
    }

    public DataCryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}