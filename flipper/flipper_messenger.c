/* SPDX-License-Identifier: GPL-3.0-or-later */
#include "messenger_protocol.h"
#include "messenger_store.h"

#include <applications/services/bt/bt_service/bt.h>
#include <furi.h>
#include <furi_hal_bt.h>
#include <furi_hal_random.h>
#include <gui/gui.h>
#include <gui/modules/submenu.h>
#include <gui/modules/text_input.h>
#include <gui/modules/widget.h>
#include <gui/view_dispatcher.h>
#include <storage/storage.h>
#include <targets/f7/ble_glue/profiles/serial_profile.h>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define FMSG_APP_VERSION "0.0.10"
#define FMSG_BT_KEYS APP_DATA_PATH("bt.keys")
#define FMSG_RX_SLOTS 16

typedef enum { ViewMain, ViewList, ViewInput, ViewSettings, ViewText } AppView;
typedef enum {
    MenuContacts,
    MenuManual,
    MenuCompose,
    MenuQuick,
    MenuInbox,
    MenuDraft,
    MenuRecent,
    MenuSettings,
    MenuAbout,
} MainMenu;
typedef enum { EditPhone, EditBody, EditQuick } EditMode;

typedef struct {
    uint16_t length;
    uint8_t data[FMSG_MAX_FRAME];
} RxSlot;

typedef struct {
    Gui* gui;
    ViewDispatcher* dispatcher;
    Submenu* main_menu;
    Submenu* list_menu;
    TextInput* input;
    Submenu* settings;
    Widget* widget;
    FuriString* text;
    Bt* bt;
    FuriHalBleProfileBase* profile;
    FuriMutex* rx_mutex;
    RxSlot rx[FMSG_RX_SLOTS];
    uint8_t rx_head;
    uint8_t rx_tail;
    uint8_t rx_count;
    bool connected;
    bool hello_pending;
    bool authenticated;
    bool allow_provision;
    bool confirm_open;
    uint32_t provision_deadline;
    uint8_t session_id[16];
    uint8_t challenge[16];
    FmsgStore store;
    FmsgContact staged_contacts[FMSG_CONTACT_MAX];
    size_t staged_count;
    size_t staged_expected;
    char phone[FMSG_PHONE_MAX + 1];
    char body[FMSG_BODY_MAX + 1];
    EditMode edit_mode;
    size_t edit_quick_index;
    size_t selected_inbox;
    AppView current;
    AppView text_back;
} MessengerApp;

static void switch_view(MessengerApp* app, AppView view) {
    app->current = view;
    view_dispatcher_switch_to_view(app->dispatcher, view);
}

static const char* link_text(const MessengerApp* app) {
    if(!app->connected) return "Advertising";
    if(!app->store.has_key) return "Connected: authorize";
    if(!app->authenticated) return "Connected: authenticating";
    return "Phone ready";
}

static void show_text(MessengerApp* app, const char* title, const char* body, AppView back) {
    widget_reset(app->widget);
    furi_string_printf(app->text, "\e#%s\n%s", title, body);
    widget_add_text_scroll_element(app->widget, 0, 0, 128, 64, furi_string_get_cstr(app->text));
    app->text_back = back;
    switch_view(app, ViewText);
}

static bool tx_message(MessengerApp* app, FmsgMessage* message, bool encrypted) {
    if(!app->profile || !app->connected) return false;
    message->flags = encrypted ? FmsgFlagEncrypted : 0;
    if(encrypted) {
        if(!app->store.has_key) return false;
        message->sequence = ++app->store.tx_sequence;
    }
    memcpy(message->session_id, app->session_id, sizeof(message->session_id));
    uint8_t frame[FMSG_MAX_FRAME];
    size_t length = fmsg_encode(frame, sizeof(frame), message, encrypted ? app->store.key : NULL);
    if(!length) return false;
    bool sent = ble_profile_serial_tx(app->profile, frame, length);
    if(sent && encrypted && (app->store.tx_sequence & 15u) == 0) fmsg_store_save(&app->store);
    return sent;
}

