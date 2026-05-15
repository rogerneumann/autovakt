package com.rogerneumann.vakt.auto.render

import com.rogerneumann.vakt.data.SlotDisplayType

data class GaugeSlot(
    val label: String,
    val value: String,
    val unit: String,
    val displayType: SlotDisplayType = SlotDisplayType.NUMERIC,
    val fraction: Float? = null   // 0..1 for ARC and BAR; null for NUMERIC
)
