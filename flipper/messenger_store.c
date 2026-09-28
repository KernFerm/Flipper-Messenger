#include "messenger_store.h"

#include <furi.h>
#include <storage/storage.h>
#include <string.h>

#define STORE_PATH APP_DATA_PATH("messenger.dat")
#define STORE_TEMP APP_DATA_PATH("messenger.tmp")
#define STORE_BACKUP APP_DATA_PATH("messenger.bak")
#define STORE_MAGIC 0x31534D46u
#define STORE_VERSION 2u

static uint32_t hash_update(uint32_t hash, const uint8_t* data, size_t length) {
    for(size_t i = 0; i < length; ++i) {
        hash ^= data[i];
        hash *= 16777619u;
    }
    return hash;
}

static bool write_bytes(File* file, const void* data, size_t length, uint32_t* hash) {
    if(storage_file_write(file, data, length) != length) return false;
    if(hash) *hash = hash_update(*hash, data, length);
    return true;
}

static bool read_bytes(File* file, void* data, size_t length, uint32_t* hash) {
    if(storage_file_read(file, data, length) != length) return false;
    if(hash) *hash = hash_update(*hash, data, length);
    return true;
}

static bool write_u64(File* file, uint64_t value, uint32_t* hash) {
    uint8_t data[8];
    for(size_t i = 0; i < sizeof(data); ++i) data[i] = (uint8_t)(value >> (8 * i));
    return write_bytes(file, data, sizeof(data), hash);
}

static bool read_u64(File* file, uint64_t* value, uint32_t* hash) {
    uint8_t data[8];
    if(!read_bytes(file, data, sizeof(data), hash)) return false;
    *value = 0;
    for(size_t i = 0; i < sizeof(data); ++i) *value |= (uint64_t)data[i] << (8 * i);
    return true;
}

static bool write_record(File* file, const void* data, size_t size, uint32_t* hash) {
    return write_bytes(file, data, size, hash);
}

static bool read_record(File* file, void* data, size_t size, uint32_t* hash) {
    return read_bytes(file, data, size, hash);
}

void fmsg_store_init(FmsgStore* store) {
    furi_check(store);
    memset(store, 0, sizeof(*store));
    const char* defaults[] = {
        "On my way.",
        "Running late.",
        "Please call me.",
        "Made it safely.",
        "Can I call you later?",
    };
    store->quick_count = COUNT_OF(defaults);
    for(size_t i = 0; i < store->quick_count; ++i) {
        strlcpy(store->quick[i], defaults[i], sizeof(store->quick[i]));
    }
}

bool fmsg_phone_valid(const char* phone) {
    if(!phone || !*phone) return false;
    size_t digits = 0;
    for(const char* p = phone; *p; ++p) {
        if(*p >= '0' && *p <= '9') {
            ++digits;
        } else if(*p == '+' && p == phone) {
            continue;
        } else if(*p == ' ' || *p == '-' || *p == '(' || *p == ')') {
            continue;
        } else {
            return false;
        }
    }
    return digits >= 3 && digits <= 15;
}

static bool store_write_payload(File* file, const FmsgStore* store, uint32_t* hash) {
    uint32_t header[4] = {STORE_MAGIC, STORE_VERSION, (uint32_t)store->contact_count, (uint32_t)store->quick_count};
    uint32_t recent_count = (uint32_t)store->recent_count;
    uint32_t inbox_count = (uint32_t)store->inbox_count;
    uint8_t has_key = store->has_key ? 1 : 0;
    if(!write_bytes(file, header, sizeof(header), hash) ||
       !write_bytes(file, &recent_count, sizeof(recent_count), hash) ||
       !write_bytes(file, &inbox_count, sizeof(inbox_count), hash) ||
       !write_bytes(file, &has_key, sizeof(has_key), hash) ||
       !write_bytes(file, store->key, sizeof(store->key), hash) ||
       !write_u64(file, store->tx_sequence, hash) ||
       !write_u64(file, store->rx_sequence, hash) ||
       !write_record(file, store->draft_phone, sizeof(store->draft_phone), hash) ||
       !write_record(file, store->draft_body, sizeof(store->draft_body), hash)) return false;
    for(size_t i = 0; i < store->contact_count; ++i)
        if(!write_record(file, &store->contacts[i], sizeof(FmsgContact), hash)) return false;
    for(size_t i = 0; i < store->quick_count; ++i)
        if(!write_record(file, store->quick[i], sizeof(store->quick[i]), hash)) return false;
    for(size_t i = 0; i < store->recent_count; ++i)
        if(!write_record(file, &store->recent[i], sizeof(FmsgRecent), hash)) return false;
    for(size_t i = 0; i < store->inbox_count; ++i)
        if(!write_record(file, &store->inbox[i], sizeof(FmsgRecent), hash)) return false;
    return true;
}