static void send_hello(MessengerApp* app) {
    FmsgMessage msg = {.type = FmsgTypeHelloReply, .payload_length = 33};
    msg.payload[0] = app->store.has_key ? 1 : 0;
    memcpy(msg.payload + 1, app->session_id, 16);
    memcpy(msg.payload + 17, app->challenge, 16);
    app->hello_pending = !tx_message(app, &msg, false);
}

static void send_error(MessengerApp* app, uint64_t request_id, uint8_t code, const char* text) {
    if(!app->authenticated) return;
    FmsgMessage msg = {.type = FmsgTypeError, .request_id = request_id};
    size_t length = strlen(text);
    if(length > FMSG_MAX_PAYLOAD - 2) length = FMSG_MAX_PAYLOAD - 2;
    msg.payload[0] = code;
    msg.payload[1] = (uint8_t)length;
    memcpy(msg.payload + 2, text, length);
    msg.payload_length = length + 2;
    tx_message(app, &msg, true);
}

static void recent_status(MessengerApp* app, uint64_t request, uint8_t status) {
    for(size_t i = 0; i < app->store.recent_count; ++i) {
        if(app->store.recent[i].request_id == request) {
            app->store.recent[i].status = status;
            fmsg_store_save(&app->store);
            return;
        }
    }
}

