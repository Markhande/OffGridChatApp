package com.saurabh.offgridchat.protocol

enum class PacketType {
    AUTH_HELLO,
    AUTH_PROOF,
    DATA,
    GROUP_DATA,
    ACK,
    READ_RECEIPT,
    PRESENCE_PING,
    PRESENCE_PONG,
    CONNECT_REQUEST,
    CONNECT_RESPONSE,
    ROUTE_ANNOUNCE
}