bool fmsg_store_save(const FmsgStore* store) {
    if(!store || store->contact_count > FMSG_CONTACT_MAX || store->quick_count > FMSG_QUICK_MAX ||
       store->recent_count > FMSG_RECENT_MAX || store->inbox_count > FMSG_INBOX_MAX) return false;
    Storage* storage = furi_record_open(RECORD_STORAGE);
    storage_common_mkdir(storage, APP_DATA_PATH(""));
    File* file = storage_file_alloc(storage);
    bool ok = storage_file_open(file, STORE_TEMP, FSAM_WRITE, FSOM_CREATE_ALWAYS);
    uint32_t hash = 2166136261u;
    if(ok) ok = store_write_payload(file, store, &hash);
    if(ok) ok = write_bytes(file, &hash, sizeof(hash), NULL);
    if(ok) ok = storage_file_sync(file);
    storage_file_close(file);
    storage_file_free(file);
    if(!ok) {
        storage_common_remove(storage, STORE_TEMP);
        furi_record_close(RECORD_STORAGE);
        return false;
    }
    const bool had_old = storage_common_stat(storage, STORE_PATH, NULL) == FSE_OK;
    storage_common_remove(storage, STORE_BACKUP);
    if(had_old && storage_common_rename(storage, STORE_PATH, STORE_BACKUP) != FSE_OK) ok = false;
    if(ok && storage_common_rename(storage, STORE_TEMP, STORE_PATH) != FSE_OK) {
        if(had_old) storage_common_rename(storage, STORE_BACKUP, STORE_PATH);
        ok = false;
    }
    if(ok) storage_common_remove(storage, STORE_BACKUP);
    storage_common_remove(storage, STORE_TEMP);
    furi_record_close(RECORD_STORAGE);
    return ok;
}

static bool store_read_payload(File* file, FmsgStore* store, uint32_t* hash) {
    uint32_t header[4];
    uint32_t recent_count;
    uint32_t inbox_count = 0;
    uint8_t has_key;
    if(!read_bytes(file, header, sizeof(header), hash) || header[0] != STORE_MAGIC ||
       (header[1] != 1u && header[1] != STORE_VERSION) || header[2] > FMSG_CONTACT_MAX || header[3] > FMSG_QUICK_MAX ||
       !read_bytes(file, &recent_count, sizeof(recent_count), hash) || recent_count > FMSG_RECENT_MAX ||
       (header[1] >= 2u && (!read_bytes(file, &inbox_count, sizeof(inbox_count), hash) || inbox_count > FMSG_INBOX_MAX)) ||
       !read_bytes(file, &has_key, sizeof(has_key), hash) || has_key > 1 ||
       !read_bytes(file, store->key, sizeof(store->key), hash) ||
       !read_u64(file, &store->tx_sequence, hash) || !read_u64(file, &store->rx_sequence, hash) ||
       !read_record(file, store->draft_phone, sizeof(store->draft_phone), hash) ||
       !read_record(file, store->draft_body, sizeof(store->draft_body), hash)) return false;
    store->contact_count = header[2];
    store->quick_count = header[3];
    store->recent_count = recent_count;
    store->inbox_count = inbox_count;
    store->has_key = has_key != 0;
    for(size_t i = 0; i < store->contact_count; ++i)
        if(!read_record(file, &store->contacts[i], sizeof(FmsgContact), hash)) return false;
    for(size_t i = 0; i < store->quick_count; ++i)
        if(!read_record(file, store->quick[i], sizeof(store->quick[i]), hash)) return false;
    for(size_t i = 0; i < store->recent_count; ++i)
        if(!read_record(file, &store->recent[i], sizeof(FmsgRecent), hash)) return false;
    for(size_t i = 0; i < store->inbox_count; ++i)
        if(!read_record(file, &store->inbox[i], sizeof(FmsgRecent), hash)) return false;
    store->draft_phone[FMSG_PHONE_MAX] = 0;
    store->draft_body[FMSG_BODY_MAX] = 0;
    return true;
}

bool fmsg_store_load(FmsgStore* store) {
    if(!store) return false;
    FmsgStore* loaded = malloc(sizeof(FmsgStore));
    if(!loaded) return false;
    fmsg_store_init(loaded);
    Storage* storage = furi_record_open(RECORD_STORAGE);
    File* file = storage_file_alloc(storage);
    bool ok = storage_file_open(file, STORE_PATH, FSAM_READ, FSOM_OPEN_EXISTING);
    uint32_t hash = 2166136261u;
    if(ok) ok = store_read_payload(file, loaded, &hash);
    uint32_t stored_hash = 0;
    if(ok) ok = read_bytes(file, &stored_hash, sizeof(stored_hash), NULL) && stored_hash == hash;
    if(ok) ok = storage_file_get_error(file) == FSE_OK && storage_file_tell(file) == storage_file_size(file);
    storage_file_close(file);
    storage_file_free(file);
    furi_record_close(RECORD_STORAGE);
    if(ok) memcpy(store, loaded, sizeof(*store));
    free(loaded);
    return ok;
}

void fmsg_store_clear_private(FmsgStore* store) {
    if(!store) return;
    memset(store->contacts, 0, sizeof(store->contacts));
    memset(store->recent, 0, sizeof(store->recent));
    memset(store->inbox, 0, sizeof(store->inbox));
    memset(store->draft_phone, 0, sizeof(store->draft_phone));
    memset(store->draft_body, 0, sizeof(store->draft_body));
    memset(store->key, 0, sizeof(store->key));
    store->contact_count = 0;
    store->recent_count = 0;
    store->inbox_count = 0;
    store->has_key = false;
    store->tx_sequence = 0;
    store->rx_sequence = 0;
}

