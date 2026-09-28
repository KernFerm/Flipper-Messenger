#include "messenger_protocol.h"

#include <furi_hal_crypto.h>
#include <string.h>

static void put_u16(uint8_t* p, uint16_t value) {
    p[0] = (uint8_t)value;
    p[1] = (uint8_t)(value >> 8);
}

static uint16_t get_u16(const uint8_t* p) {
    return (uint16_t)p[0] | ((uint16_t)p[1] << 8);
}

static void put_u32(uint8_t* p, uint32_t value) {
    for(size_t i = 0; i < 4; ++i) p[i] = (uint8_t)(value >> (i * 8));
}

static uint32_t get_u32(const uint8_t* p) {
    uint32_t value = 0;
    for(size_t i = 0; i < 4; ++i) value |= (uint32_t)p[i] << (i * 8);
    return value;
}

static void put_u64(uint8_t* p, uint64_t value) {
    for(size_t i = 0; i < 8; ++i) p[i] = (uint8_t)(value >> (i * 8));
}

static uint64_t get_u64(const uint8_t* p) {
    uint64_t value = 0;
    for(size_t i = 0; i < 8; ++i) value |= (uint64_t)p[i] << (i * 8);
    return value;
}

static void make_nonce(uint8_t nonce[FMSG_NONCE_SIZE], const FmsgMessage* message) {
    memcpy(nonce, message->session_id, 4);
    put_u64(nonce + 4, message->sequence);
}

bool fmsg_constant_time_equal(const uint8_t* left, const uint8_t* right, size_t length) {
    uint8_t difference = 0;
    for(size_t i = 0; i < length; ++i) difference |= left[i] ^ right[i];
    return difference == 0;
}

size_t fmsg_encode(
    uint8_t* output,
    size_t capacity,
    const FmsgMessage* message,
    const uint8_t key[FMSG_KEY_SIZE]) {
    if(!output || !message || message->payload_length > FMSG_MAX_PAYLOAD) return 0;
    const bool encrypted = (message->flags & FmsgFlagEncrypted) != 0;
    const size_t required = FMSG_HEADER_SIZE + message->payload_length + (encrypted ? FMSG_TAG_SIZE : 0);
    if(capacity < required) return 0;

    put_u32(output, FMSG_MAGIC);
    output[4] = FMSG_VERSION;
    output[5] = message->type;
    output[6] = message->flags;
    output[7] = 0;
    put_u16(output + 8, message->payload_length);
    output[10] = 0;
    output[11] = 0;
    put_u64(output + 12, message->request_id);
    put_u64(output + 20, message->sequence);
    memcpy(output + 28, message->session_id, 12);

    if(!encrypted) {
        if(message->payload_length) memcpy(output + FMSG_HEADER_SIZE, message->payload, message->payload_length);
        return required;
    }
    if(!key || message->sequence == 0) return 0;
    uint8_t nonce[FMSG_NONCE_SIZE];
    make_nonce(nonce, message);
    uint8_t* tag = output + FMSG_HEADER_SIZE + message->payload_length;
    if(furi_hal_crypto_gcm_encrypt_and_tag(
           key,
           nonce,
           output,
           FMSG_HEADER_SIZE,
           message->payload,
           output + FMSG_HEADER_SIZE,
           message->payload_length,
           tag) != FuriHalCryptoGCMStateOk) {
        memset(output, 0, required);
        return 0;
    }
    return required;
}

FmsgParseResult fmsg_decode(
    FmsgMessage* message,
    const uint8_t* frame,
    size_t frame_length,
    const uint8_t key[FMSG_KEY_SIZE],
    uint64_t minimum_sequence) {
    if(!message || !frame || frame_length < FMSG_HEADER_SIZE) return FmsgParseShort;
    if(get_u32(frame) != FMSG_MAGIC) return FmsgParseMagic;
    if(frame[4] != FMSG_VERSION) return FmsgParseVersion;
    const uint16_t payload_length = get_u16(frame + 8);
    if(payload_length > FMSG_MAX_PAYLOAD) return FmsgParseLength;
    const bool encrypted = (frame[6] & FmsgFlagEncrypted) != 0;
    const size_t expected = FMSG_HEADER_SIZE + payload_length + (encrypted ? FMSG_TAG_SIZE : 0);
    if(frame_length != expected) return FmsgParseLength;

    memset(message, 0, sizeof(*message));
    message->type = frame[5];
    message->flags = frame[6];
    message->payload_length = payload_length;
    message->request_id = get_u64(frame + 12);
    message->sequence = get_u64(frame + 20);
    memcpy(message->session_id, frame + 28, 12);

    if(!encrypted) {
        if(payload_length) memcpy(message->payload, frame + FMSG_HEADER_SIZE, payload_length);
        return FmsgParseOk;
    }
    if(!key) return FmsgParseAuth;
    if(message->sequence <= minimum_sequence) return FmsgParseReplay;
    uint8_t nonce[FMSG_NONCE_SIZE];
    make_nonce(nonce, message);
    const uint8_t* tag = frame + FMSG_HEADER_SIZE + payload_length;
    if(furi_hal_crypto_gcm_decrypt_and_verify(
           key,
           nonce,
           frame,
           FMSG_HEADER_SIZE,
           frame + FMSG_HEADER_SIZE,
           message->payload,
           payload_length,
           tag) != FuriHalCryptoGCMStateOk) {
        memset(message, 0, sizeof(*message));
        return FmsgParseAuth;
    }
    return FmsgParseOk;
}

