package com.example.ykdsummer.bot.message;

import io.github.morningwn.protocol.ProtocolValues;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ILinkMessageTypeTest {

    @Test
    void mapsAllKnownSdkItemTypes() {
        assertEquals(ILinkMessageType.TEXT, ILinkMessageType.from(ProtocolValues.ITEM_TYPE_TEXT));
        assertEquals(ILinkMessageType.IMAGE, ILinkMessageType.from(ProtocolValues.ITEM_TYPE_IMAGE));
        assertEquals(ILinkMessageType.VOICE, ILinkMessageType.from(ProtocolValues.ITEM_TYPE_VOICE));
        assertEquals(ILinkMessageType.FILE, ILinkMessageType.from(ProtocolValues.ITEM_TYPE_FILE));
        assertEquals(ILinkMessageType.VIDEO, ILinkMessageType.from(ProtocolValues.ITEM_TYPE_VIDEO));
    }

    @Test
    void mapsMissingOrFutureTypesToUnknown() {
        assertEquals(ILinkMessageType.UNKNOWN, ILinkMessageType.from(null));
        assertEquals(ILinkMessageType.UNKNOWN, ILinkMessageType.from(999));
    }
}