static void process_message(MessengerApp* app, const uint8_t* data, size_t length) {
    FmsgMessage message;
    FmsgParseResult parsed = fmsg_decode(
        &message, data, length, app->store.has_key ? app->store.key : NULL, app->store.rx_sequence);
    if(parsed != FmsgParseOk) return;

    if(!(message.flags & FmsgFlagEncrypted)) {
        if(message.type == FmsgTypeHello) {
            send_hello(app);
        } else if(message.type == FmsgTypeProvisionKey && app->allow_provision &&
                  furi_get_tick() <= app->provision_deadline && message.payload_length == FMSG_KEY_SIZE) {
            memcpy(app->store.key, message.payload, FMSG_KEY_SIZE);
            app->store.has_key = true;
            app->store.rx_sequence = 0;
            app->store.tx_sequence = 0;
            app->allow_provision = false;
            fmsg_store_save(&app->store);
            send_hello(app);
        }
        return;
    }
    if(memcmp(message.session_id, app->session_id, 12) != 0) return;
    app->store.rx_sequence = message.sequence;

    if(message.type == FmsgTypeAuthResponse) {
        if(message.payload_length == sizeof(app->challenge) &&
           fmsg_constant_time_equal(message.payload, app->challenge, sizeof(app->challenge))) {
            app->authenticated = true;
            FmsgMessage reply = {.type = FmsgTypeRequestAccepted, .flags = FmsgFlagResponse};
            reply.payload[0] = 1;
            reply.payload_length = 1;
            tx_message(app, &reply, true);
        }
        return;
    }
    if(!app->authenticated) return;
    switch(message.type) {
    case FmsgTypePing: {
        FmsgMessage pong = {.type = FmsgTypePong, .request_id = message.request_id};
        tx_message(app, &pong, true);
        break;
    }
    case FmsgTypeContactSyncBegin:
        app->staged_count = 0;
        app->staged_expected =
            message.payload_length == 1 && message.payload[0] <= FMSG_CONTACT_MAX ? message.payload[0] : SIZE_MAX;
        break;
    case FmsgTypeContactEntry: {
        if(message.payload_length < 2 || app->staged_count >= FMSG_CONTACT_MAX) break;
        const uint8_t name_len = message.payload[0];
        const uint8_t phone_len = message.payload[1];
        if(name_len > FMSG_NAME_MAX || phone_len > FMSG_PHONE_MAX ||
           (size_t)name_len + phone_len + 2 != message.payload_length) break;
        FmsgContact* contact = &app->staged_contacts[app->staged_count];
        memcpy(contact->name, message.payload + 2, name_len);
        contact->name[name_len] = 0;
        memcpy(contact->phone, message.payload + 2 + name_len, phone_len);
        contact->phone[phone_len] = 0;
        if(fmsg_phone_valid(contact->phone)) ++app->staged_count;
        break;
    }
    case FmsgTypeContactSyncEnd: {
        if(app->staged_expected == SIZE_MAX || app->staged_count != app->staged_expected) {
            send_error(app, message.request_id, 2, "Contact transfer incomplete");
            break;
        }
        FmsgContact* old_contacts = malloc(sizeof(app->store.contacts));
        if(!old_contacts) {
            send_error(app, message.request_id, 3, "Not enough memory to save contacts");
            break;
        }
        const size_t old_count = app->store.contact_count;
        memcpy(old_contacts, app->store.contacts, sizeof(app->store.contacts));
        memset(app->store.contacts, 0, sizeof(app->store.contacts));
        memcpy(app->store.contacts, app->staged_contacts, app->staged_count * sizeof(FmsgContact));
        app->store.contact_count = app->staged_count;
        const bool saved = fmsg_store_save(&app->store);
        if(!saved) {
            memcpy(app->store.contacts, old_contacts, sizeof(app->store.contacts));
            app->store.contact_count = old_count;
        }
        free(old_contacts);
        app->staged_expected = SIZE_MAX;
        if(!saved) {
            send_error(app, message.request_id, 4, "Could not save contacts to microSD");
            break;
        }
        FmsgMessage result = {
            .type = FmsgTypeContactSyncResult,
            .flags = FmsgFlagResponse,
            .request_id = message.request_id,
            .payload_length = 1,
        };
        result.payload[0] = (uint8_t)app->store.contact_count;
        tx_message(app, &result, true);
        break;
    }
    case FmsgTypeRequestAccepted:
        recent_status(app, message.request_id, 1);
        break;
    case FmsgTypeSmsSent:
        recent_status(app, message.request_id, 2);
        break;
    case FmsgTypeSmsDelivered:
        recent_status(app, message.request_id, 3);
        break;
    case FmsgTypeSmsFailed:
        recent_status(app, message.request_id, 4);
        break;
    case FmsgTypeIncomingSms: {
        if(message.payload_length < 3) break;
        const uint8_t sender_len = message.payload[0];
        const uint16_t body_len = (uint16_t)message.payload[1] | ((uint16_t)message.payload[2] << 8);
        if(sender_len == 0 || sender_len > FMSG_PHONE_MAX || body_len == 0 || body_len > FMSG_BODY_MAX ||
           (size_t)sender_len + body_len + 3 != message.payload_length) break;
        if(app->store.inbox_count == FMSG_INBOX_MAX) {
            memmove(&app->store.inbox[1], &app->store.inbox[0], (FMSG_INBOX_MAX - 1) * sizeof(FmsgRecent));
        } else {
            memmove(&app->store.inbox[1], &app->store.inbox[0], app->store.inbox_count * sizeof(FmsgRecent));
            ++app->store.inbox_count;
        }
        FmsgRecent* incoming = &app->store.inbox[0];
        memset(incoming, 0, sizeof(*incoming));
        memcpy(incoming->phone, message.payload + 3, sender_len);
        incoming->phone[sender_len] = 0;
        memcpy(incoming->body, message.payload + 3 + sender_len, body_len);
        incoming->body[body_len] = 0;
        fmsg_store_save(&app->store);
        break;
    }
    default:
        send_error(app, message.request_id, 1, "Unsupported operation");
        break;
    }
}

static uint16_t serial_callback(SerialServiceEvent event, void* context) {
    MessengerApp* app = context;
    if(event.event != SerialServiceEventTypeDataReceived || !event.data.buffer ||
       event.data.size > FMSG_MAX_FRAME) return FMSG_MAX_FRAME;
    if(furi_mutex_acquire(app->rx_mutex, 0) != FuriStatusOk) return 0;
    if(app->rx_count < FMSG_RX_SLOTS) {
        RxSlot* slot = &app->rx[app->rx_head];
        slot->length = event.data.size;
        memcpy(slot->data, event.data.buffer, event.data.size);
        app->rx_head = (app->rx_head + 1) % FMSG_RX_SLOTS;
        ++app->rx_count;
    }
    uint16_t free_bytes = (FMSG_RX_SLOTS - app->rx_count) * FMSG_MAX_FRAME;
    furi_mutex_release(app->rx_mutex);
    return free_bytes;
}

