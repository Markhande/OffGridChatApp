package com.saurabh.offgridchat.repository

import com.saurabh.offgridchat.database.MESSAGE_STATUS_FAILED
import com.saurabh.offgridchat.database.MESSAGE_STATUS_SENDING
import com.saurabh.offgridchat.database.MESSAGE_STATUS_SENT
import com.saurabh.offgridchat.tools.ChatMessage
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Persists the outgoing ID before the transport can expose it to a peer's ACK. */
class MessageDelivery @Inject constructor(private val repository: ChatRepository) {
    suspend fun send(message: ChatMessage, encryptionVersion: Int, transmit: suspend () -> Boolean): Boolean {
        require(message.fromMe)
        if (!repository.saveMessage(message.copy(status = MESSAGE_STATUS_SENDING),
                encrypted = true, encryptionVersion = encryptionVersion)) return false
        var sent = false
        try {
            sent = transmit()
            return sent
        } finally {
            // Cancellation or an exception after persistence must not strand SENDING.
            // The atomic DAO transition preserves a faster DELIVERED or READ receipt.
            withContext(NonCancellable) {
                repository.updateMessageStatus(message.messageId,
                    if (sent) MESSAGE_STATUS_SENT else MESSAGE_STATUS_FAILED)
            }
        }
    }
}
