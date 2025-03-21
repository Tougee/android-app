package one.mixin.android.pay

import one.mixin.android.Constants
import one.mixin.android.api.response.AddressFeeResponse
import one.mixin.android.extension.stripAmountZero
import one.mixin.android.extension.toUri
import one.mixin.android.pay.erc831.isEthereumURLString
import one.mixin.android.vo.AssetPrecision

suspend fun parseExternalTransferUri(
    url: String,
    getAddressFee: suspend (String, String) -> AddressFeeResponse?,
    findAssetIdByAssetKey: suspend (String) -> String?,
    getAssetPrecisionById: suspend (String) -> AssetPrecision?,
): ExternalTransfer? {
    if (url.isEthereumURLString()) {
        return parseEthereum(url, getAddressFee, findAssetIdByAssetKey, getAssetPrecisionById)
    }

    val uri = url.addSlashesIfNeeded().toUri()
    val scheme = uri.scheme
    val assetId = externalTransferAssetIdMap[scheme] ?: return null

    if (assetId == Constants.ChainId.Solana) {
        val splToken = uri.getQueryParameter("spl-token")
        if (!splToken.isNullOrEmpty()) {
            return null
        }
    }

    val destination = uri.host ?: return null
    val addressFeeResponse = getAddressFee(assetId, destination) ?: return null
    if (!addressFeeResponse.destination.equals(destination, true)) {
        return null
    }

    var amount = uri.getQueryParameter("amount")
    if (amount == null) {
        amount = uri.getQueryParameter("tx_amount")
    }
    if (amount.isNullOrEmpty()) return null
    if (amount.amountWithE()) return null

    amount = amount.stripAmountZero()
    val amountBD = amount.toBigDecimalOrNull() ?: return null
    if (amount != amountBD.toPlainString()) {
        return null
    }
    val memo = uri.getQueryParameter("memo")
    return ExternalTransfer(addressFeeResponse.destination, amount, assetId, addressFeeResponse.fee.toBigDecimalOrNull(), memo)
}

// check amount has scientific E
fun String?.amountWithE(): Boolean = this?.contains("e") == true

fun String.addSlashesIfNeeded(): String {
    if (this.indexOf("://") != -1) {
        return this
    }
    return this.replace(":", "://")
}

val externalTransferAssetIdMap by lazy {
    mapOf(
        "bitcoin" to Constants.ChainId.BITCOIN_CHAIN_ID,
        "ethereum" to Constants.ChainId.ETHEREUM_CHAIN_ID,
        "litecoin" to Constants.ChainId.Litecoin,
        "dash" to Constants.ChainId.Dash,
        "dogecoin" to Constants.ChainId.Dogecoin,
        "monero" to Constants.ChainId.Monero,
        "solana" to Constants.ChainId.Solana,
    )
}