static void bt_status(BtStatus status, void* context) {
    MessengerApp* app = context;
    const bool now_connected = status == BtStatusConnected;
    if(now_connected && !app->connected) {
        /* The system BT service installs its RPC callback when the serial profile connects.
         * Reclaim the profile for this application after that connection notification. */
        if(app->profile) {
            ble_profile_serial_set_event_callback(
                app->profile, FMSG_RX_SLOTS * FMSG_MAX_FRAME, serial_callback, app);
            ble_profile_serial_set_rpc_active(app->profile, false);
        }
        furi_hal_random_fill_buf(app->session_id, sizeof(app->session_id));
        furi_hal_random_fill_buf(app->challenge, sizeof(app->challenge));
        app->store.rx_sequence = 0;
        app->store.tx_sequence = 0;
        app->authenticated = false;
        app->hello_pending = true;
    } else if(!now_connected) {
        app->authenticated = false;
    }
    app->connected = now_connected;
}

static void edit_done(void* context) {
    MessengerApp* app = context;
    if(app->edit_mode == EditPhone) {
        if(!fmsg_phone_valid(app->phone)) {
            show_text(app, "Invalid number", "Enter 3-15 digits. A leading + and common separators are allowed.", ViewMain);
            return;
        }
    } else if(app->edit_mode == EditQuick && app->edit_quick_index < FMSG_QUICK_MAX) {
        if(!app->body[0]) {
            show_text(app, "Empty message", "Quick messages cannot be empty.", ViewMain);
            return;
        }
        strlcpy(app->store.quick[app->edit_quick_index], app->body, sizeof(app->store.quick[0]));
        if(app->edit_quick_index == app->store.quick_count) ++app->store.quick_count;
        fmsg_store_save(&app->store);
    }
    if(app->edit_mode == EditPhone || app->edit_mode == EditBody) {
        strlcpy(app->store.draft_phone, app->phone, sizeof(app->store.draft_phone));
        strlcpy(app->store.draft_body, app->body, sizeof(app->store.draft_body));
        fmsg_store_save(&app->store);
    }
    switch_view(app, ViewMain);
}

static void start_edit(MessengerApp* app, EditMode mode) {
    app->edit_mode = mode;
    text_input_reset(app->input);
    if(mode == EditPhone) {
        text_input_set_header_text(app->input, "Recipient phone number");
        text_input_set_minimum_length(app->input, 3);
        text_input_set_result_callback(app->input, edit_done, app, app->phone, sizeof(app->phone), false);
    } else {
        text_input_set_header_text(app->input, mode == EditBody ? "Message (max 320)" : "Quick message");
        text_input_set_minimum_length(app->input, 1);
        text_input_set_result_callback(app->input, edit_done, app, app->body, sizeof(app->body), false);
    }
    switch_view(app, ViewInput);
}

static void prepare_list(MessengerApp* app, uint32_t kind);

static bool send_current(MessengerApp* app) {
    if(!app->authenticated || !fmsg_phone_valid(app->phone) || !app->body[0]) return false;
    const size_t phone_len = strlen(app->phone);
    const size_t body_len = strlen(app->body);
    if(phone_len > FMSG_PHONE_MAX || body_len > FMSG_BODY_MAX || body_len + phone_len + 3 > FMSG_MAX_PAYLOAD)
        return false;
    uint64_t request = ((uint64_t)furi_hal_random_get() << 32) | furi_hal_random_get();
    if(!request) request = 1;
    FmsgMessage message = {.type = FmsgTypeSendSms, .request_id = request};
    message.payload[0] = (uint8_t)phone_len;
    message.payload[1] = (uint8_t)body_len;
    message.payload[2] = (uint8_t)(body_len >> 8);
    memcpy(message.payload + 3, app->phone, phone_len);
    memcpy(message.payload + 3 + phone_len, app->body, body_len);
    message.payload_length = phone_len + body_len + 3;
    if(!tx_message(app, &message, true)) return false;

    if(app->store.recent_count == FMSG_RECENT_MAX) {
        memmove(&app->store.recent[1], &app->store.recent[0], (FMSG_RECENT_MAX - 1) * sizeof(FmsgRecent));
    } else {
        memmove(&app->store.recent[1], &app->store.recent[0], app->store.recent_count * sizeof(FmsgRecent));
        ++app->store.recent_count;
    }
    FmsgRecent* recent = &app->store.recent[0];
    memset(recent, 0, sizeof(*recent));
    strlcpy(recent->phone, app->phone, sizeof(recent->phone));
    strlcpy(recent->body, app->body, sizeof(recent->body));
    recent->request_id = request;
    recent->status = 0;
    app->store.draft_phone[0] = 0;
    app->store.draft_body[0] = 0;
    fmsg_store_save(&app->store);
    return true;
}

