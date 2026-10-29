package com.saurabh.offgridchat.protocol

enum class PacketType {
    AUTH_HELLO,
    AUTH_PROOF,
    DATA,
    FILE_CHUNK,
    CALL_SIGNAL,
    CALL_AUDIO,
    GROUP_DATA,
    ACK,
    READ_RECEIPT,
    PRESENCE_PING,
    PRESENCE_PONG,
    CONNECT_REQUEST,
    CONNECT_RESPONSE,
    ROUTE_ANNOUNCE
}
