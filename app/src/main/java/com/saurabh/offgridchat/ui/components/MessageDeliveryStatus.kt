package com.saurabh.offgridchat.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saurabh.offgridchat.database.*
import com.saurabh.offgridchat.ui.theme.LocalOgAccent

@Composable
fun MessageDeliveryStatus(status: String) {
    val a = LocalOgAccent.current
    when (status) {

        MESSAGE_STATUS_SENDING -> {

            Text(
                "⏳",
                fontSize = 11.sp
            )
        }

        MESSAGE_STATUS_SENT -> {

            Icon(
                imageVector =
                    Icons.Default.Done,
                contentDescription =
                    "Sent",
                tint =
                    a.on.copy(
                        alpha = 0.85f
                    ),
                modifier =
                    Modifier.size(
                        13.dp
                    )
            )
        }

        MESSAGE_STATUS_DELIVERED -> {

            Icon(
                imageVector =
                    Icons.Default.DoneAll,
                contentDescription =
                    "Delivered",
                tint =
                    a.on.copy(
                        alpha = 0.85f
                    ),
                modifier =
                    Modifier.size(
                        13.dp
                    )
            )
        }

        MESSAGE_STATUS_READ -> {

            Icon(
                imageVector =
                    Icons.Default.DoneAll,
                contentDescription =
                    "Read",
                tint =
                    Color(0xFF60A5FA),
                modifier =
                    Modifier.size(
                        13.dp
                    )
            )
        }

        MESSAGE_STATUS_FAILED -> {

            Text(
                "!",
                fontSize = 12.sp,
                fontWeight =
                    FontWeight.Bold,
                color =
                    a.on
            )
        }

        else -> {

            Icon(
                imageVector =
                    Icons.Default.DoneAll,
                contentDescription =
                    "Delivered",
                tint =
                    a.on.copy(
                        alpha = 0.85f
                    ),
                modifier =
                    Modifier.size(
                        13.dp
                    )
            )
        }
    }
}
