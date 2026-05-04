package com.igotitinc.demo

import com.igotitinc.sdk.analytics.IGIAnalyticsListener

/**
 * Sample analytics bridge. The 4.0.0 SDK preserves the legacy SDK's
 * explicit-method `IGIAnalyticsListener` interface — same set of
 * methods (`trackPurchase`, `trackBid`, `trackPromotion`,
 * `trackAddPayment`, `trackAddShipping`, `trackItemListSelection`,
 * `trackItemSelection`, `trackEvent`) — with two readability
 * improvements:
 *
 *   1. **Typed parameter names** — `transactionId: String`,
 *      `userId: String`, etc. Legacy 3.x used `p0..p8` because the
 *      Java SDK's `.class` file lost parameter names at compile.
 *   2. **Non-nullable types** — every `String` / `Double` / `Int`
 *      argument is non-null in 4.0.0. Legacy was `String?` /
 *      `Double?` etc. because the Java SDK declared them with
 *      `@Nullable`. The SDK guarantees these are populated before
 *      dispatching, so the listener doesn't have to defensively
 *      null-check.
 *   3. **`trackEvent` payload type** — was `Bundle?` in 3.x; now
 *      `Map<String, Any>` (matches iOS `[String: Any]`). Convert
 *      to a Bundle inside this method if your analytics provider
 *      requires Android `Bundle` (Firebase Analytics does).
 */
class AnalyticsManager : IGIAnalyticsListener {

    override fun trackPurchase(
        transactionId: String,
        userId: String,
        itemId: String,
        itemName: String,
        itemCategory: String,
        price: Double,
        quantity: Int,
        total: Double,
        currencyCode: String,
    ) {
        // Forward to your analytics provider here.
    }

    override fun trackBid(
        transactionId: String,
        userId: String,
        itemId: String,
        itemName: String,
        itemCategory: String,
        price: Double,
        quantity: Int,
        total: Double,
        currencyCode: String,
        bidType: String,
    ) {
        // Forward to your analytics provider here.
    }

    override fun trackPromotion(
        transactionId: String,
        userId: String,
        itemId: String,
        itemName: String,
        itemCategory: String,
        price: Double,
        quantity: Int,
        total: Double,
        currencyCode: String,
    ) {
        // Forward to your analytics provider here.
    }

    override fun trackAddPayment(userId: String) {
        // Forward to your analytics provider here.
    }

    override fun trackAddShipping(userId: String) {
        // Forward to your analytics provider here.
    }

    override fun trackItemListSelection(
        itemId: String,
        itemName: String,
        itemCategory: String,
    ) {
        // Forward to your analytics provider here.
    }

    override fun trackItemSelection(
        itemId: String,
        itemName: String,
        itemCategory: String,
        price: Double,
    ) {
        // Forward to your analytics provider here.
    }

    override fun trackEvent(eventName: String, attributes: Map<String, Any>) {
        // Forward to your analytics provider here. If you need a Bundle
        // (e.g. Firebase Analytics), build it from `attributes`:
        //
        //   val bundle = Bundle().apply {
        //       attributes.forEach { (k, v) ->
        //           when (v) {
        //               is String -> putString(k, v)
        //               is Int    -> putInt(k, v)
        //               is Long   -> putLong(k, v)
        //               is Double -> putDouble(k, v)
        //               is Boolean -> putBoolean(k, v)
        //           }
        //       }
        //   }
    }
}