static void list_selected(void* context, uint32_t index) {
    MessengerApp* app = context;
    if(index >= 7000) {
        if(index == 7002 && app->selected_inbox < app->store.inbox_count) {
            char detail[FMSG_BODY_MAX + FMSG_PHONE_MAX + 32];
            snprintf(detail, sizeof(detail), "From: %s\n\n%s", app->store.inbox[app->selected_inbox].phone,
                     app->store.inbox[app->selected_inbox].body);
            show_text(app, "Incoming SMS", detail, ViewList);
        } else if(index == 7000 && app->selected_inbox < app->store.inbox_count) {
            if(!fmsg_phone_valid(app->store.inbox[app->selected_inbox].phone)) {
                show_text(app, "Cannot reply", "This sender does not contain a replyable phone number.", ViewList);
                return;
            }
            strlcpy(app->phone, app->store.inbox[app->selected_inbox].phone, sizeof(app->phone));
            app->body[0] = 0;
            start_edit(app, EditBody);
        } else {
            prepare_list(app, 5);
        }
    } else if(index >= 6000) {
        const size_t i = index - 6000;
        if(i < app->store.inbox_count) {
            app->selected_inbox = i;
            char summary[64];
            snprintf(summary, sizeof(summary), "Message from\n%s", app->store.inbox[i].phone);
            submenu_reset(app->list_menu);
            submenu_set_header(app->list_menu, summary);
            submenu_add_item(app->list_menu, "Read full message", 7002, list_selected, app);
            submenu_add_item(app->list_menu, "Reply", 7000, list_selected, app);
            submenu_add_item(app->list_menu, "Back to inbox", 7001, list_selected, app);
        }
    } else if(index >= 4000) {
        if(index == 4000) {
            if(send_current(app)) show_text(app, "Request sent", "The phone accepted the BLE request for processing. SENT and DELIVERED will only appear after Android reports them.", ViewMain);
            else show_text(app, "Not sent", "Connect and authorize the phone, then enter a valid recipient and message.", ViewMain);
        } else switch_view(app, ViewMain);
    } else if(index >= 3000) {
        const size_t i = index - 3000;
        if(i < app->store.quick_count) {
            strlcpy(app->body, app->store.quick[i], sizeof(app->body));
            prepare_list(app, 4);
        } else if(i == app->store.quick_count && i < FMSG_QUICK_MAX) {
            app->edit_quick_index = i;
            app->body[0] = 0;
            start_edit(app, EditQuick);
        }
    } else if(index >= 2000) {
        const size_t i = index - 2000;
        if(i < app->store.contact_count) {
            strlcpy(app->phone, app->store.contacts[i].phone, sizeof(app->phone));
            switch_view(app, ViewMain);
        }
    } else if(index >= 1000) {
        const size_t i = index - 1000;
        if(i < app->store.recent_count) {
            char detail[420];
            const char* statuses[] = {"Requested", "Accepted", "Sent", "Delivered", "Failed"};
            snprintf(detail, sizeof(detail), "To: %s\nStatus: %s\n\n%s", app->store.recent[i].phone,
                     statuses[MIN(app->store.recent[i].status, 4)], app->store.recent[i].body);
            show_text(app, "Outgoing message", detail, ViewList);
        }
    }
}

