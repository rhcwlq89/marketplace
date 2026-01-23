package com.example.marketplace.order

import jakarta.persistence.Embeddable

@Embeddable
class ShippingAddress(
    var zipCode: String = "",
    var address: String = "",
    var addressDetail: String? = null,
    var receiverName: String = "",
    var receiverPhone: String = ""
)
