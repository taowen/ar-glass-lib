package com.taowen.arglass

/** Device-data contract, not a choice of estimator or rendering implementation. */
enum class ImuInputEncoding { XREAL_USB_REPORT, RUNTIME_SI }

data class ImuInputContract(
    val encoding: ImuInputEncoding,
    /** Vendor's actual hardware discriminator; absent for non-XREAL devices. */
    val xrealDeviceKind: Int? = null,
    /** Verified sensor cadence, not display refresh or an invented timestamp. */
    val nominalSamplePeriodNanos: Long? = null,
) {
    init {
        require(nominalSamplePeriodNanos == null || nominalSamplePeriodNanos > 0)
        require(encoding == ImuInputEncoding.XREAL_USB_REPORT || xrealDeviceKind == null)
    }
}
