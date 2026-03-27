package com.example.marketplace.fcfs.repository

import com.example.marketplace.fcfs.entity.FcfsOrder
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface FcfsOrderRepository : JpaRepository<FcfsOrder, Long> {

    @Modifying
    @Query("DELETE FROM FcfsOrder")
    fun deleteAllFcfsOrders()
}
