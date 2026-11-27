package com.capitol.app

import com.capitol.app.agent.AutopilotWorker
import com.capitol.app.data.broker.CreditCardBill
import com.capitol.app.data.broker.CreditCards
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Credit card bills from INDmoney's networth_snapshot liabilities section. */
class CreditCardsTest {
    // Shape reported by INDmoney's networth_snapshot (as relayed by the AI agent).
    private val snapshot = """{"total_networth":733511.48,"total_current_value":1397208.87,
        "liabilities":{"total_loan_balance":0.0,"total_credit_card_due":663697.39,"total":663697.39,"loans":[],
          "credit_cards":[
            {"card_variant":"Citi Rewards Platinum","bank_name":"Citi","masked_card_number":"XXXX XXXX XXXX 1433","total_due":633283.39,"due_date":"2026-12-12"},
            {"card_variant":"Swiggy HDFC Bank","bank_name":"HDFC","last_4":"6465","total_due":"30,414","min_due":1520,"due_date":"2026-12-03T00:00:00+05:30"}]}}"""

    @Test fun parsesSnapshotLiabilities() {
        val cards = CreditCards.parse(snapshot)
        assertEquals(2, cards.size)
        val citi = cards[0]
        assertEquals("Citi Rewards Platinum", citi.name); assertEquals("1433", citi.last4)
        assertEquals(633283.39, citi.totalDue, 1e-6); assertEquals(LocalDate.of(2026, 12, 12), citi.dueDate)
        val hdfc = cards[1]
        assertEquals("Swiggy HDFC Bank ••6465", hdfc.label); assertEquals(30414.0, hdfc.totalDue, 0.0)
        assertEquals(1520.0, hdfc.minDue!!, 0.0); assertEquals(LocalDate.of(2026, 12, 3), hdfc.dueDate)
        assertEquals(663697.39, cards.sumOf { it.totalDue }, 1e-6)
        assertEquals(6L, hdfc.daysUntilDue(LocalDate.of(2026, 11, 27)))
    }

    @Test fun handlesProseEpochAndNoCards() {
        val prose = """Here is your snapshot: {"liabilities":{"credit_cards":[{"card_name":"Axis Ace","amount_due":1200,"due_date":1796083200}]}} Let me know!"""
        val c = CreditCards.parse(prose).single()
        assertEquals("Axis Ace", c.name); assertNull(c.last4); assertEquals(1200.0, c.totalDue, 0.0)
        assertTrue(c.dueDate != null)
        assertTrue(CreditCards.parse("""{"liabilities":{"credit_cards":[],"loans":[]}}""").isEmpty())
        assertTrue(CreditCards.parse("no data").isEmpty())
    }

    @Test fun remindersThreeDaysBeforeAndOnTheDay() {
        val card = CreditCardBill("Swiggy HDFC Bank", "6465", 30414.0, dueDate = LocalDate.of(2026, 12, 3))
        assertNull(AutopilotWorker.cardReminder(card, LocalDate.of(2026, 11, 27)))
        assertEquals("soon", AutopilotWorker.cardReminder(card, LocalDate.of(2026, 11, 30))!!.first)
        assertTrue(AutopilotWorker.cardReminder(card, LocalDate.of(2026, 12, 2))!!.second.contains("due in 1 day"))
        assertEquals("today", AutopilotWorker.cardReminder(card, LocalDate.of(2026, 12, 3))!!.first)
        assertNull(AutopilotWorker.cardReminder(card.copy(totalDue = 0.0), LocalDate.of(2026, 12, 3)))
    }
}
