package com.adamtz.ai_sight_android  // use your app's package here

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

class AccessibleFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    override fun performClick(): Boolean {
        super.performClick()  // call super to handle accessibility events correctly
        return true
    }
}
