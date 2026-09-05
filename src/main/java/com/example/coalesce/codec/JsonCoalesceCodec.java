package com.example.coalesce.codec;

import com.example.coalesce.exception.CoalesceCodecException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.lang.reflect.Type;
import org.springframework.stereotype.Component;

/**
 * JSON payloads. Deliberately not Java native serialization: that would force every
 * cached DTO to implement Serializable, break across rolling deploys whenever a class
 * shape changes while old and new pods share one Redis, and deserializing untrusted
 * bytes is a known RCE vector.
 *
 * <p>Jackson is confined to this class — the {@link CoalesceCodec} SPI and the aspect
 * both stay library-agnostic.
 */
@Component
public class JsonCoalesceCodec implements CoalesceCodec {

    private final ObjectMapper mapper;

    public JsonCoalesceCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public byte[] encode(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new CoalesceCodecException("encode failed", e);
        }
    }

    @Override
    public Object decode(byte[] bytes, Type type) {
        try {
            return mapper.readValue(bytes, mapper.getTypeFactory().constructType(type));
        } catch (IOException e) {
            throw new CoalesceCodecException("decode failed", e);
        }
    }
}