static void prepare_list(MessengerApp* app, uint32_t kind) {
    submenu_reset(app->list_menu);
    if(kind == 1) {
        submenu_set_header(app->list_menu, "Synced contacts");
        for(size_t i = 0; i < app->store.contact_count; ++i)
            submenu_add_item(app->list_menu, app->store.contacts[i].name, 2000 + i, list_selected, app);
        if(!app->store.contact_count) submenu_add_item(app->list_menu, "No contacts synced", 4999, list_selected, app);
    } else if(kind == 2) {
        submenu_set_header(app->list_menu, "Quick messages");
        for(size_t i = 0; i < app->store.quick_count; ++i)
            submenu_add_item(app->list_menu, app->store.quick[i], 3000 + i, list_selected, app);
        if(app->store.quick_count < FMSG_QUICK_MAX)
            submenu_add_item(app->list_menu, "+ Add quick message", 3000 + app->store.quick_count, list_selected, app);
    } else if(kind == 3) {
        submenu_set_header(app->list_menu, "Recent outgoing");
        for(size_t i = 0; i < app->store.recent_count; ++i)
            submenu_add_item(app->list_menu, app->store.recent[i].phone, 1000 + i, list_selected, app);
        if(!app->store.recent_count) submenu_add_item(app->list_menu, "No outgoing messages", 4999, list_selected, app);
    } else if(kind == 5) {
        submenu_set_header(app->list_menu, "Inbox: select to reply");
        for(size_t i = 0; i < app->store.inbox_count; ++i)
            submenu_add_item(app->list_menu, app->store.inbox[i].phone, 6000 + i, list_selected, app);
        if(!app->store.inbox_count) submenu_add_item(app->list_menu, "No forwarded messages", 4999, list_selected, app);
    } else {
        char summary[128];
        snprintf(summary, sizeof(summary), "To: %s\nMessage: %.75s%s", app->phone[0] ? app->phone : "(none)",
                 app->body[0] ? app->body : "(empty)", strlen(app->body) > 75 ? "..." : "");
        submenu_set_header(app->list_menu, summary);
        submenu_add_item(app->list_menu, "SEND NOW", 4000, list_selected, app);
        submenu_add_item(app->list_menu, "Cancel", 4001, list_selected, app);
    }
    switch_view(app, ViewList);
}

static void main_selected(void* context, uint32_t index) {
    MessengerApp* app = context;
    switch(index) {
    case MenuContacts:
        prepare_list(app, 1);
        break;
    case MenuManual:
        start_edit(app, EditPhone);
        break;
    case MenuCompose:
        start_edit(app, EditBody);
        break;
    case MenuQuick:
        prepare_list(app, 2);
        break;
    case MenuInbox:
        prepare_list(app, 5);
        break;
    case MenuDraft:
        prepare_list(app, 4);
        break;
    case MenuRecent:
        prepare_list(app, 3);
        break;
    case MenuSettings:
        switch_view(app, ViewSettings);
        break;
    default:
        show_text(app, "Flipper Messenger", "Version " FMSG_APP_VERSION "\n\nCompose SMS requests on Flipper Zero. An authorized Android companion sends them using the phone's real SMS service.\n\nBonded BLE + AES-256-GCM application sessions. No inbox, call logs, telemetry, or simulated delivery.\n\nGPL-3.0-or-later.", ViewMain);
        break;
    }
}

static void setting_selected(void* context, uint32_t row) {
    MessengerApp* app = context;
    if(row == 0) {
        app->allow_provision = true;
        app->provision_deadline = furi_get_tick() + furi_ms_to_ticks(60000);
        show_text(app, "Authorization open", "For 60 seconds, press Authorize this phone in the Android app. Keep both apps open.", ViewSettings);
    } else if(row == 1) {
        bt_forget_bonded_devices(app->bt);
        memset(app->store.key, 0, sizeof(app->store.key));
        app->store.has_key = false;
        app->authenticated = false;
        fmsg_store_save(&app->store);
        show_text(app, "Phone forgotten", "Bluetooth bonds and the Messenger authorization key were removed.", ViewSettings);
    } else if(row == 2) {
        fmsg_store_clear_private(&app->store);
        fmsg_store_save(&app->store);
        app->phone[0] = 0;
        app->body[0] = 0;
        show_text(app, "Private data cleared", "Contacts, drafts, recent outgoing records, and the authorization key were erased.", ViewSettings);
    } else {
        show_text(app, "About", "Flipper Messenger v" FMSG_APP_VERSION "\n\nAndroid sends real SMS for any valid number entered on Flipper. Bluetooth must be connected and authorized.\n\nGPL-3.0-or-later.", ViewSettings);
    }
}

