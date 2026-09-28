#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include "messenger_protocol.h"

#define FMSG_CONTACT_MAX 50
#define FMSG_NAME_MAX 31
#define FMSG_PHONE_MAX 23
#define FMSG_BODY_MAX 320
#define FMSG_QUICK_MAX 12
#define FMSG_RECENT_MAX 20
#define FMSG_INBOX_MAX 10

typedef struct {
    char name[FMSG_NAME_MAX + 1];
    char phone[FMSG_PHONE_MAX + 1];
} FmsgContact;

typedef struct {
    char phone[FMSG_PHONE_MAX + 1];
    char body[FMSG_BODY_MAX + 1];
    uint64_t request_id;
    uint8_t status;
} FmsgRecent;

typedef struct {
    FmsgContact contacts[FMSG_CONTACT_MAX];
    size_t contact_count;
    char quick[FMSG_QUICK_MAX][FMSG_BODY_MAX + 1];
    size_t quick_count;
    FmsgRecent recent[FMSG_RECENT_MAX];
    size_t recent_count;
    FmsgRecent inbox[FMSG_INBOX_MAX];
    size_t inbox_count;
    char draft_phone[FMSG_PHONE_MAX + 1];
    char draft_body[FMSG_BODY_MAX + 1];
    uint8_t key[FMSG_KEY_SIZE];
    bool has_key;
    uint64_t tx_sequence;
    uint64_t rx_sequence;
} FmsgStore;

void fmsg_store_init(FmsgStore* store);
bool fmsg_store_load(FmsgStore* store);
bool fmsg_store_save(const FmsgStore* store);
void fmsg_store_clear_private(FmsgStore* store);
bool fmsg_phone_valid(const char* phone);

