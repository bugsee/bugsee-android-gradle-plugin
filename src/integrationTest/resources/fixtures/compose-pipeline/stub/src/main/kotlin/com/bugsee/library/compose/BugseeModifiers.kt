package com.bugsee.library.compose

import androidx.compose.ui.Modifier

// Stand-ins for the Bugsee SDK's extension functions that the compiler plugin injects calls to. They
// live in their own module because a function declared in the module being compiled is a source
// declaration, not the library function a consumer resolves.
fun Modifier.bugseeTag(tag: String): Modifier = this

fun Modifier.bugseeSecure(): Modifier = this