static bool on_back(void* context) {
    MessengerApp* app = context;
    if(app->current == ViewMain) {
        if(app->body[0] || app->phone[0]) {
            strlcpy(app->store.draft_phone, app->phone, sizeof(app->store.draft_phone));
            strlcpy(app->store.draft_body, app->body, sizeof(app->store.draft_body));
            fmsg_store_save(&app->store);
        }
        view_dispatcher_stop(app->dispatcher);
    } else if(app->current == ViewText) {
        switch_view(app, app->text_back);
    } else {
        switch_view(app, ViewMain);
    }
    return true;
}

static void tick(void* context) {
    MessengerApp* app = context;
    if(app->allow_provision && furi_get_tick() > app->provision_deadline) app->allow_provision = false;
    if(app->hello_pending && app->connected) send_hello(app);
    for(size_t n = 0; n < FMSG_RX_SLOTS; ++n) {
        RxSlot slot = {0};
        if(furi_mutex_acquire(app->rx_mutex, 0) != FuriStatusOk) break;
        if(app->rx_count) {
            slot = app->rx[app->rx_tail];
            app->rx_tail = (app->rx_tail + 1) % FMSG_RX_SLOTS;
            --app->rx_count;
        }
        furi_mutex_release(app->rx_mutex);
        if(!slot.length) break;
        process_message(app, slot.data, slot.length);
        ble_profile_serial_notify_buffer_is_empty(app->profile);
    }
    char header[64];
    snprintf(header, sizeof(header), "Flipper Messenger\n%s", link_text(app));
    submenu_set_header(app->main_menu, header);
}

static MessengerApp* app_alloc(void) {
    MessengerApp* app = calloc(1, sizeof(*app));
    if(!app) return NULL;
    fmsg_store_init(&app->store);
    fmsg_store_load(&app->store);
    app->gui = furi_record_open(RECORD_GUI);
    app->bt = furi_record_open(RECORD_BT);
    app->dispatcher = view_dispatcher_alloc();
    app->main_menu = submenu_alloc();
    app->list_menu = submenu_alloc();
    app->input = text_input_alloc();
    app->settings = submenu_alloc();
    app->widget = widget_alloc();
    app->text = furi_string_alloc();
    app->rx_mutex = furi_mutex_alloc(FuriMutexTypeNormal);
    if(!app->gui || !app->bt || !app->dispatcher || !app->main_menu || !app->list_menu || !app->input ||
       !app->settings || !app->widget || !app->text || !app->rx_mutex) return app;

    submenu_add_item(app->main_menu, "Contacts", MenuContacts, main_selected, app);
    submenu_add_item(app->main_menu, "Manual number", MenuManual, main_selected, app);
    submenu_add_item(app->main_menu, "Compose message", MenuCompose, main_selected, app);
    submenu_add_item(app->main_menu, "Quick messages", MenuQuick, main_selected, app);
    submenu_add_item(app->main_menu, "Inbox / reply", MenuInbox, main_selected, app);
    submenu_add_item(app->main_menu, "Review / send", MenuDraft, main_selected, app);
    submenu_add_item(app->main_menu, "Recent outgoing", MenuRecent, main_selected, app);
    submenu_add_item(app->main_menu, "Settings", MenuSettings, main_selected, app);
    submenu_add_item(app->main_menu, "About", MenuAbout, main_selected, app);

    submenu_set_header(app->settings, "Settings v" FMSG_APP_VERSION);
    submenu_add_item(app->settings, "Authorize phone (60s)", 0, setting_selected, app);
    submenu_add_item(app->settings, "Forget phone", 1, setting_selected, app);
    submenu_add_item(app->settings, "Clear private data", 2, setting_selected, app);
    submenu_add_item(app->settings, "About / version", 3, setting_selected, app);

    view_dispatcher_set_event_callback_context(app->dispatcher, app);
    view_dispatcher_set_navigation_event_callback(app->dispatcher, on_back);
    view_dispatcher_set_tick_event_callback(app->dispatcher, tick, 100);
    view_dispatcher_add_view(app->dispatcher, ViewMain, submenu_get_view(app->main_menu));
    view_dispatcher_add_view(app->dispatcher, ViewList, submenu_get_view(app->list_menu));
    view_dispatcher_add_view(app->dispatcher, ViewInput, text_input_get_view(app->input));
    view_dispatcher_add_view(app->dispatcher, ViewSettings, submenu_get_view(app->settings));
    view_dispatcher_add_view(app->dispatcher, ViewText, widget_get_view(app->widget));
    view_dispatcher_attach_to_gui(app->dispatcher, app->gui, ViewDispatcherTypeFullscreen);
    return app;
}

