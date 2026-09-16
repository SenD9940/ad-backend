package com.orinan.db.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class DataCryptConverter
        implements AttributeConverter<String, String> {

    private final AesGcmStringEncryptor encryptor;

    public DataCryptConverter(
            AesGcmStringEncryptor encryptor
    ) {
        this.encryptor = encryptor;
    }

    /**
     * 엔티티 값에서 DB 값으로 변환할 때 호출됩니다.
     *
     * repository.save(), EntityManager.persist() 등의 시점에
     * 평문을 암호화합니다.
     */
    @Override
    public String convertToDatabaseColumn(String attribute) {
        return encryptor.encrypt(attribute);
    }

    /**
     * DB 값에서 엔티티 값으로 변환할 때 호출됩니다.
     *
     * findById(), JPQL 조회 등의 시점에
     * 암호문을 평문으로 복호화합니다.
     */
    @Override
    public String convertToEntityAttribute(String dbData) {
        return encryptor.decrypt(dbData);
    }
}