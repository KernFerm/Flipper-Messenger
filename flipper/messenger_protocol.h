#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define FMSG_MAGIC 0x47534D46u /* FMSG, little endian */
#define FMSG_VERSION 1u
#define FMSG_KEY_SIZE 32u
#define FMSG_NONCE_SIZE 12u
#define FMSG_TAG_SIZE 16u
#define FMSG_HEADER_SIZE 40u
#define FMSG_MAX_PAYLOAD 400u
#define FMSG_MAX_FRAME (FMSG_HEADER_SIZE + FMSG_MAX_PAYLOAD + FMSG_TAG_SIZE)

typedef enum {
    FmsgTypeHello = 1,
    FmsgTypeHelloReply = 2,
    FmsgTypeProvisionRequest = 3,
    FmsgTypeProvisionKey = 4,
    FmsgTypeAuthChallenge = 5,
    FmsgTypeAuthResponse = 6,
    FmsgTypePing = 7,
    FmsgTypePong = 8,
    FmsgTypeDeviceInfo = 9,
    FmsgTypeContactSyncBegin = 16,
    FmsgTypeContactEntry = 17,
    FmsgTypeContactSyncEnd = 18,
    FmsgTypeContactSyncResult = 19,
    FmsgTypeSendSms = 32,
    FmsgTypeRequestAccepted = 33,
    FmsgTypeSmsSent = 34,
    FmsgTypeSmsDelivered = 35,
    FmsgTypeSmsFailed = 36,
    FmsgTypeIncomingSms = 48,
    FmsgTypeError = 127,
} FmsgType;

typedef enum {
    FmsgFlagEncrypted = 1u,
    FmsgFlagResponse = 2u,
} FmsgFlags;

typedef enum {
    FmsgParseOk,
    FmsgParseShort,
    FmsgParseMagic,
    FmsgParseVersion,
    FmsgParseLength,
    FmsgParseReplay,
    FmsgParseAuth,
    FmsgParseCrypto,
} FmsgParseResult;

typedef struct {
    uint8_t type;
    uint8_t flags;
    uint16_t payload_length;
    uint64_t request_id;
    uint64_t sequence;
    uint8_t session_id[16];
    uint8_t payload[FMSG_MAX_PAYLOAD];
} FmsgMessage;

size_t fmsg_encode(
    uint8_t* output,
    size_t capacity,
    const FmsgMessage* message,
    const uint8_t key[FMSG_KEY_SIZE]);

FmsgParseResult fmsg_decode(
    FmsgMessage* message,
    const uint8_t* frame,
    size_t frame_length,
    const uint8_t key[FMSG_KEY_SIZE],
    uint64_t minimum_sequence);

bool fmsg_constant_time_equal(const uint8_t* left, const uint8_t* right, size_t length);