static bool app_valid(const MessengerApp* app) {
    return app && app->gui && app->bt && app->dispatcher && app->main_menu && app->list_menu && app->input &&
           app->settings && app->widget && app->text && app->rx_mutex;
}

static void app_free(MessengerApp* app) {
    if(!app) return;
    if(app->bt) {
        bt_set_status_changed_callback(app->bt, NULL, NULL);
        bt_disconnect(app->bt);
        furi_delay_ms(200);
        bt_keys_storage_set_default_path(app->bt);
        if(app->profile) bt_profile_restore_default(app->bt);
    }
    if(app->dispatcher) {
        view_dispatcher_remove_view(app->dispatcher, ViewText);
        view_dispatcher_remove_view(app->dispatcher, ViewSettings);
        view_dispatcher_remove_view(app->dispatcher, ViewInput);
        view_dispatcher_remove_view(app->dispatcher, ViewList);
        view_dispatcher_remove_view(app->dispatcher, ViewMain);
    }
    if(app->widget) widget_free(app->widget);
    if(app->settings) submenu_free(app->settings);
    if(app->input) text_input_free(app->input);
    if(app->list_menu) submenu_free(app->list_menu);
    if(app->main_menu) submenu_free(app->main_menu);
    if(app->dispatcher) view_dispatcher_free(app->dispatcher);
    if(app->text) furi_string_free(app->text);
    if(app->rx_mutex) furi_mutex_free(app->rx_mutex);
    if(app->bt) furi_record_close(RECORD_BT);
    if(app->gui) furi_record_close(RECORD_GUI);
    memset(&app->store, 0, sizeof(app->store));
    free(app);
}

int32_t flipper_messenger_app(void* p) {
    UNUSED(p);
    MessengerApp* app = app_alloc();
    if(!app_valid(app)) {
        app_free(app);
        return -1;
    }
    strlcpy(app->phone, app->store.draft_phone, sizeof(app->phone));
    strlcpy(app->body, app->store.draft_body, sizeof(app->body));
    bt_disconnect(app->bt);
    furi_delay_ms(200);
    bt_keys_storage_set_storage_path(app->bt, FMSG_BT_KEYS);
    app->profile = bt_profile_start(app->bt, ble_profile_serial, NULL);
    if(!app->profile) {
        show_text(app, "Bluetooth error", "Could not start the official BLE serial profile.", ViewMain);
    } else {
        ble_profile_serial_set_event_callback(app->profile, FMSG_RX_SLOTS * FMSG_MAX_FRAME, serial_callback, app);
        ble_profile_serial_set_rpc_active(app->profile, false);
        bt_set_status_changed_callback(app->bt, bt_status, app);
        furi_hal_bt_start_advertising();
    }
    switch_view(app, ViewMain);
    view_dispatcher_run(app->dispatcher);
    fmsg_store_save(&app->store);
    app_free(app);
    return 0;
}

